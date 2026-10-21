package io.github.dream07code.innovationspace.scoring;

import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 本地启发式评分 Provider。
 *
 * <p>不调用外部模型，按 {@link ScoringRubric} 的档位做确定性近似，用于本地联调和演示：
 * 只看内容是否具体、字段是否完整、有没有反思，不按篇幅直接给高分。
 * 生产环境仍应使用 {@code openai-compatible}。
 */
public class HeuristicReportScoringProvider implements ReportScoringProvider {
  private static final String[] DAILY_FIELDS = {"learningContent", "gains", "problems", "nextPlan"};
  private static final String[] WEEKLY_FIELDS = {"summary", "achievements", "reflections", "nextWeekPlan"};

  private final JsonMapper json = JsonMapper.builder().build();

  @Override
  public ProviderScore score(ScoringInput input) {
    Map<String, Object> content = parse(input.content());
    boolean weekly = "WEEKLY".equals(input.reportType());
    String[] fields = weekly ? WEEKLY_FIELDS : DAILY_FIELDS;

    int filled = 0;
    int chars = 0;
    for (String field : fields) {
      String value = text(content.get(field));
      if (value.isBlank()) continue;
      filled++;
      chars += value.replaceAll("\\s+", "").length();
    }
    boolean hasReflection =
        weekly
            ? !text(content.get("reflections")).isBlank() || !text(content.get("nextWeekPlan")).isBlank()
            : !text(content.get("problems")).isBlank() || !text(content.get("nextPlan")).isBlank();

    if (chars < 15 || filled == 0) {
      return result(1, "有效内容过少，缺少具体的学习过程。");
    }
    if (filled == 1 && chars < 25) {
      return result(1, "只有一句笼统描述，没有具体过程或收获。");
    }
    if (chars < 30) {
      return result(1, "有效信息偏少，只有笼统描述，缺少具体过程或成果。");
    }
    if (filled >= 3 && hasReflection && chars >= 40) {
      return result(3, "内容具体，有可检验的收获，并对问题或下一步有反思。");
    }
    return result(2, "内容具体，但收获或反思不足，缺少可检验的结果。");
  }

  private ProviderScore result(int score, String reason) {
    return new ProviderScore(score, reason, "heuristic", ScoringRubric.VERSION);
  }

  private Map<String, Object> parse(String raw) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> value = json.readValue(raw, Map.class);
      return value == null ? Map.of() : value;
    } catch (Exception error) {
      return Map.of();
    }
  }

  private static String text(Object value) {
    return value instanceof String string ? string.trim() : "";
  }
}
