package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.QuizQuestionService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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

/**
 * 客观题库服务。
 *
 * <p>保密口径：客观题是考试材料，学生端没有任何入口——本服务所有操作对 STUDENT
 * 一律按不存在处理（404）。题库全院共享，负责人与管理员均可维护。
 *
 * <p>校验矩阵与判分契约对齐（后端 QuizGrader / 前端 quizImport）：
 * 单选=一个界内字母；多选=1–6 个去重字母（落库升序）；判断=布尔且无选项；
 * 填空=1–10 个非空备选答案。
 */
@Service
public class QuizQuestionServiceImpl implements QuizQuestionService {
  private static final Set<String> TYPES =
      Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE", "FILL_BLANK");

  private final JdbcTemplate jdbc;
  private final IdempotencyService idem;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final JsonMapper json = JsonMapper.builder().build();

  public QuizQuestionServiceImpl(
      JdbcTemplate jdbc, IdempotencyService idem, Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.idem = idem;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  @Override
  public IdempotencyService.StoredResponse create(
      Map<String, Object> request, SessionPrincipal actor, String key) {
    requireStaff(actor);
    Validated validated = validate(request, null);
    return idem.execute(
        actor,
        "POST",
        "/api/v1/quiz-questions",
        key,
        json.writeValueAsString(request),
        () ->
            tx.execute(
                status -> {
                  Instant now = clock.instant();
                  jdbc.update(
                      "INSERT INTO quiz_question(type,stem,options,answer,analysis,tags,status,"
                          + "created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,'DRAFT',?,?,?)",
                      validated.type,
                      validated.stem,
                      validated.optionsJson,
                      validated.answerJson,
                      validated.analysisJson,
                      validated.tagsJson,
                      actor.userId(),
                      Timestamp.from(now),
                      Timestamp.from(now));
                  long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                  audit(actor, "QUIZ_QUESTION_CREATED", id, "Created quiz question");
                  return new IdempotencyService.StoredResponse(
                      201, json.writeValueAsString(ApiResponse.success(response(id))));
                }));
  }

  @Override
  public Map<String, Object> get(long id, SessionPrincipal actor) {
    requireStaff(actor);
    return response(requireRow(id));
  }

  @Override
  public Map<String, Object> list(
      SessionPrincipal actor, int page, int size, String type, String status, String keyword) {
    requireStaff(actor);
    if (page < 1 || size < 1 || size > 200) throw bad();
    if (type != null && !TYPES.contains(type)) throw bad();
    if (status != null && !Set.of("DRAFT", "PUBLISHED", "RETIRED").contains(status)) throw bad();

    var args = new ArrayList<Object>();
    var where = new StringBuilder(" WHERE 1=1");
    if (type != null) {
      where.append(" AND type=?");
      args.add(type);
    }
    if (status != null) {
      where.append(" AND status=?");
      args.add(status);
    } else {
      where.append(" AND status<>'RETIRED'");
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" AND stem LIKE ?");
      args.add("%" + keyword.trim() + "%");
    }
    Long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM quiz_question" + where, Long.class, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    List<Long> ids =
        jdbc.queryForList(
            "SELECT id FROM quiz_question" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            Long.class,
            args.toArray());
    var items = new ArrayList<Map<String, Object>>();
    for (long id : ids) items.add(response(id));
    var out = new LinkedHashMap<String, Object>();
    out.put("items", items);
    out.put("page", page);
    out.put("size", size);
    out.put("total", total == null ? 0 : total);
    return out;
  }

  @Override
  public Map<String, Object> update(long id, Map<String, Object> request, SessionPrincipal actor) {
    requireStaff(actor);
    var row = requireRow(id);
    if (!"DRAFT".equals(row.get("status"))) throw new ApiException(409, ErrorCode.INVALID_STATE);
    if (request.containsKey("type") && !request.get("type").equals(row.get("type"))) throw bad();
    Validated validated = validate(request, row);
    jdbc.update(
        "UPDATE quiz_question SET stem=?,options=?,answer=?,analysis=?,tags=?,updated_at=? WHERE id=?",
        validated.stem,
        validated.optionsJson,
        validated.answerJson,
        validated.analysisJson,
        validated.tagsJson,
        Timestamp.from(clock.instant()),
        id);
    audit(actor, "QUIZ_QUESTION_UPDATED", id, "Updated quiz question");
    return response(id);
  }

