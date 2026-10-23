package io.github.dream07code.innovationspace.service.impl;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;
import io.github.dream07code.innovationspace.assessment.*;
import io.github.dream07code.innovationspace.assessment.service.AssessmentJudgeWorker;


import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AssessmentProblemServiceImpl implements AssessmentProblemService {
  private final JdbcTemplate jdbc;
  private final IdempotencyService idem;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final AssessmentStatePolicy states = new AssessmentStatePolicy();
  private static final int IMPORT_MAX_BYTES = 5 * 1024 * 1024;
  private static final Set<String> LANGUAGES = Set.of("java", "python", "cpp", "javascript", "go", "rust");
  private final AssessmentJudgeWorker judgeWorker;
  private final JsonMapper json = JsonMapper.builder().build();

  public AssessmentProblemServiceImpl(
      JdbcTemplate jdbc,
      IdempotencyService idem,
      Clock clock,
      PlatformTransactionManager manager,
      AssessmentJudgeWorker judgeWorker) {
    this.jdbc = jdbc;
    this.idem = idem;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
    this.judgeWorker = judgeWorker;
  }

  public IdempotencyService.StoredResponse create(
      Map<String, Object> request, SessionPrincipal actor, String key) {
    requireManage(actor);
    validate(request);
    validateTestCaseField(request.get("assessmentTestCases"), true);
    validateTestCaseField(request.get("practiceTestCases"), false);
    return idem.execute(
        actor,
        "POST",
        "/api/v1/assessment-problems",
        key,
        json.writeValueAsString(request),
        () ->
            tx.execute(
                status -> {
                  long id = insertProblem(request, actor);
                  saveTestCases(
                      id,
                      "assessment_problem_assessment_test_case",
                      request.get("assessmentTestCases"));
                  saveTestCases(
                      id,
                      "assessment_problem_practice_test_case",
                      request.get("practiceTestCases"));
                  audit(actor, "ASSESSMENT_PROBLEM_CREATED", id);
                  return new IdempotencyService.StoredResponse(
                      201, json.writeValueAsString(ApiResponse.success(response(id))));
                }));
  }

  public IdempotencyService.StoredResponse importProblems(
      Map<String, Object> request, SessionPrincipal actor, String key) {
    requireManage(actor);
    String requestJson = json.writeValueAsString(request);
    return idem.execute(
        actor,
        "POST",
        "/api/v1/assessment-problems/import",
        key,
        requestJson,
        () -> {
          var errors = validateImportRequest(request, requestJson);
          if (!errors.isEmpty()) return importFailure(errors);
          @SuppressWarnings("unchecked")
          var problems = (List<Object>) request.get("problems");
          return tx.execute(
              status -> {
                var ids = new ArrayList<String>();
                for (Object item : problems) {
                  @SuppressWarnings("unchecked")
                  var problem = (Map<String, Object>) item;
                  long id = insertProblem(problem, actor);
                  saveTestCases(
                      id,
                      "assessment_problem_assessment_test_case",
                      problem.get("assessmentTestCases"));
                  saveTestCases(
                      id,
                      "assessment_problem_practice_test_case",
                      problem.get("practiceTestCases"));
                  audit(actor, "ASSESSMENT_PROBLEM_IMPORTED", id);
                  ids.add(String.valueOf(id));
                }
                return new IdempotencyService.StoredResponse(
                    201,
                    json.writeValueAsString(
                        ApiResponse.success(importResult(ids, List.of()))));
              });
        });
  }

  public Map<String, Object> get(long id, SessionPrincipal actor) {
    var row = row(id);
    requireVisible(actor, row);
    return response(id);
  }

  public Map<String, Object> testCases(long id, SessionPrincipal actor) {
    requireManage(actor);
    row(id);
    return Map.of(
        "assessmentTestCases",
        readTestCases(id, "assessment_problem_assessment_test_case"),
        "practiceTestCases",
        readTestCases(id, "assessment_problem_practice_test_case"));
  }

  public Map<String, Object> practiceTestCases(long id, SessionPrincipal actor) {
    requireStudent(actor);
    var row = row(id);
    requirePublished(row);
    return Map.of("practiceTestCases", readTestCases(id, "assessment_problem_practice_test_case"));
  }

  public Map<String, Object> practiceRun(
      long id, Map<String, Object> request, SessionPrincipal actor) {
    requireStudent(actor);
    var row = row(id);
    requirePublished(row);
    if (request == null || request.size() != 2 || !request.containsKey("language") || !request.containsKey("code"))
      throw bad();
    if (!(request.get("language") instanceof String language) || !LANGUAGES.contains(language))
      throw bad();
    if (!(request.get("code") instanceof String code) || code.isBlank() || code.length() > 200000)
      throw bad();
    var result = judgeWorker.practice(id, language, code);
    var cases = readTestCases(id, "assessment_problem_practice_test_case");
    var details = new ArrayList<Map<String, Object>>();
    for (var testCaseResult : result.testCaseResults()) {
      int index = testCaseResult.position() - 1;
      if (index < 0 || index >= cases.size()) continue;
      var testCase = cases.get(index);
      var detail = new LinkedHashMap<String, Object>();
      detail.put("position", testCaseResult.position());
      detail.put("input", testCase.get("input"));
      detail.put("expectedOutput", testCase.get("expectedOutput"));
      detail.put("actualOutput", testCaseResult.actualOutput());
      detail.put("verdict", testCaseResult.verdict().name());
      detail.put("timeMs", testCaseResult.timeMs());
      detail.put("memoryKb", testCaseResult.memoryKb());
      detail.put("safeError", testCaseResult.safeError());
      details.add(detail);
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("verdict", result.verdict().name());
    out.put("passedTestCases", result.passedTestCases());
    out.put("totalTestCases", result.totalTestCases());
    out.put("timeMs", result.timeMs());
    out.put("memoryKb", result.memoryKb());
    out.put("safeError", result.safeError());
    out.put("testCaseResults", details);
    return out;
  }

  public Map<String, Object> list(SessionPrincipal actor, int page, int size) {
    if (page < 1 || size < 1 || size > 200) throw bad();
    if ("STUDENT".equals(actor.role())) {
      String where = " WHERE status='PUBLISHED'";
      var ids =
          jdbc.queryForList(
              "SELECT id FROM assessment_problem" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
              Long.class,
              size,
              (page - 1) * size);
      var items = new ArrayList<Map<String, Object>>();
      for (Long id : ids) items.add(response(id));
      Long total = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_problem" + where, Long.class);
      return Map.of("items", items, "page", page, "size", size, "total", total);
    }
    requireManage(actor);
    var ids =
        jdbc.queryForList(
            "SELECT id FROM assessment_problem ORDER BY id DESC LIMIT ? OFFSET ?",
            Long.class,
            size,
            (page - 1) * size);
    var items = new ArrayList<Map<String, Object>>();
    for (Long id : ids) items.add(response(id));
    Long total = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_problem", Long.class);
    return Map.of("items", items, "page", page, "size", size, "total", total);
  }

  public Map<String, Object> update(long id, Map<String, Object> request, SessionPrincipal actor) {
    validateUpdate(request);
    if (request.containsKey("assessmentTestCases"))
      validateTestCaseField(request.get("assessmentTestCases"), true);
    if (request.containsKey("practiceTestCases"))
      validateTestCaseField(request.get("practiceTestCases"), false);
    requireManage(actor);
    long version = version(request.get("version"));
    return tx.execute(
        status -> {
          var before = locked(id);
          if (!"DRAFT".equals(before.get("status"))) throw new ApiException(409, ErrorCode.INVALID_STATE);
          if (version != ((Number) before.get("version")).longValue())
            throw new ApiException(409, ErrorCode.VERSION_CONFLICT);
          Map<String, Object> values = new LinkedHashMap<>(request);
          values.remove("version");
          if (values.isEmpty()) throw bad();
          String effectiveMode =
              values.containsKey("judgeMode")
                  ? judgeMode(values.get("judgeMode"))
                  : String.valueOf(before.get("judge_mode"));
          if ("FUNCTION".equals(effectiveMode)) {
            var effective = new LinkedHashMap<String, Object>();
            effective.put("functionName", values.getOrDefault("functionName", before.get("function_name")));
            effective.put(
                "parameterTypes",
                values.containsKey("parameterTypes")
                    ? values.get("parameterTypes")
                    : jsonList(before.get("parameter_types")));
            effective.put("returnType", values.getOrDefault("returnType", before.get("return_type")));
            var signature = signatureFor(effectiveMode, effective);
            if (values.containsKey("assessmentTestCases"))
              validateFunctionTestCases(signature, values.get("assessmentTestCases"), true);
            else if (values.containsKey("judgeMode"))
              throw bad(); // 切换为函数模式时必须同时提交测试点
            if (values.containsKey("practiceTestCases"))
              validateFunctionTestCases(signature, values.get("practiceTestCases"), false);
          }
          var fields = new ArrayList<String>();
          var args = new ArrayList<Object>();
          if (values.containsKey("title")) { fields.add("title=?"); args.add(text(values, "title", 120)); }
          if (values.containsKey("summary")) { fields.add("summary=?"); args.add(text(values, "summary", 300)); }
          if (values.containsKey("description")) { fields.add("description=?"); args.add(text(values, "description", 20000)); }
          if (values.containsKey("inputFormat")) { fields.add("input_format=?"); args.add(text(values, "inputFormat", 5000)); }
          if (values.containsKey("outputFormat")) { fields.add("output_format=?"); args.add(text(values, "outputFormat", 5000)); }
          if (values.containsKey("examples")) { fields.add("examples=?"); args.add(json.writeValueAsString(examples(values.get("examples")))); }
          if (values.containsKey("difficulty")) { fields.add("difficulty=?"); args.add(difficulty(values.get("difficulty"))); }
          if (values.containsKey("tags")) { fields.add("tags=?"); args.add(json.writeValueAsString(tags(values.get("tags")))); }
          if (values.containsKey("timeLimitMs")) { fields.add("time_limit_ms=?"); args.add(integer(values.get("timeLimitMs"), 100, 30000)); }
          if (values.containsKey("memoryLimitKb")) { fields.add("memory_limit_kb=?"); args.add(integer(values.get("memoryLimitKb"), 1024, 1048576)); }
          if (values.containsKey("allowedLanguages")) { fields.add("allowed_languages=?"); args.add(json.writeValueAsString(languages(values.get("allowedLanguages")))); }
          if (values.containsKey("judgeMode")) {
            String mode = judgeMode(values.get("judgeMode"));
            fields.add("judge_mode=?");
            args.add(mode);
            fields.add("function_name=?");
            args.add(functionNameOrNull(mode, values.get("functionName")));
            fields.add("parameter_types=?");
            args.add(parameterTypesJson(mode, values.get("parameterTypes")));
            fields.add("return_type=?");
            args.add(returnTypeOrNull(mode, values.get("returnType")));
          } else {
            if (values.containsKey("functionName")) { fields.add("function_name=?"); args.add(functionName(values.get("functionName"))); }
            if (values.containsKey("parameterTypes")) { fields.add("parameter_types=?"); args.add(json.writeValueAsString(parameterTypeValues(values.get("parameterTypes")))); }
            if (values.containsKey("returnType")) { fields.add("return_type=?"); args.add(returnTypeValue(values.get("returnType"))); }
          }
          fields.add("version=version+1");
          fields.add("updated_at=?");
          args.add(Timestamp.from(clock.instant()));
          args.add(id);
          jdbc.update("UPDATE assessment_problem SET " + String.join(",", fields) + " WHERE id=?", args.toArray());
          if (values.containsKey("assessmentTestCases")) {
            jdbc.update("DELETE FROM assessment_problem_assessment_test_case WHERE problem_id=?", id);
            saveTestCases(id, "assessment_problem_assessment_test_case", values.get("assessmentTestCases"));
          }
          if (values.containsKey("practiceTestCases")) {
            jdbc.update("DELETE FROM assessment_problem_practice_test_case WHERE problem_id=?", id);
            saveTestCases(id, "assessment_problem_practice_test_case", values.get("practiceTestCases"));
          }
          audit(actor, "ASSESSMENT_PROBLEM_UPDATED", id);
          return response(id);
        });
  }

  public IdempotencyService.StoredResponse retire(
      long id, Map<String, Object> request, SessionPrincipal actor, String key) {
    if (request == null || request.size() != 1 || !request.containsKey("version")) throw bad();
    long version = version(request.get("version"));
    return idem.execute(
        actor,
        "POST",
        "/api/v1/assessment-problems/" + id + "/retire",
        key,
        json.writeValueAsString(request),
        () ->
            tx.execute(
                status -> {
                  var before = locked(id);
                  requireManage(actor);
                  ProblemStatus current = ProblemStatus.valueOf((String) before.get("status"));
                  if (!states.canTransition(current, ProblemStatus.RETIRED))
                    throw new ApiException(409, ErrorCode.INVALID_STATE);
                  if (version != ((Number) before.get("version")).longValue())
                    throw new ApiException(409, ErrorCode.VERSION_CONFLICT);
                  jdbc.update(
                      "UPDATE assessment_problem SET status='RETIRED',version=version+1,updated_at=? WHERE id=?",
                      Timestamp.from(clock.instant()),
                      id);
                  audit(actor, "ASSESSMENT_PROBLEM_RETIRED", id);
                  return new IdempotencyService.StoredResponse(
                      200, json.writeValueAsString(ApiResponse.success(response(id))));
                }));
  }

  private void requireStudent(SessionPrincipal actor) {
    if (!"STUDENT".equals(actor.role()) || actor.memberId() == null)
      throw new ApiException(403, ErrorCode.FORBIDDEN);
  }

  private static void requirePublished(Map<String, Object> row) {
    if (!"PUBLISHED".equals(row.get("status"))) throw new ApiException(404, ErrorCode.NOT_FOUND);
  }

  private void requireManage(SessionPrincipal actor) {
    if (!"COLLEGE_ADMIN".equals(actor.role()) && !"DIRECTION_LEADER".equals(actor.role()))
      throw new ApiException(403, ErrorCode.FORBIDDEN);
  }

  private void requireVisible(SessionPrincipal actor, Map<String, Object> row) {
    if ("STUDENT".equals(actor.role()) && !"PUBLISHED".equals(row.get("status")))
      throw new ApiException(404, ErrorCode.NOT_FOUND);
  }

  private Map<String, Object> row(long id) {
    var rows = jdbc.queryForList("SELECT * FROM assessment_problem WHERE id=?", id);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  private Map<String, Object> locked(long id) {
    var rows = jdbc.queryForList("SELECT * FROM assessment_problem WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new ApiException(404, ErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  private Map<String, Object> response(long id) {
    var row = row(id);
    var out = new LinkedHashMap<String, Object>();
    out.put("id", String.valueOf(row.get("id")));
    out.put("title", row.get("title"));
    out.put("summary", row.get("summary"));
    out.put("description", row.get("description"));
    out.put("inputFormat", row.get("input_format"));
    out.put("outputFormat", row.get("output_format"));
    out.put("examples", json.readValue(String.valueOf(row.get("examples")), List.class));
    out.put("difficulty", row.get("difficulty"));
    out.put("tags", json.readValue(String.valueOf(row.get("tags")), List.class));
    out.put("timeLimitMs", row.get("time_limit_ms"));
    out.put("memoryLimitKb", row.get("memory_limit_kb"));
    out.put("allowedLanguages", json.readValue(String.valueOf(row.get("allowed_languages")), List.class));
    out.put("judgeMode", row.get("judge_mode"));
    out.put("functionName", row.get("function_name"));
    out.put("parameterTypes",
        row.get("parameter_types") == null
            ? null
            : json.readValue(String.valueOf(row.get("parameter_types")), List.class));
    out.put("returnType", row.get("return_type"));
    out.put("status", row.get("status"));
    out.put("version", row.get("version"));
    out.put("createdAt", time(row.get("created_at")));
    out.put("updatedAt", time(row.get("updated_at")));
    return out;
  }

  private void audit(SessionPrincipal actor, String action, long id) {
    jdbc.update(
        "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,summary,result,occurred_at) VALUES(?,?, 'ASSESSMENT_PROBLEM',?,?, 'SUCCESS',?)",
        actor.userId(),
        action,
        id,
        action,
        Timestamp.from(clock.instant()));
  }

  private static void validate(Map<String, Object> request) {
    if (request == null) throw bad();
    text(request, "title", 120);
    text(request, "summary", 300);
    text(request, "description", 20000);
    text(request, "inputFormat", 5000);
    text(request, "outputFormat", 5000);
    examples(request.get("examples"));
    difficulty(request.get("difficulty"));
    tags(request.get("tags"));
    integer(request.get("timeLimitMs"), 100, 30000);
    integer(request.get("memoryLimitKb"), 1024, 1048576);
    languages(request.get("allowedLanguages"));
    String mode = judgeMode(request.get("judgeMode"));
    var signature = signatureFor(mode, request);
    if ("FUNCTION".equals(mode)) {
      validateFunctionTestCases(signature, request.get("assessmentTestCases"), true);
      validateFunctionTestCases(signature, request.get("practiceTestCases"), false);
    }
  }

  private static String judgeMode(Object value) {
    if (value == null) return "STDIO";
    if (!(value instanceof String text) || !Set.of("STDIO", "FUNCTION").contains(text)) throw bad();
    return text;
  }

  private static String functionName(Object value) {
    if (!(value instanceof String text) || !text.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw bad();
    return text;
  }

  private static String returnTypeValue(Object value) {
    if (!(value instanceof String text)) throw bad();
    try {
      FunctionHarness.ValueType.parse(text);
      return text;
    } catch (IllegalArgumentException error) {
      throw bad();
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> parameterTypeValues(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 4) throw bad();
    var result = new ArrayList<String>();
    for (Object item : list) {
      if (!(item instanceof String text)) throw bad();
      try {
        FunctionHarness.ValueType.parse(text);
      } catch (IllegalArgumentException error) {
        throw bad();
      }
      result.add(text);
    }
    return result;
  }

  private static FunctionHarness.Signature signatureFor(String mode, Map<String, Object> source) {
    if (!"FUNCTION".equals(mode)) return null;
    try {
      return FunctionHarness.signature(
          functionName(source.get("functionName")),
          parameterTypeValues(source.get("parameterTypes")),
          returnTypeValue(source.get("returnType")));
    } catch (IllegalArgumentException error) {
      throw bad();
    }
  }

  private List<?> jsonList(Object value) {
    if (value == null) return List.of();
    if (value instanceof List<?> list) return list;
    try {
      return json.readValue(String.valueOf(value), List.class);
    } catch (RuntimeException error) {
      return List.of();
    }
  }

  private String functionNameOrNull(String mode, Object value) {
    return "FUNCTION".equals(mode) ? functionName(value) : null;
  }

  private String parameterTypesJson(String mode, Object value) {
    return "FUNCTION".equals(mode) ? json.writeValueAsString(parameterTypeValues(value)) : null;
  }

  private String returnTypeOrNull(String mode, Object value) {
    return "FUNCTION".equals(mode) ? returnTypeValue(value) : null;
  }

  private static void validateFunctionTestCases(
      FunctionHarness.Signature signature, Object value, boolean required) {
    if (value == null) {
      if (required) throw bad();
      return;
    }
    if (!(value instanceof List<?> list)) throw bad();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> testCase) || !(testCase.get("input") instanceof String input))
        throw bad();
      try {
        FunctionHarness.parseArguments(signature, input);
      } catch (IllegalArgumentException error) {
        throw bad();
      }
    }
  }

  private static void validateTestCaseField(Object value, boolean required) {
    if (value == null) {
      if (required) throw bad();
      return;
    }
    if (!(value instanceof List<?> list) || (required && list.isEmpty()) || list.size() > 100) throw bad();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> testCase)) throw bad();
      if (!Set.of("input", "expectedOutput").containsAll(testCase.keySet())
          || testCase.keySet().size() != 2) throw bad();
      if (!(testCase.get("input") instanceof String input)
          || input.length() > 1_000_000) throw bad();
      if (!(testCase.get("expectedOutput") instanceof String expected)
          || expected.length() > 1_000_000) throw bad();
    }
  }

  private static void validateUpdate(Map<String, Object> request) {
    if (request == null || request.size() < 2 || !request.containsKey("version")) throw bad();
    var allowed = Set.of("version", "title", "summary", "description", "inputFormat", "outputFormat", "examples", "difficulty", "tags", "timeLimitMs", "memoryLimitKb", "allowedLanguages", "assessmentTestCases", "practiceTestCases", "judgeMode", "functionName", "parameterTypes", "returnType");
    if (!allowed.containsAll(request.keySet())) throw bad();
    for (var entry : request.entrySet()) {
      String key = entry.getKey();
      if (key.equals("version")) continue;
      Object value = entry.getValue();
      switch (key) {
        case "title" -> text(Map.of(key, value), key, 120);
        case "summary" -> text(Map.of(key, value), key, 300);
        case "description" -> text(Map.of(key, value), key, 20000);
        case "inputFormat" -> text(Map.of(key, value), key, 5000);
        case "outputFormat" -> text(Map.of(key, value), key, 5000);
        case "examples" -> examples(value);
        case "difficulty" -> difficulty(value);
        case "tags" -> tags(value);
        case "timeLimitMs" -> integer(value, 100, 30000);
        case "memoryLimitKb" -> integer(value, 1024, 1048576);
        case "allowedLanguages" -> languages(value);
        case "judgeMode" -> judgeMode(value);
        // 命令行模式下这三个字段会显式传 null，真正的组合校验在下方按生效签名执行。
        case "functionName" -> { if (value != null) functionName(value); }
        case "parameterTypes" -> { if (value != null) parameterTypeValues(value); }
        case "returnType" -> { if (value != null) returnTypeValue(value); }
        case "assessmentTestCases" -> validateTestCaseField(value, true);
        case "practiceTestCases" -> validateTestCaseField(value, false);
        default -> throw bad();
      }
    }
  }

  private static String text(Map<String, Object> request, String field, int max) {
    Object value = request.get(field);
    if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw bad();
    return text;
  }

  private static int integer(Object value, int min, int max) {
    if (!(value instanceof Number number) || number.intValue() != number.longValue()
        || number.intValue() < min || number.intValue() > max) throw bad();
    return number.intValue();
  }

  private static String difficulty(Object value) {
    if (!(value instanceof String text) || !Set.of("EASY", "MEDIUM", "HARD").contains(text)) throw bad();
    return text;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> examples(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 20) throw bad();
    var result = new ArrayList<Map<String, Object>>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> example)) throw bad();
      var output = new LinkedHashMap<String, Object>();
      if (!(example.get("input") instanceof String input) || input.isBlank()) throw bad();
      if (!(example.get("output") instanceof String out) || out.isBlank()) throw bad();
      output.put("input", input);
      output.put("output", out);
      if (example.get("explanation") != null) {
        if (!(example.get("explanation") instanceof String explanation)) throw bad();
        output.put("explanation", explanation);
      }
      if (!Set.of("input", "output", "explanation").containsAll(example.keySet())) throw bad();
      result.add(output);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<String> tags(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 20) throw bad();
    var result = new ArrayList<String>();
    for (Object item : list) {
      if (!(item instanceof String tag) || tag.isBlank() || tag.length() > 40) throw bad();
      result.add(tag);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<String> languages(Object value) {
    if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 6) throw bad();
    var result = new ArrayList<String>();
    for (Object item : list) {
      if (!(item instanceof String language)
          || !Set.of("java", "python", "cpp", "javascript", "go", "rust").contains(language)) throw bad();
      result.add(language);
    }
    return result;
  }

  private long insertProblem(Map<String, Object> request, SessionPrincipal actor) {
    Instant now = clock.instant();
    jdbc.update(
        "INSERT INTO assessment_problem(title,summary,description,input_format,output_format,examples,difficulty,tags,time_limit_ms,memory_limit_kb,allowed_languages,judge_mode,function_name,parameter_types,return_type,status,version,created_by,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'DRAFT',1,?,?,?)",
        text(request, "title", 120),
        text(request, "summary", 300),
        text(request, "description", 20000),
        text(request, "inputFormat", 5000),
        text(request, "outputFormat", 5000),
        json.writeValueAsString(examples(request.get("examples"))),
        request.get("difficulty"),
        json.writeValueAsString(tags(request.get("tags"))),
        integer(request.get("timeLimitMs"), 100, 30000),
        integer(request.get("memoryLimitKb"), 1024, 1048576),
        json.writeValueAsString(languages(request.get("allowedLanguages"))),
        judgeMode(request.get("judgeMode")),
        functionNameOrNull(judgeMode(request.get("judgeMode")), request.get("functionName")),
        parameterTypesJson(judgeMode(request.get("judgeMode")), request.get("parameterTypes")),
        returnTypeOrNull(judgeMode(request.get("judgeMode")), request.get("returnType")),
        actor.userId(),
        Timestamp.from(now),
        Timestamp.from(now));
    return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  private List<Map<String, Object>> validateImportRequest(
      Map<String, Object> request, String requestJson) {
    var errors = new ArrayList<Map<String, Object>>();
    if (requestJson.getBytes(StandardCharsets.UTF_8).length > IMPORT_MAX_BYTES) {
      errors.add(importError(0, "REQUEST_TOO_LARGE"));
      return errors;
    }
    if (request == null || request.size() != 1 || !request.containsKey("problems")) {
      errors.add(importError(0, "INVALID_IMPORT"));
      return errors;
    }
    if (!(request.get("problems") instanceof List<?> problems)) {
      errors.add(importError(0, "INVALID_IMPORT"));
      return errors;
    }
    if (problems.isEmpty()) {
      errors.add(importError(0, "INVALID_IMPORT"));
      return errors;
    }
    if (problems.size() > 100) {
      errors.add(importError(0, "TOO_MANY_PROBLEMS"));
      return errors;
    }
    var titles = new java.util.HashSet<String>();
    for (int index = 0; index < problems.size(); index++) {
      try {
        validateImportProblem(problems.get(index));
        String title = String.valueOf(((Map<?, ?>) problems.get(index)).get("title"))
            .trim()
            .toLowerCase(Locale.ROOT);
        if (!titles.add(title)) {
          errors.add(importError(index, "DUPLICATE_TITLE"));
        }
      } catch (ApiException exception) {
        errors.add(importError(index, "INVALID_PROBLEM"));
      }
      if (errors.size() >= 100) break;
    }
    return errors;
  }

  private static void validateImportProblem(Object value) {
    if (!(value instanceof Map<?, ?> raw)) throw bad();
    var allowed =
        Set.of(
            "title",
            "summary",
            "description",
            "inputFormat",
            "outputFormat",
            "examples",
            "difficulty",
            "tags",
            "timeLimitMs",
            "memoryLimitKb",
            "allowedLanguages",
            "judgeMode",
            "functionName",
            "parameterTypes",
            "returnType",
            "assessmentTestCases",
            "practiceTestCases");
    if (!allowed.containsAll(raw.keySet())) throw bad();
    @SuppressWarnings("unchecked")
    var item = (Map<String, Object>) raw;
    validate(item);
    validateTestCaseField(item.get("assessmentTestCases"), true);
    validateTestCaseField(item.get("practiceTestCases"), false);
  }

  private static Map<String, Object> importError(int index, String code) {
    var error = new LinkedHashMap<String, Object>();
    error.put("index", index);
    error.put("code", code);
    return error;
  }

  private IdempotencyService.StoredResponse importFailure(List<Map<String, Object>> errors) {
    var body =
        Map.of(
            "code",
            "INVALID_REQUEST",
            "message",
            "Import validation failed",
            "data",
            importResult(List.of(), errors));
    return new IdempotencyService.StoredResponse(
        400, json.writeValueAsString(body));
  }

  private static Map<String, Object> importResult(
      List<String> problemIds, List<Map<String, Object>> errors) {
    var result = new LinkedHashMap<String, Object>();
    result.put("importedCount", problemIds.size());
    result.put("problemIds", problemIds);
    result.put("errors", errors);
    return result;
  }

  private void saveTestCases(long problemId, String table, Object value) {
    if (value == null) return;
    var list = (List<?>) value;
    Instant now = clock.instant();
    int position = 1;
    for (Object item : list) {
      var testCase = (Map<?, ?>) item;
      jdbc.update(
          "INSERT INTO "
              + table
              + "(problem_id,position,input,expected_output,created_at,updated_at) VALUES(?,?,?,?,?,?)",
          problemId,
          position++,
          testCase.get("input"),
          testCase.get("expectedOutput"),
          Timestamp.from(now),
          Timestamp.from(now));
    }
  }

  private List<Map<String, Object>> readTestCases(long problemId, String table) {
    var rows =
        jdbc.queryForList(
            "SELECT position,input,expected_output FROM " + table
                + " WHERE problem_id=? ORDER BY position",
            problemId);
    var result = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      result.add(
          Map.of(
              "position",
              row.get("position"),
              "input",
              row.get("input"),
              "expectedOutput",
              row.get("expected_output")));
    }
    return result;
  }

  private static long version(Object value) {
    if (!(value instanceof Number number) || number.longValue() < 1) throw bad();
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
}
