package io.github.dream07code.innovationspace.service.impl;
import io.github.dream07code.innovationspace.service.AssessmentEvaluationService;
import io.github.dream07code.innovationspace.service.AssessmentReviewService;
import io.github.dream07code.innovationspace.assessment.*;


import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AssessmentReviewServiceImpl implements AssessmentReviewService {
  private final JdbcTemplate jdbc;
  private final IdempotencyService idem;
  private final AssessmentEvaluationService evaluations;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final JsonMapper json = JsonMapper.builder().build();

  public AssessmentReviewServiceImpl(
      JdbcTemplate jdbc,
      IdempotencyService idem,
      AssessmentEvaluationService evaluations,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.idem = idem;
    this.evaluations = evaluations;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  public IdempotencyService.StoredResponse create(
      long submissionId, Map<String, Object> request, SessionPrincipal actor, String key) {
    requireReviewer(actor);
    validate(request);
    long submissionVersion = integer(request.get("submissionVersion"));
    long reviewVersion = integer(request.get("reviewVersion"));
    int score = Math.toIntExact(integer(request.get("score")));
    String comment = (String) request.get("comment");
    requireVisible(submissionId, actor);
    var result =
        idem.execute(
            actor,
            "POST",
            "/api/v1/assessment-submissions/" + submissionId + "/reviews",
            key,
            json.writeValueAsString(request),
            () ->
                tx.execute(
                    status -> {
                      var submissions =
                          jdbc.queryForList(
                              "SELECT s.version,s.member_id FROM assessment_submission s WHERE s.id=? FOR UPDATE",
                              submissionId);
                      if (submissions.isEmpty()) throw notFound();
                      long currentVersion =
                          ((Number) submissions.getFirst().get("version")).longValue();
                      if (currentVersion != submissionVersion) throw versionConflict();
                      int next =
                          jdbc.queryForObject(
                              "SELECT COALESCE(MAX(version),0)+1 FROM assessment_review WHERE submission_id=?",
                              Integer.class,
                              submissionId);
                      if (next != reviewVersion) throw versionConflict();
                      jdbc.update(
                          "UPDATE assessment_review SET status='SUPERSEDED',updated_at=? WHERE submission_id=? AND status='ACTIVE'",
                          Timestamp.from(clock.instant()),
                          submissionId);
                      jdbc.update(
                          "INSERT INTO assessment_review(submission_id,member_id,reviewer_id,score,comment,status,version,created_at,updated_at)"
                              + " VALUES(?,?,?,?,?,'ACTIVE',?,?,?)"
                              + " ON DUPLICATE KEY UPDATE score=VALUES(score),comment=VALUES(comment),"
                              + " status='ACTIVE',updated_at=VALUES(updated_at)",
                          submissionId,
                          submissions.getFirst().get("member_id"),
                          actor.userId(),
                          score,
                          comment,
                          reviewVersion,
                          Timestamp.from(clock.instant()),
                          Timestamp.from(clock.instant()));
                      jdbc.update(
                          "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,detail,summary,result,occurred_at)"
                              + " VALUES(?,'ASSESSMENT_REVIEWED','SUBMISSION',?,?,'Assessment reviewed','SUCCESS',?)",
                          actor.userId(),
                          submissionId,
                          "{\"reviewVersion\":" + reviewVersion + ",\"score\":" + score + "}",
                          Timestamp.from(clock.instant()));
                      return new IdempotencyService.StoredResponse(
                          201,
                          json.writeValueAsString(
                              ApiResponse.success(response(submissionId, reviewVersion))));
                    }));
    evaluations.refreshResult(submissionId);
    return result;
  }

  public Map<String, Object> list(long submissionId, SessionPrincipal actor, int page, int size) {
    requireReviewer(actor);
    requireVisible(submissionId, actor);
    if (page < 1 || size < 1 || size > 200) throw bad();
    var ids =
        jdbc.queryForList(
            "SELECT version FROM assessment_review WHERE submission_id=? ORDER BY version DESC LIMIT ? OFFSET ?",
            Long.class,
            submissionId,
            size,
            (page - 1) * size);
    var items = new ArrayList<Map<String, Object>>();
    for (Long version : ids) items.add(response(submissionId, version));
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM assessment_review WHERE submission_id=?", Long.class, submissionId);
    return Map.of("items", items, "page", page, "size", size, "total", total);
  }

  private Map<String, Object> response(long submissionId, long version) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM assessment_review WHERE submission_id=? AND version=?",
            submissionId,
            version);
    if (rows.isEmpty()) throw notFound();
    var row = rows.getFirst();
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(row.get("id")));
    out.put("submissionId", String.valueOf(row.get("submission_id")));
    out.put("memberId", String.valueOf(row.get("member_id")));
    out.put("reviewerId", String.valueOf(row.get("reviewer_id")));
    out.put("score", row.get("score"));
    out.put("comment", row.get("comment"));
    out.put("status", row.get("status"));
    out.put("version", row.get("version"));
    out.put("createdAt", time(row.get("created_at")));
    out.put("updatedAt", time(row.get("updated_at")));
    return out;
  }

  private void requireVisible(long submissionId, SessionPrincipal actor) {
    var rows =
        jdbc.queryForList(
            "SELECT a.direction_id FROM assessment_submission s JOIN assessment a ON a.id=s.assessment_id"
                + " WHERE s.id=?",
            submissionId);
    if (rows.isEmpty()) throw notFound();
    long directionId = ((Number) rows.getFirst().get("direction_id")).longValue();
    if ("COLLEGE_ADMIN".equals(actor.role())) return;
    if ("DIRECTION_LEADER".equals(actor.role()) && actor.managedDirectionIds().contains(directionId)) return;
    throw notFound();
  }

  private static void requireReviewer(SessionPrincipal actor) {
    if (!"COLLEGE_ADMIN".equals(actor.role()) && !"DIRECTION_LEADER".equals(actor.role()))
      throw new ApiException(403, ErrorCode.FORBIDDEN);
  }

  private static void validate(Map<String, Object> request) {
    if (request == null
        || !Set.of("submissionVersion", "reviewVersion", "score", "comment").equals(request.keySet()))
      throw bad();
    long submissionVersion = integer(request.get("submissionVersion"));
    long reviewVersion = integer(request.get("reviewVersion"));
    long score = integer(request.get("score"));
    if (submissionVersion < 1 || reviewVersion < 1 || reviewVersion > 1_000_000 || score < 0 || score > 100)
      throw bad();
    if (!(request.get("comment") instanceof String comment)
        || comment.isBlank()
        || comment.length() > 2000) throw bad();
  }

  private static long integer(Object value) {
    if (!(value instanceof Number number) || number.longValue() != number.intValue() || number.longValue() < 0)
      throw bad();
    return number.longValue();
  }

  private static String time(Object value) {
    Instant instant =
        value instanceof Timestamp timestamp
            ? timestamp.toInstant()
            : value instanceof LocalDateTime local
                ? local.toInstant(ZoneOffset.UTC)
                : (Instant) value;
    return instant.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
  }

  private static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }

  private static ApiException notFound() {
    return new ApiException(404, ErrorCode.NOT_FOUND);
  }

  private static ApiException versionConflict() {
    return new ApiException(409, ErrorCode.VERSION_CONFLICT);
  }
}
