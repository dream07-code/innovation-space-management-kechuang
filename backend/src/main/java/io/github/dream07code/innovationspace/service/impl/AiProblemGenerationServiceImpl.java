package io.github.dream07code.innovationspace.service.impl;

import io.github.dream07code.innovationspace.assessment.FunctionHarness;
import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjSubmission;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import io.github.dream07code.innovationspace.assessment.adapter.OjAdapter;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.common.validation.RequestFields;
import io.github.dream07code.innovationspace.problemgen.ProblemDraft;
import io.github.dream07code.innovationspace.problemgen.ProblemDraftParser;
import io.github.dream07code.innovationspace.problemgen.AiProblemCancellationRegistry;
import io.github.dream07code.innovationspace.problemgen.ProblemGenerator;
import io.github.dream07code.innovationspace.security.RateLimitService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.service.AiProblemGenerationService;
import io.github.dream07code.innovationspace.service.AssessmentProblemService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 出题实现。
 *
 * <p>流程：模型生成 → 结构校验 → 用 Java 参考解法把每个测试点真跑一遍 → 落库。
 * 自测不过就带着失败原因重生成一次；第二次仍不过直接报错，宁可让学生换个知识点，
 * 也不把「参考解都做不对」的题写进题库。
 *
 * <p>落库状态直接是 PUBLISHED：这是学生自己的练习题，不需要走考核发布流程；
 * {@code source='AI'} 与 {@code owner_member_id} 用于前端打标签和「我的题库」过滤。
 */
@Service
public class AiProblemGenerationServiceImpl implements AiProblemGenerationService {
  private static final Logger log = LoggerFactory.getLogger(AiProblemGenerationServiceImpl.class);
  /** 出题要调模型再跑判题，单次十几秒起步，配额按「10 分钟 6 道」限制。 */
  private static final int GENERATE_REQUESTS = 6;
  private static final int GENERATE_WINDOW_SECONDS = 600;
  private static final int MAX_ATTEMPTS = 2;
  private static final List<String> ALLOWED_LANGUAGES =
      List.of("java", "python", "cpp", "javascript", "go", "rust");
  private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
  private static final Set<String> FIELDS =
      Set.of("topic", "difficulty", "category", "requestId");

  private final JdbcTemplate jdbc;
  private final ProblemGenerator generator;
  private final OjAdapter adapter;
  private final AssessmentProblemService problems;
  private final RateLimitService rateLimit;
  private final AiProblemCancellationRegistry cancellations;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();
  private final TransactionTemplate tx;

