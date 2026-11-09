package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.scoring.ScoringProperties;
import io.github.dream07code.innovationspace.scoring.TutorChatAi;
import io.github.dream07code.innovationspace.scoring.TutorChatRubric;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;
import io.github.dream07code.innovationspace.service.TutorChatService;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 通用 AI 助教实现。
 *
 * <p>与提交助教共用同一个模型与限流，但不读代码、不读判题结果：上下文只有学生选中的题目。
 * 题目由 {@link AssessmentProblemService#get} 按 problemId 取，自带可见性校验，
 * 学生传别人看不到的题目 id 会直接 404，不会把未发布题面送进提示词。
 *
 * <p>不写任何表、不落会话：历史由前端保存在 localStorage 并逐轮带回，因此没有数据库结构变更，
 * 也不存在会话残留清理问题。
 */
@Service
public class TutorChatServiceImpl implements TutorChatService {
  private static final Logger log = LoggerFactory.getLogger(TutorChatServiceImpl.class);
  private static final int MAX_HISTORY_TURNS = 12;
  private static final int MAX_TURN_CHARS = 2000;
  private static final int MAX_MESSAGE_CHARS = 1000;
  private static final long AI_WAIT_SECONDS = 20;
  private static final DateTimeFormatter OFFSET =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
  private static final String NOT_ENABLED =
      "AI 助教未启用：需要 AI_SCORING_ENABLED=true 与 AI_PROVIDER=openai-compatible。";

  private final AssessmentProblemService problems;
  private final TutorChatAi ai;
  private final ScoringProperties aiProperties;
  private final Clock clock;
  private final Semaphore aiSlots;

  public TutorChatServiceImpl(
      AssessmentProblemService problems,
      TutorChatAi ai,
      ScoringProperties aiProperties,
      Clock clock) {
    this.problems = problems;
    this.ai = ai;
    this.aiProperties = aiProperties;
    this.clock = clock;
    this.aiSlots = new Semaphore(Math.max(2, aiProperties.getMaxConcurrency()));
  }

  @Override
  public Map<String, Object> chat(Map<String, Object> request, SessionPrincipal actor) {
    String message = text(request == null ? null : request.get("message"));
    if (message.isBlank())
      throw new ApiException(400, ErrorCode.INVALID_REQUEST, "message is required");

    var problem = problem(request.get("problemId"), actor);

    var input =
        new TutorChatAi.ChatInput(
            problem, history(request.get("history")), cap(message, MAX_MESSAGE_CHARS));

    var out = new LinkedHashMap<String, Object>();
    out.put("promptVersion", TutorChatRubric.VERSION);
    out.put("generatedAt", now());

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

  /** problemId 可空；给了就必须是合法 id 且当前角色可见（get 内部做可见性校验）。 */
  private TutorChatAi.Problem problem(Object raw, SessionPrincipal actor) {
    String value = text(raw);
    if (value.isBlank()) return null;
    if (!value.matches("[1-9][0-9]{0,18}"))
      throw new ApiException(400, ErrorCode.INVALID_REQUEST, "problemId is invalid");
    var row = problems.get(Long.parseLong(value), actor);
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

  /** 历史对话只接受 user/assistant 两种角色，非法项直接丢弃，不让脏结构进提示词。 */
  private List<TutorChatAi.Turn> history(Object raw) {
    if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
    var turns = new ArrayList<TutorChatAi.Turn>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) continue;
      String role = text(map.get("role")).toLowerCase(Locale.ROOT);
      if (!"user".equals(role) && !"assistant".equals(role)) continue;
      String content = text(map.get("content"));
      if (content.isBlank()) continue;
      turns.add(new TutorChatAi.Turn(role, cap(content, MAX_TURN_CHARS)));
    }
    if (turns.size() <= MAX_HISTORY_TURNS) return List.copyOf(turns);
    return List.copyOf(turns.subList(turns.size() - MAX_HISTORY_TURNS, turns.size()));
  }

  private String now() {
    return clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().format(OFFSET);
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
