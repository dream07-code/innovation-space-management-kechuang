package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;
import io.github.dream07code.innovationspace.service.TutorConversationService;
import io.github.dream07code.innovationspace.tutor.TutorConversation;
import io.github.dream07code.innovationspace.tutor.TutorConversationSummary;
import io.github.dream07code.innovationspace.tutor.TutorMessage;
import io.github.dream07code.innovationspace.tutor.TutorRepository;
import io.github.dream07code.innovationspace.tutor.TutorSupport;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 会话管理的实现。
 *
 * <p>权限只有一条规则：会话的 member_id 必须等于当前账号的 member_id，否则 404。
 * 查询时按 id 取回再比对，而不是在 SQL 里带 member_id 过滤后返回 404/403 两种情况——
 * 这样「别人的会话」和「不存在的会话」对外表现完全一致。
 */
@Service
public class TutorConversationServiceImpl implements TutorConversationService {
  private static final int MAX_PAGE_SIZE = 50;
  private static final int MAX_LISTED_MESSAGES = 200;

  private final TutorRepository repository;
  private final AssessmentProblemService problems;
  private final IdempotencyService idem;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final JsonMapper json = JsonMapper.builder().build();

  public TutorConversationServiceImpl(
      TutorRepository repository,
      AssessmentProblemService problems,
      IdempotencyService idem,
      Clock clock,
      PlatformTransactionManager manager) {
    this.repository = repository;
    this.problems = problems;
    this.idem = idem;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  @Override
  public IdempotencyService.StoredResponse create(
      Map<String, Object> request, SessionPrincipal actor, String key) {
    long memberId = requireMember(actor);
    var body = request == null ? Map.<String, Object>of() : request;
    Long problemId = optionalProblem(body.get("problemId"), actor);
    // 关联了题目却没填标题时，用题目标题当会话名，比“新对话”更容易在列表里认出来。
    String problemTitle =
        problemId == null ? null : string(problems.get(problemId, actor).get("title"));
    String title = TutorSupport.cleanTitle(string(body.get("title")));
    if (title == null) title = problemTitle == null || problemTitle.isBlank() ? "新对话" : problemTitle;
    String conversationTitle = title;

    return idem.execute(
        actor,
        "POST",
        "/api/v1/tutor/conversations",
        key,
        json.writeValueAsString(body),
        () ->
            tx.execute(
                status -> {
                  var now = clock.instant();
                  long id = repository.createConversation(memberId, conversationTitle, problemId, now);
                  var created =
                      new TutorConversation(id, memberId, conversationTitle, problemId, now, now);
                  var view =
                      conversationView(
                          new TutorConversationSummary(created, problemTitle, 0));
                  return new IdempotencyService.StoredResponse(
                      201, json.writeValueAsString(ApiResponse.success(view)));
                }));
  }

  @Override
  public Map<String, Object> list(SessionPrincipal actor, int page, int size) {
    long memberId = requireMember(actor);
    int safePage = Math.max(1, page);
    int safeSize = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    var items = new ArrayList<Map<String, Object>>();
    for (var summary : repository.listConversations(memberId, safeSize, (safePage - 1) * safeSize))
      items.add(conversationView(summary));
    var out = new LinkedHashMap<String, Object>();
    out.put("items", items);
    out.put("page", safePage);
    out.put("size", safeSize);
    out.put("total", repository.countConversations(memberId));
    return out;
  }

  @Override
  public Map<String, Object> messages(long conversationId, SessionPrincipal actor) {
    var summary = requireOwned(conversationId, actor);
    long total = repository.countMessages(conversationId);
    // 只读最近 200 条，超出时在响应里明确标记截断，前端提示"仅显示最近消息"。
    var rows = repository.recentMessages(conversationId, MAX_LISTED_MESSAGES);
    var items = new ArrayList<Map<String, Object>>(rows.size());
    for (var message : rows) items.add(messageView(message));
    var out = new LinkedHashMap<String, Object>();
    out.put("conversation", conversationView(summary));
    out.put("items", items);
    out.put("total", total);
    out.put("truncated", total > rows.size());
    return out;
  }

  @Override
  public Map<String, Object> rename(
      long conversationId, Map<String, Object> request, SessionPrincipal actor) {
    requireOwned(conversationId, actor);
    String title = TutorSupport.cleanTitle(request == null ? null : string(request.get("title")));
    if (title == null) throw new ApiException(400, ErrorCode.INVALID_REQUEST, "title is required");
    repository.renameConversation(conversationId, title, clock.instant());
    return conversationView(requireOwned(conversationId, actor));
  }

  @Override
  public IdempotencyService.StoredResponse delete(
      long conversationId, SessionPrincipal actor, String key) {
    requireMember(actor);
    return idem.execute(
        actor,
        "POST",
        "/api/v1/tutor/conversations/" + conversationId + "/delete",
        key,
        "{}",
        () ->
            tx.execute(
                status -> {
                  var summary = requireOwned(conversationId, actor);
                  repository.deleteConversation(conversationId);
                  var out = new LinkedHashMap<String, Object>();
                  out.put("deleted", true);
                  out.put("conversationId", String.valueOf(conversationId));
                  out.put("title", summary.conversation().title());
                  return new IdempotencyService.StoredResponse(
                      200, json.writeValueAsString(ApiResponse.success(out)));
                }));
  }

  private TutorConversationSummary requireOwned(long conversationId, SessionPrincipal actor) {
    long memberId = requireMember(actor);
    var summary =
        repository
            .findConversation(conversationId)
            .orElseThrow(() -> new ApiException(404, ErrorCode.NOT_FOUND));
    if (summary.conversation().memberId() != memberId)
      throw new ApiException(404, ErrorCode.NOT_FOUND);
    return summary;
  }

  /** 会话挂在学生档案上：没有 member_id 的账号（学院管理员）不能拥有会话。 */
  private static long requireMember(SessionPrincipal actor) {
    if (actor == null || actor.memberId() == null) throw new ApiException(403, ErrorCode.FORBIDDEN);
    return actor.memberId();
  }

  /** problemId 可空；给了就必须可见，否则按题库口径 404。 */
  private Long optionalProblem(Object raw, SessionPrincipal actor) {
    String value = string(raw);
    if (value.isBlank()) return null;
    if (!TutorSupport.isId(value))
      throw new ApiException(400, ErrorCode.INVALID_REQUEST, "problemId is invalid");
    long problemId = Long.parseLong(value);
    problems.get(problemId, actor);
    return problemId;
  }

  private Map<String, Object> conversationView(TutorConversationSummary summary) {    var conversation = summary.conversation();
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(conversation.id()));
    out.put("title", conversation.title());
    out.put("problemId", conversation.problemId() == null ? null : String.valueOf(conversation.problemId()));
    out.put("problemTitle", summary.problemTitle());
    out.put("messageCount", summary.messageCount());
    out.put("createdAt", TutorSupport.time(conversation.createdAt()));
    out.put("updatedAt", TutorSupport.time(conversation.updatedAt()));
    return out;
  }

  private Map<String, Object> messageView(TutorMessage message) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(message.id()));
    out.put("role", message.role());
    out.put("content", message.content());
    out.put("createdAt", TutorSupport.time(message.createdAt()));
    return out;
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }
}