  public AiProblemGenerationServiceImpl(
      JdbcTemplate jdbc,
      ProblemGenerator generator,
      OjAdapter adapter,
      AssessmentProblemService problems,
      RateLimitService rateLimit,
      AiProblemCancellationRegistry cancellations,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.generator = generator;
    this.adapter = adapter;
    this.problems = problems;
    this.rateLimit = rateLimit;
    this.cancellations = cancellations;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  @Override
  public Map<String, Object> generate(Map<String, Object> request, SessionPrincipal actor) {
    var input = parse(request, actor);
    rateLimit.check(
        "ai-problem:" + actor.memberId(), GENERATE_REQUESTS, GENERATE_WINDOW_SECONDS);

    long startedAt = clock.millis();
    ProblemDraft draft = null;
    String feedback = null;
    try {
      for (int attempt = 1; attempt <= MAX_ATTEMPTS && draft == null; attempt++) {
        // 取消后就别再重试了：学生已经不要这道题，省一次模型调用和自测。
        if (isCancelled(input, actor)) throw cancelled();
        try {
        var candidate =
            generator.generate(
                new ProblemGenerator.Request(
                    input.topic(), input.difficulty(), input.category(), feedback));
        var failure = verify(candidate);
        if (failure.isEmpty()) {
          draft = candidate;
        } else {
          feedback = failure.get();
          log.info(
              "AI problem failed self-check (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, feedback);
        }
        } catch (ProblemDraftParser.InvalidDraft error) {
          feedback = "输出不符合题目规格：" + error.getMessage();
          log.info(
              "AI problem draft invalid (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, error.getMessage());
        } catch (ProblemGenerator.Unavailable error) {
          log.warn("AI problem generation unavailable: {}", error.getMessage());
          throw new ApiException(503, ErrorCode.SERVICE_UNAVAILABLE, "AI 出题暂不可用");
        }
      }
      if (draft == null) {
        throw new ApiException(
            503, ErrorCode.SERVICE_UNAVAILABLE, "AI 生成的题目未通过自测，请稍后换个知识点重试");
      }
      // 落库前最后确认一次；落库后再确认一次，命中就立刻回滚，保证「取消」不留题。
      if (isCancelled(input, actor)) throw cancelled();
      long problemId = save(draft, input, actor);
      if (isCancelled(input, actor)) {
        discard(problemId);
        throw cancelled();
      }
      log.info(
          "AI problem {} generated by member {} in {} ms ({} cases)",
          problemId,
          actor.memberId(),
          clock.millis() - startedAt,
          draft.testCases().size());
      return generated(problemId, draft, actor);
    } finally {
      if (input.requestId() != null) cancellations.clear(actor.memberId(), input.requestId());
    }
  }

  @Override
  public Map<String, Object> cancel(Map<String, Object> request, SessionPrincipal actor) {
    if (actor == null || !"STUDENT".equals(actor.role()) || actor.memberId() == null)
      throw new ApiException(403, ErrorCode.FORBIDDEN);
    if (request == null || request.get("requestId") == null)
      throw invalid("requestId is required");
    if (!(request.get("requestId") instanceof String rawRequestId))
      throw invalid("requestId must be a string");
    String requestId = rawRequestId.strip();
    if (requestId.isEmpty() || requestId.length() > 64)
      throw invalid("requestId length must be 1-64");
    cancellations.cancel(actor.memberId(), requestId);
    return Map.of("cancelled", true);
  }

  private boolean isCancelled(Input input, SessionPrincipal actor) {
    return input.requestId() != null && cancellations.isCancelled(actor.memberId(), input.requestId());
  }

  private static ApiException cancelled() {
    return new ApiException(409, ErrorCode.INVALID_STATE, "题目生成已取消");
  }

  /** 取消时清掉刚落库的题：测试点与审计都一起删，不留孤儿记录。 */
  private void discard(long problemId) {
    tx.execute(
        status -> {
          for (String table :
              List.of("assessment_problem_assessment_test_case", "assessment_problem_practice_test_case")) {
            jdbc.update("DELETE FROM " + table + " WHERE problem_id=?", problemId);
          }
          jdbc.update(
              "DELETE FROM assessment_audit WHERE resource_type='ASSESSMENT_PROBLEM'"
                  + " AND resource_id=? AND action='AI_GENERATE_PROBLEM'",
              problemId);
          jdbc.update("DELETE FROM assessment_problem WHERE id=?", problemId);
          return null;
        });
    log.info("AI problem {} discarded because the student cancelled", problemId);
  }

  /**
   * 出题接口的响应：题目（与详情接口同结构）+ 参考解法 + 测试点。
   *
   * <p>参考解法只在这一次生成结果里返回，题目详情接口不带——学生要的是「这道题怎么做」，
   * 而不是把答案挂在公开题面上。
   */
  private Map<String, Object> generated(
      long problemId, ProblemDraft draft, SessionPrincipal actor) {
    var testCases = new ArrayList<Map<String, Object>>(draft.testCases().size());
    for (int index = 0; index < draft.testCases().size(); index++) {
      var testCase = draft.testCases().get(index);
      var item = new java.util.LinkedHashMap<String, Object>();
      item.put("position", index + 1);
      item.put("input", testCase.input());
      item.put("expectedOutput", testCase.expectedOutput());
      item.put("boundary", testCase.boundary());
      testCases.add(item);
    }
    var out = new java.util.LinkedHashMap<String, Object>();
    out.put("problem", problems.get(problemId, actor));
    out.put("referenceCode", draft.referenceCode());
    out.put("testCases", testCases);
    return out;
  }

  private Input parse(Map<String, Object> request, SessionPrincipal actor) {
    if (actor == null || !"STUDENT".equals(actor.role()) || actor.memberId() == null)
      throw new ApiException(403, ErrorCode.FORBIDDEN);
    if (request == null) throw invalid("request body is required");
    RequestFields.rejectUnknown(request, FIELDS);
    RequestFields.requirePresent(request, "topic", "difficulty");
    if (!(request.get("topic") instanceof String rawTopic)) throw invalid("topic must be a string");
    String topic = rawTopic.strip();
    if (topic.isEmpty() || topic.length() > 60) throw invalid("topic length must be 1-60");
    if (!(request.get("difficulty") instanceof String difficulty)
        || !DIFFICULTIES.contains(difficulty))
      throw invalid("difficulty must be EASY, MEDIUM or HARD");
    String category = null;
    Object rawCategory = request.get("category");
    if (rawCategory != null) {
      if (!(rawCategory instanceof String text)) throw invalid("category must be a string");
      String trimmed = text.strip();
      if (trimmed.length() > 40) throw invalid("category length must be at most 40");
      category = trimmed.isEmpty() ? null : trimmed;
    }
    String requestId = null;
    Object rawRequestId = request.get("requestId");
    if (rawRequestId != null) {
      if (!(rawRequestId instanceof String text)) throw invalid("requestId must be a string");
      String trimmed = text.strip();
      if (trimmed.isEmpty() || trimmed.length() > 64)
        throw invalid("requestId length must be 1-64");
      requestId = trimmed;
    }
    return new Input(topic, difficulty, category, requestId);
  }

  /**
   * 用参考解法跑每个测试点。
   *
   * <p>走的是与正式判题同一条链路（{@link FunctionHarness} 包装 + {@link OjAdapter}），
   * 所以这一步通过，学生提交同样的解法也能通过；返回第一条失败的描述用于重试提示。
   */
  Optional<String> verify(ProblemDraft draft) {
    var signature =
        ProblemDraftParser.signature(draft.functionName(), draft.parameterTypes(), draft.returnType());
    var cases = draft.testCases();
    for (int index = 0; index < cases.size(); index++) {
      var testCase = cases.get(index);
      OjJudgeResult result;
      try {
        var arguments = FunctionHarness.parseArguments(signature, testCase.input());
        var wrapped = FunctionHarness.wrap("java", signature, arguments, draft.referenceCode());
        var expected =
            FunctionHarness.canonicalExpected(testCase.expectedOutput(), signature.returns());
        result =
            adapter.judge(
                new OjSubmission(
                    "java",
                    wrapped,
                    List.of(new OjSubmission.TestCase("", expected)),
                    draft.timeLimitMs()));
      } catch (RuntimeException error) {
        return Optional.of("第 " + (index + 1) + " 个测试点无法运行：" + error.getClass().getSimpleName());
      }
      if (result.verdict() != OjVerdict.ACCEPTED) {
        String actual =
            result.testCaseResults().isEmpty()
                ? ""
                : String.valueOf(result.testCaseResults().getFirst().actualOutput());
        return Optional.of(
            "第 "
                + (index + 1)
                + " 个测试点未通过（"
                + result.verdict()
                + "）：输入 "
                + abbreviate(testCase.input())
                + "，期望 "
                + abbreviate(testCase.expectedOutput())
                + "，参考解法实际输出 "
                + abbreviate(actual));
      }
    }
    return Optional.empty();
  }

  private long save(ProblemDraft draft, Input input, SessionPrincipal actor) {
    var now = clock.instant();
    return tx.execute(
        status -> {
          jdbc.update(
              "INSERT INTO assessment_problem(title,summary,description,input_format,output_format,"
                  + "examples,difficulty,tags,time_limit_ms,memory_limit_kb,allowed_languages,"
                  + "judge_mode,function_name,parameter_types,return_type,status,version,created_by,"
                  + "created_at,updated_at,source,owner_member_id)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?, 'FUNCTION',?,?,?, 'PUBLISHED',1,?,?,?, 'AI',?)",
              draft.title(),
              draft.summary(),
              draft.description(),
              draft.inputFormat(),
              draft.outputFormat(),
              json.writeValueAsString(draft.examples()),
              input.difficulty(),
              json.writeValueAsString(tags(draft, input)),
              draft.timeLimitMs(),
              draft.memoryLimitKb(),
              json.writeValueAsString(ALLOWED_LANGUAGES),
              draft.functionName(),
              json.writeValueAsString(draft.parameterTypes()),
              draft.returnType(),
              actor.userId(),
              Timestamp.from(now),
              Timestamp.from(now),
              actor.memberId());
          long problemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
          for (int index = 0; index < draft.testCases().size(); index++) {
            var testCase = draft.testCases().get(index);
            for (String table :
                List.of("assessment_problem_assessment_test_case", "assessment_problem_practice_test_case")) {
              jdbc.update(
                  "INSERT INTO " + table
                      + "(problem_id,position,input,expected_output,created_at,updated_at)"
                      + " VALUES(?,?,?,?,?,?)",
                  problemId,
                  index + 1,
                  testCase.input(),
                  testCase.expectedOutput(),
                  Timestamp.from(now),
                  Timestamp.from(now));
            }
          }
          jdbc.update(
              "INSERT INTO assessment_audit(actor_user_id,action,resource_type,resource_id,summary,result,occurred_at)"
                  + " VALUES(?, 'AI_GENERATE_PROBLEM','ASSESSMENT_PROBLEM',?,?, 'SUCCESS',?)",
              actor.userId(),
              problemId,
              "AI 出题：" + input.topic(),
              Timestamp.from(now));
          return problemId;
        });
  }

  /**
   * 标签：固定带「AI 生成」，再加知识点与题型，最后合并模型给的标签（去重、截断到 20 个）。
   * 「AI 生成」放第一位，题库列表的标签列一眼能看出是自动生成的题。
   */
  private static List<String> tags(ProblemDraft draft, Input input) {
    var tags = new LinkedHashSet<String>();
    tags.add("AI 生成");
    addTag(tags, input.topic());
    addTag(tags, input.category());
    for (String tag : draft.tags()) addTag(tags, tag);
    var result = new ArrayList<String>(tags);
    return result.size() > 20 ? result.subList(0, 20) : result;
  }

  private static void addTag(Set<String> tags, String value) {
    if (value == null) return;
    String tag = value.strip();
    if (tag.isEmpty() || tag.length() > 40 || tags.size() >= 20) return;
    tags.add(tag);
  }

  private static String abbreviate(String value) {
    if (value == null) return "(空)";
    String text = value.replace('\n', ' ').strip();
    return text.length() <= 120 ? text : text.substring(0, 120) + "…";
  }

  private static ApiException invalid(String detail) {
    return new ApiException(400, ErrorCode.INVALID_REQUEST, detail);
  }

  private record Input(String topic, String difficulty, String category, String requestId) {}
}
