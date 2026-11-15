package io.github.dream07code.innovationspace.service.impl;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.scoring.ScoringProperties;
import io.github.dream07code.innovationspace.scoring.SubmissionReportAi;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.MistakeBookService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 错题本实现。
 *
 * <p>两类记录合并成「每题最近一次尝试」：正式提交（有判题结果、有代码、有 AI 批改报告）
 * 与题库练习（只有判题结论 + V20 起保存的代码）。最近一次已经通过就不再算错题。
 *
 * <p>AI 错因按需生成，不落库：与批改报告一样按提交/练习 id 缓存在进程内，
 * 模型改动后最多等一个 TTL；模型不可用时如实返回 available=false，不编造原因。
 */
@Service
public class MistakeBookServiceImpl implements MistakeBookService {
  private static final Logger log = LoggerFactory.getLogger(MistakeBookServiceImpl.class);
  private static final int MAX_CACHE_ENTRIES = 200;
  private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
  private static final long AI_WAIT_SECONDS = 20;
  private static final int MAX_FAILED_CASES = 3;

  private final JdbcTemplate jdbc;
  private final SubmissionReportAi ai;
  private final ScoringProperties aiProperties;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Clock clock;

  private final Map<String, Cached> cache =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
              return size() > MAX_CACHE_ENTRIES;
            }
          });
  private final Semaphore aiSlots;

  public MistakeBookServiceImpl(
      JdbcTemplate jdbc,
      SubmissionReportAi ai,
      ScoringProperties aiProperties,
      Clock clock) {
    this.jdbc = jdbc;
    this.ai = ai;
    this.aiProperties = aiProperties;
    this.clock = clock;
    this.aiSlots = new Semaphore(Math.max(2, aiProperties.getMaxConcurrency()));
  }

  private record Attempt(
      long problemId,
      String source,
      long refId,
      Long assessmentId,
      String language,
      Instant at,
      String verdict,
      int passed,
      int total,
      String safeError) {}

  private record Cached(SubmissionReportAi.Analysis analysis, long createdAt) {}

  @Override
  public Map<String, Object> list(SessionPrincipal actor) {
    long memberId = requireStudent(actor);
    var rows =
        latestAttempts(memberId).values().stream()
            .filter(attempt -> !"ACCEPTED".equals(attempt.verdict()))
            .toList();

    var problems = problems(rows.stream().map(Attempt::problemId).toList());
    var items = new ArrayList<Map<String, Object>>();
    for (var attempt : rows) {
      var problem = problems.get(attempt.problemId());
      var out = new LinkedHashMap<String, Object>();
      out.put("problemId", String.valueOf(attempt.problemId()));
      out.put("title", problem == null ? "题目 #" + attempt.problemId() : problem.get("title"));
      out.put(
          "difficulty", problem == null ? "MEDIUM" : problem.get("difficulty"));
      out.put("tags", problem == null ? List.of() : tags(problem.get("tags")));
      out.put("source", attempt.source());
      out.put(
          "submissionId",
          "SUBMISSION".equals(attempt.source()) ? String.valueOf(attempt.refId()) : null);
      out.put(
          "assessmentId",
          attempt.assessmentId() == null ? null : String.valueOf(attempt.assessmentId()));
      out.put(
          "attemptId", "PRACTICE".equals(attempt.source()) ? String.valueOf(attempt.refId()) : null);
      out.put("language", attempt.language());
      out.put("verdict", attempt.verdict());
      out.put("passedTestCases", attempt.passed());
      out.put("totalTestCases", attempt.total());
      out.put("safeError", attempt.safeError());
      out.put("lastSubmittedAt", time(attempt.at()));
      items.add(out);
    }
    items.sort(
        (left, right) ->
            String.valueOf(right.get("lastSubmittedAt"))
                .compareTo(String.valueOf(left.get("lastSubmittedAt"))));
    var response = new LinkedHashMap<String, Object>();
    response.put("items", items);
    response.put("total", items.size());
    return response;
  }

  @Override
  public Map<String, Object> cause(long problemId, SessionPrincipal actor) {
    long memberId = requireStudent(actor);
    var attempt = latestWrongAttempt(memberId, problemId);
    var out = new LinkedHashMap<String, Object>();
    if (attempt == null) {
      out.put("available", false);
      out.put("error", "这道题最近一次尝试已经通过，或者还没有做错记录。");
      return out;
    }

    String cacheKey = attempt.source() + ":" + attempt.refId();
    var hit = cache.get(cacheKey);
    if (hit != null && clock.millis() - hit.createdAt() < CACHE_TTL_MS) {
      out.put("available", true);
      out.put("cause", hit.analysis().cause());
      out.put("model", hit.analysis().model());
      out.put("cached", true);
      return out;
    }

    if (!aiProperties.isEnabled() || !"openai-compatible".equals(aiProperties.getProvider())) {
      out.put("available", false);
      out.put("error", "AI 批改未启用，暂时无法生成错因。");
      return out;
    }

    String code = code(attempt);
    if (code == null || code.isBlank()) {
      out.put("available", false);
      out.put("error", "这次尝试没有保存代码（历史记录），无法生成 AI 错因。");
      return out;
    }

    var problem = problem(problemId);
    if (problem == null) throw new ApiException(404, ErrorCode.NOT_FOUND);

    var input =
        new SubmissionReportAi.ReportInput(
            string(problem.get("title")),
            string(problem.get("difficulty")),
            string(problem.get("description")),
            attempt.language(),
            code,
            attempt.verdict(),
            attempt.passed(),
            attempt.total(),
            attempt.safeError(),
            "SUBMISSION".equals(attempt.source()) ? failedCases(attempt.refId()) : List.of());

    boolean acquired;
    try {
      acquired = aiSlots.tryAcquire(AI_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      acquired = false;
    }
    if (!acquired) {
      out.put("available", false);
      out.put("error", "AI 批改繁忙，请稍后重试。");
      return out;
    }
    try {
      var analysis = ai.analyze(input);
      cache.put(cacheKey, new Cached(analysis, clock.millis()));
      out.put("available", true);
      out.put("cause", analysis.cause());
      out.put("model", analysis.model());
      out.put("cached", false);
      return out;
    } catch (SubmissionReportAi.Unavailable error) {
      log.warn("Mistake cause unavailable for {}: {}", cacheKey, error.getMessage());
      out.put("available", false);
      out.put("error", "AI 批改暂不可用，请稍后重试。");
      return out;
    } finally {
      aiSlots.release();
    }
  }

  @Override
  public Map<String, Object> lastCode(long problemId, SessionPrincipal actor) {
    long memberId = requireStudent(actor);
    var attempt = latestWrongAttempt(memberId, problemId);
    var out = new LinkedHashMap<String, Object>();
    if (attempt == null) {
      out.put("available", false);
      return out;
    }
    String code = code(attempt);
    out.put("available", code != null && !code.isBlank());
    out.put("source", attempt.source());
    out.put("language", attempt.language());
    out.put("code", code == null ? "" : code);
    return out;
  }

  // -------------------------------------------------------------------------
  // 查询
  // -------------------------------------------------------------------------

  /** 每题最近一次尝试；正式提交与练习一起比时间，谁新用谁。 */
  private Map<Long, Attempt> latestAttempts(long memberId) {
    var latest = new LinkedHashMap<Long, Attempt>();

    jdbc.query(
        "SELECT s.problem_id,s.id,s.assessment_id,s.language,s.submitted_at,jr.verdict,"
            + " jr.passed_test_cases,jr.total_test_cases,jr.safe_error"
            + " FROM assessment_submission s JOIN oj_judge_result jr ON jr.submission_id=s.id"
            + " WHERE s.member_id=?",
        rs -> {
          long problemId = rs.getLong("problem_id");
          var attempt =
              new Attempt(
                  problemId,
                  "SUBMISSION",
                  rs.getLong("id"),
                  rs.getLong("assessment_id"),
                  rs.getString("language"),
                  instant(rs.getObject("submitted_at")),
                  rs.getString("verdict"),
                  rs.getInt("passed_test_cases"),
                  rs.getInt("total_test_cases"),
                  rs.getString("safe_error"));
          keepLatest(latest, attempt);
        },
        memberId);

    jdbc.query(
        "SELECT problem_id,id,language,created_at,verdict,passed_test_cases,total_test_cases"
            + " FROM problem_practice_attempt WHERE member_id=?",
        rs -> {
          long problemId = rs.getLong("problem_id");
          var attempt =
              new Attempt(
                  problemId,
                  "PRACTICE",
                  rs.getLong("id"),
                  null,
                  rs.getString("language"),
                  instant(rs.getObject("created_at")),
                  rs.getString("verdict"),
                  rs.getInt("passed_test_cases"),
                  rs.getInt("total_test_cases"),
                  null);
          keepLatest(latest, attempt);
        },
        memberId);

    return latest;
  }

  private static void keepLatest(Map<Long, Attempt> latest, Attempt candidate) {
    var current = latest.get(candidate.problemId());
    if (current == null || !candidate.at().isBefore(current.at())) {
      latest.put(candidate.problemId(), candidate);
    }
  }

  private Attempt latestWrongAttempt(long memberId, long problemId) {
    var attempt = latestAttempts(memberId).get(problemId);
    if (attempt == null || "ACCEPTED".equals(attempt.verdict())) return null;
    return attempt;
  }

  private Map<Long, Map<String, Object>> problems(List<Long> ids) {
    var out = new LinkedHashMap<Long, Map<String, Object>>();
    if (ids.isEmpty()) return out;
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    jdbc.query(
        "SELECT id,title,difficulty,tags FROM assessment_problem WHERE id IN (" + placeholders + ")",
        rs -> {
          var row = new LinkedHashMap<String, Object>();
          row.put("title", rs.getString("title"));
          row.put("difficulty", rs.getString("difficulty"));
          row.put("tags", rs.getString("tags"));
          out.put(rs.getLong("id"), row);
        },
        ids.toArray());
    return out;
  }

  private Map<String, Object> problem(long problemId) {
    var rows =
        jdbc.queryForList(
            "SELECT title,difficulty,description FROM assessment_problem WHERE id=?", problemId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private String code(Attempt attempt) {
    if ("SUBMISSION".equals(attempt.source())) {
      var rows =
          jdbc.queryForList(
              "SELECT code_text FROM assessment_submission_code WHERE submission_id=?",
              attempt.refId());
      return rows.isEmpty() ? null : (String) rows.getFirst().get("code_text");
    }
    var rows =
        jdbc.queryForList("SELECT code FROM problem_practice_attempt WHERE id=?", attempt.refId());
    return rows.isEmpty() ? null : (String) rows.getFirst().get("code");
  }

  private List<SubmissionReportAi.FailedCase> failedCases(long submissionId) {
    var out = new ArrayList<SubmissionReportAi.FailedCase>();
    jdbc.query(
        "SELECT position,verdict,input_preview,actual_output,expected_output"
            + " FROM oj_test_case_result WHERE submission_id=? AND verdict<>'ACCEPTED'"
            + " ORDER BY position LIMIT "
            + MAX_FAILED_CASES,
        rs -> {
          out.add(
              new SubmissionReportAi.FailedCase(
                  rs.getInt("position"),
                  rs.getString("verdict"),
                  rs.getString("input_preview"),
                  rs.getString("actual_output"),
                  rs.getString("expected_output")));
        },
        submissionId);
    return out;
  }

  // -------------------------------------------------------------------------
  // 辅助
  // -------------------------------------------------------------------------

  private static long requireStudent(SessionPrincipal actor) {
    if (!"STUDENT".equals(actor.role()) || actor.memberId() == null)
      throw new ApiException(403, ErrorCode.FORBIDDEN);
    return actor.memberId();
  }

  private List<String> tags(Object raw) {
    if (raw == null) return List.of();
    try {
      return json.readValue(String.valueOf(raw), json.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception error) {
      return List.of();
    }
  }

  private static String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /** 库里时间列按 UTC 存，JDBC 取出来可能是 Timestamp 或 LocalDateTime，统一转 Instant。 */
  private static Instant instant(Object value) {
    return value instanceof Timestamp timestamp
        ? timestamp.toInstant()
        : value instanceof LocalDateTime local
            ? local.toInstant(ZoneOffset.UTC)
            : (Instant) value;
  }

  private static String time(Instant value) {
    return value.atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
  }
}