  @Override
  public IdempotencyService.StoredResponse retire(
      long id, Map<String, Object> request, SessionPrincipal actor, String key) {
    requireStaff(actor);
    return idem.execute(
        actor,
        "POST",
        "/api/v1/quiz-questions/" + id + "/retire",
        key,
        json.writeValueAsString(request),
        () -> {
          var row = requireRow(id);
          if (!"RETIRED".equals(row.get("status"))) {
            jdbc.update(
                "UPDATE quiz_question SET status='RETIRED',updated_at=? WHERE id=?",
                Timestamp.from(clock.instant()),
                id);
            audit(actor, "QUIZ_QUESTION_RETIRED", id, "Retired quiz question");
          }
          return new IdempotencyService.StoredResponse(
              201, json.writeValueAsString(ApiResponse.success(response(id))));
        });
  }

  /** 客观题对学生保密：按不存在处理，而不是显式拒绝。 */
  private void requireStaff(SessionPrincipal actor) {
    if ("STUDENT".equals(actor.role())) throw new ApiException(404, ErrorCode.NOT_FOUND);
  }

  private Map<String, Object> requireRow(long id) {
    var rows = jdbc.queryForList("SELECT * FROM quiz_question WHERE id=?", id);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  // -- 校验 ------------------------------------------------------------

  private record Validated(
      String type, String stem, String optionsJson, String answerJson, String analysisJson,
      String tagsJson) {}

  @SuppressWarnings("unchecked")
  private Validated validate(Map<String, Object> request, Map<String, Object> current) {
    Object typeValue = current != null ? current.get("type") : request.get("type");
    if (!(typeValue instanceof String type) || !TYPES.contains(type)) throw bad();
    String stem = optionalText(request, current, "stem", 2000, type);
    if (stem == null || stem.isBlank()) throw bad();

    String optionsJson = null;
    Object answerNormalized = null;
    switch (type) {
      case "SINGLE_CHOICE", "MULTI_CHOICE" -> {
        List<String> options = options(request, current);
        if (options.size() < 2 || options.size() > 6) throw bad();
        optionsJson = json.writeValueAsString(options);
        Object answer = value(request, current, "answer");
        if (type.equals("SINGLE_CHOICE")) {
          if (!(answer instanceof String letter)
              || indexOfLetter(letter, options) < 0) throw bad();
          answerNormalized = letter;
        } else {
          if (!(answer instanceof List<?> letters) || letters.isEmpty() || letters.size() > 6)
            throw bad();
          var normalized = new ArrayList<String>();
          for (Object item : letters) {
            if (!(item instanceof String letter) || indexOfLetter(letter, options) < 0) throw bad();
            if (normalized.contains(letter)) throw bad();
            normalized.add(letter);
          }
          normalized.sort(String::compareTo);
          answerNormalized = normalized;
        }
      }
      case "TRUE_FALSE" -> {
        if (optionsPresent(request, current)) throw bad();
        Object answer = value(request, current, "answer");
        if (!(answer instanceof Boolean bool)) throw bad();
        answerNormalized = bool;
      }
      case "FILL_BLANK" -> {
        if (optionsPresent(request, current)) throw bad();
        Object answer = value(request, current, "answer");
        if (!(answer instanceof List<?> alternatives) || alternatives.isEmpty() || alternatives.size() > 10)
          throw bad();
        var normalized = new ArrayList<String>();
        for (Object item : alternatives) {
          if (!(item instanceof String text)) throw bad();
          String trimmed = text.trim();
          if (trimmed.isEmpty() || trimmed.length() > 500) throw bad();
          normalized.add(trimmed);
        }
        answerNormalized = normalized;
      }
      default -> throw bad();
    }

    String analysis = optionalText(request, current, "analysis", 1000, type);
    List<String> tags = tags(request, current);
    return new Validated(
        type,
        stem.trim(),
        optionsJson,
        json.writeValueAsString(answerNormalized),
        analysis == null || analysis.isBlank() ? null : analysis.trim(),
        json.writeValueAsString(tags));
  }

  private static int indexOfLetter(String letter, List<String> options) {
    if (letter == null || letter.length() != 1) return -1;
    int index = letter.charAt(0) - 'A';
    return index >= 0 && index < options.size() ? index : -1;
  }

  @SuppressWarnings("unchecked")
  private List<String> options(Map<String, Object> request, Map<String, Object> current) {
    Object value = request.containsKey("options") ? request.get("options") : decode(current == null ? null : String.valueOf(current.get("options")));
    if (!(value instanceof List<?> raw)) throw bad();
    var out = new ArrayList<String>();
    for (Object item : raw) {
      if (!(item instanceof String text)) throw bad();
      String trimmed = text.trim();
      if (trimmed.isEmpty() || trimmed.length() > 500) throw bad();
      out.add(trimmed);
    }
    return out;
  }

  private boolean optionsPresent(Map<String, Object> request, Map<String, Object> current) {
    if (request.containsKey("options")) return request.get("options") != null;
    return current != null && current.get("options") != null;
  }

  private Object value(Map<String, Object> request, Map<String, Object> current, String field) {
    if (request.containsKey(field)) return request.get(field);
    return current == null ? null : decode(String.valueOf(current.get(field)));
  }

  private Object decode(String raw) {
    if (raw == null || "null".equals(raw)) return null;
    return json.readValue(raw, Object.class);
  }

  private String optionalText(
      Map<String, Object> request,
      Map<String, Object> current,
      String field,
      int max,
      String type) {
    Object value;
    if (request.containsKey(field)) value = request.get(field);
    else value = current == null ? null : current.get(field);
    if (value == null) return null;
    if (!(value instanceof String text) || text.length() > max) throw bad();
    return text;
  }

  @SuppressWarnings("unchecked")
  private List<String> tags(Map<String, Object> request, Map<String, Object> current) {
    Object value = request.containsKey("tags") ? request.get("tags") : decode(current == null ? "[]" : String.valueOf(current.get("tags")));
    if (value == null) value = List.of();
    if (!(value instanceof List<?> raw) || raw.size() > 10) throw bad();
    var out = new ArrayList<String>();
    for (Object item : raw) {
      if (!(item instanceof String text) || text.isBlank() || text.length() > 30) throw bad();
      out.add(text.trim());
    }
    return out;
  }

  // -- 装配 ------------------------------------------------------------

  private Map<String, Object> response(long id) {
    return response(requireRow(id));
  }

  private Map<String, Object> response(Map<String, Object> row) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(row.get("id")));
    out.put("type", row.get("type"));
    out.put("stem", row.get("stem"));
    out.put("options", decode(String.valueOf(row.get("options"))));
    out.put("answer", decode(String.valueOf(row.get("answer"))));
    out.put("analysis", row.get("analysis"));
    out.put("tags", decode(String.valueOf(row.get("tags"))));
    out.put("status", row.get("status"));
    out.put("createdAt", String.valueOf(row.get("created_at")));
    out.put("updatedAt", String.valueOf(row.get("updated_at")));
    return out;
  }

  private void audit(SessionPrincipal actor, String action, long id, String summary) {
    jdbc.update(
        "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,summary,result,occurred_at)"
            + " VALUES(?,?,'QUIZ_QUESTION',?,?,'SUCCESS',?)",
        actor.userId(), action, id, summary, Timestamp.from(clock.instant()));
  }

  private static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }
}
