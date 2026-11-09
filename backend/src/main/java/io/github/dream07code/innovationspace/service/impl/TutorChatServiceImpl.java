package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.scoring.ScoringProperties;
import io.github.dream07code.innovationspace.scoring.TutorChatAi;
import io.github.dream07code.innovationspace.scoring.TutorChatRubric;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;
import io.github.dream07code.innovationspace.service.TutorChatService;
import io.github.dream07code.innovationspace.tutor.TutorConversation;
import io.github.dream07code.innovationspace.tutor.TutorMessage;
import io.github.dream07code.innovationspace.tutor.TutorRepository;
import io.github.dream07code.innovationspace.tutor.TutorSupport;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 通用 AI 助教实现。
 *
 * <p>与提交助教共用同一个模型与限流，但不读代码、不读判题结果：上下文只有会话关联的题目。
 * 题目由 {@link AssessmentProblemService#get} 按 problemId 取，自带可见性校验，
 * 学生传别人看不到的题目 id 会直接 404，不会把未发布题面送进提示词。
 *
 * <p>会话与消息落库：历史由服务端按 conversationId 从 tutor_message 取最近若干条，
 * 前端不再上传历史。conversationId 为空时自动新建会话并回传 id，
 * 因此「先发第一句」和「点新建再发」在前端是同一条路径。
 *
 * <p>会话绑定题目后不再跟随请求切换：同一会话里前端传别的 problemId 会被忽略，
 * 避免模型在一段对话里同时看到两道题的题面。
 */
@Service
public class TutorChatServiceImpl implements TutorChatService {
  private static final Logger log = LoggerFactory.getLogger(TutorChatServiceImpl.class);
  /** 提示词里最多带最近 12 条消息（约 6 轮），够承接上下文又不至于把提示词撑爆。 */
  private static final int MAX_HISTORY_MESSAGES = 12;
  /** 单条历史进提示词时的上限。 */
  private static final int MAX_TURN_CHARS = 2000;
  /** 落库上限：比提示词上限宽，学生刷新后看到的仍是完整回答。 */
  private static final int MAX_CONTENT_CHARS = 8000;
  private static final int MAX_MESSAGE_CHARS = 1000;
  private static final long AI_WAIT_SECONDS = 20;
  private static final String NOT_ENABLED =
      "AI 助教未启用：需要 AI_SCORING_ENABLED=true 与 AI_PROVIDER=openai-compatible。";

  private final TutorRepository repository;
  private final AssessmentProblemService problems;
  private final TutorChatAi ai;
  private final ScoringProperties aiProperties;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final Semaphore aiSlots;

  public TutorChatServiceImpl(
      TutorRepository repository,
      AssessmentProblemService problems,
      TutorChatAi ai,
      ScoringProperties aiProperties,
      Clock clock,
      PlatformTransactionManager manager) {
    this.repository = repository;
    this.problems = problems;
    this.ai = ai;
    this.aiProperties = aiProperties;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
    this.aiSlots = new Semaphore(Math.max(2, aiProperties.getMaxConcurrency()));
  }

  @Override
  public Map<String, Object> chat(Map<String, Object> request, SessionPrincipal actor) {
    long memberId = requireMember(actor);
    var body = request == null ? Map.<String, Object>of() : request;
    String message = text(body.get("message"));
    if (message.isBlank())
      throw new ApiException(400, ErrorCode.INVALID_REQUEST, "message is required");
    String question = cap(message, MAX_MESSAGE_CHARS);

    var resolved = conversation(body, actor, memberId, question);
    var conversation = resolved.conversation();
    // 历史必须在写入本次提问之前读：否则提示词里会出现两条相同的问题。
    var history = history(conversation.id());
    storeUserMessage(conversation.id(), question);

    var input = new TutorChatAi.ChatInput(problem(resolved.problemRow()), history, question);

    var out = new LinkedHashMap<String, Object>();
    out.put("conversationId", String.valueOf(conversation.id()));
    out.put("conversationTitle", conversation.title());
    out.put("promptVersion", TutorChatRubric.VERSION);
    out.put("generatedAt", TutorSupport.time(clock.instant()));

    if (!aiProperties.isEnabled() || !"openai-compatible".equals(aiProperties.getProvider())) {
      out.put("available", false);
      out.put("reply", null);
      out.put("model", null);
      out.put("error", NOT_ENABLED);
      return out;
    }

    boolean acquired;
    try {
      acquired = aiSlots.tryAcquire(AI_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      acquired = false;
    }
    if (!acquired) {
      out.put("available", false);
      out.put("reply", null);
      out.put("model", null);
      out.put("error", "AI 助教繁忙，请稍后重试。");
      return out;
    }
    try {
      var reply = ai.chat(input);
      storeAssistant(conversation.id(), reply.content());
      out.put("available", true);
      out.put("reply", reply.content());
      out.put("model", reply.model());
      return out;
    } catch (TutorChatAi.Unavailable error) {
      log.warn("AI tutor chat unavailable: {}", error.getMessage());
      out.put("available", false);
      out.put("reply", null);
      out.put("model", null);
      out.put("error", "AI 助教暂不可用，请稍后重试。");
      return out;
    } finally {
      aiSlots.release();
    }
  }

  /**
   * 定位会话：给了 id 就取本人的会话（别人的 id 一律 404），没给就新建。
   *
   * <p>这里只做会话本身的读写；本次提问由调用方在读完历史之后写入，
   * 避免新问题同时出现在「历史」和「本轮问题」里。题面顺带返回，避免同一题查两遍。
   */
  private Resolved conversation(
      Map<String, Object> body, SessionPrincipal actor, long memberId, String question) {
    Long conversationId = parseConversationId(body.get("conversationId"));
    var now = clock.instant();

    if (conversationId != null) {
      var existing =
          repository
              .findConversation(conversationId)
              .map(
                  summary -> {
                    if (summary.conversation().memberId() != memberId)
                      throw new ApiException(404, ErrorCode.NOT_FOUND);
                    return summary.conversation();
                  })
              .orElseThrow(() -> new ApiException(404, ErrorCode.NOT_FOUND));

      Long problemId = existing.problemId();
      // 题目可能已退役或被删除：老会话不该因此整段打不开，读不到题面就降级成纯知识点问答。
      Map<String, Object> problemRow = problemId == null ? null : visibleProblem(problemId, actor);
      // 只有还没绑定题目的会话才接受本次请求里的 problemId，绑定后不再切换上下文。
      if (problemId == null) {
        var requested = optionalProblem(body.get("problemId"), actor);
        if (requested != null) {
          problemId = requested.id();
          problemRow = requested.row();
          repository.setConversationProblem(existing.id(), problemId, now);
        }
      }
      var conversation =
          new TutorConversation(
              existing.id(),
              existing.memberId(),
              existing.title(),
              problemId,
              existing.createdAt(),
              now);
      return new Resolved(conversation, problemRow);
    }

    var requested = optionalProblem(body.get("problemId"), actor);
    Long problemId = requested == null ? null : requested.id();
    String title = TutorSupport.titleFrom(question);
    long id = repository.createConversation(memberId, title, problemId, now);
    return new Resolved(
        new TutorConversation(id, memberId, title, problemId, now, now),
        requested == null ? null : requested.row());
  }

  /** 已解析的会话与题面：题面只查一次，后续拼提示词直接用。 */
  private record Resolved(TutorConversation conversation, Map<String, Object> problemRow) {}

  /** 合法的题目 id 与它的题面行。 */
  private record ProblemRef(long id, Map<String, Object> row) {}

  private void storeUserMessage(long conversationId, String question) {
    var now = clock.instant();
    tx.execute(
        status -> {
          repository.appendMessage(conversationId, "user", cap(question, MAX_CONTENT_CHARS), now);
          repository.touchConversation(conversationId, now);
          return null;
        });
  }

  /** 只取最近若干条，且逐条截断：历史是提示词输入，不是事实来源。 */
  private List<TutorChatAi.Turn> history(long conversationId) {
    List<TutorMessage> rows = repository.recentMessages(conversationId, MAX_HISTORY_MESSAGES);
    var turns = new ArrayList<TutorChatAi.Turn>(rows.size());
    for (var row : rows) {
      String role = TutorSupport.lower(row.role());
      if (!"user".equals(role) && !"assistant".equals(role)) continue;
      String content = text(row.content());
      if (content.isBlank()) continue;
      turns.add(new TutorChatAi.Turn(role, cap(content, MAX_TURN_CHARS)));
    }
    return List.copyOf(turns);
  }

  private void storeAssistant(long conversationId, String content) {
    var now = clock.instant();
    tx.execute(
        status -> {
          repository.appendMessage(conversationId, "assistant", cap(content, MAX_CONTENT_CHARS), now);
          repository.touchConversation(conversationId, now);
          return null;
        });
  }

  /** problemId 可空；给了就必须是合法 id 且当前角色可见（get 内部做可见性校验）。 */
  private TutorChatAi.Problem problem(Map<String, Object> row) {
    if (row == null) return null;
    return new TutorChatAi.Problem(
        string(row.get("title")),
        string(row.get("difficulty")),
        string(row.get("description")),
        string(row.get("inputFormat")),
        string(row.get("outputFormat")),
        string(row.get("tags")),
        string(row.get("functionName")),
        join(row.get("parameterTypes")),
        string(row.get("returnType")));
  }

  private ProblemRef optionalProblem(Object raw, SessionPrincipal actor) {
    String value = text(raw);
    if (value.isBlank()) return null;
    if (!TutorSupport.isId(value))
      throw new ApiException(400, ErrorCode.INVALID_REQUEST, "problemId is invalid");
    long problemId = Long.parseLong(value);
    return new ProblemRef(problemId, problems.get(problemId, actor));
  }

  /**
   * 会话里已绑定的题目：学生现在看不到（退役、下架、被删）时返回 null，而不是抛 404。
   *
   * <p>这与「请求里带不可见 problemId」不同：那是学生主动试探，必须 404；
   * 这里是历史上下文失效，丢掉题面继续问答才是可用行为。
   */
  private Map<String, Object> visibleProblem(long problemId, SessionPrincipal actor) {
    try {
      return problems.get(problemId, actor);
    } catch (ApiException error) {
      if (error.status() == 404) return null;
      throw error;
    }
  }

  private static Long parseConversationId(Object raw) {
    String value = text(raw);
    if (value.isBlank()) return null;
    if (!TutorSupport.isId(value))
      throw new ApiException(400, ErrorCode.INVALID_REQUEST, "conversationId is invalid");
    return Long.valueOf(value);
  }

  private static long requireMember(SessionPrincipal actor) {
    if (actor == null || actor.memberId() == null) throw new ApiException(403, ErrorCode.FORBIDDEN);
    return actor.memberId();
  }

  /** parameterTypes 在题库里是数组，提示词里拼成 int,int 比 [int, int] 更易读。 */
  private static String join(Object value) {
    if (value instanceof List<?> list) {
      var parts = new ArrayList<String>(list.size());
      for (Object item : list) parts.add(string(item));
      return String.join(",", parts);
    }
    return string(value);
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String cap(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
