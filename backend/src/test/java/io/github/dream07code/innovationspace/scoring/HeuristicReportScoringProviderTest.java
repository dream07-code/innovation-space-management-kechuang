package io.github.dream07code.innovationspace.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeuristicReportScoringProviderTest {
  private final HeuristicReportScoringProvider provider = new HeuristicReportScoringProvider();

  private ProviderScore score(String type, String content) {
    return provider.score(new ScoringInput(type, "1", ScoringRubric.VERSION, content));
  }

  @Test
  void vagueShortDailyGetsOnePoint() {
    assertEquals(1, score("DAILY", "{\"learningContent\":\"学习了 Java\"}").score());
  }

  @Test
  void concreteButNoReflectionGetsTwoPoints() {
    String content =
        "{\"learningContent\":\"完成 Spring Boot 参数校验练习，给三个接口加了 @Valid 和分组校验。\","
            + "\"gains\":\"理解了校验注解的执行顺序。\"}";
    assertEquals(2, score("DAILY", content).score());
  }

  @Test
  void detailedDailyWithReflectionGetsThreePoints() {
    String content =
        "{\"learningContent\":\"完成 Spring Boot 参数校验练习，给三个接口加了 @Valid 和分组校验。\","
            + "\"gains\":\"理解了校验注解的执行顺序和异常映射。\","
            + "\"problems\":\"自定义注解一开始不生效，排查后发现是拦截器顺序问题。\"}";
    assertEquals(3, score("DAILY", content).score());
  }

  @Test
  void vagueWeeklyGetsOnePoint() {
    String content = "{\"summary\":\"本周学习了一些前端知识。\",\"achievements\":\"看完了教程。\"}";
    assertEquals(1, score("WEEKLY", content).score());
  }

  @Test
  void weeklyWithoutReflectionOrPlanGetsTwoPoints() {
    String content =
        "{\"summary\":\"本周完成 Vue 组件通信和路由守卫练习。\","
            + "\"achievements\":\"完成了 3 个页面跳转和参数传递。\"}";
    assertEquals(2, score("WEEKLY", content).score());
  }

  @Test
  void detailedWeeklyGetsThreePoints() {
    String content =
        "{\"summary\":\"本周完成报告模块联调，覆盖日报、周报与人工评分。\","
            + "\"achievements\":\"完成 3 个接口联调并通过 99 个前端测试。\","
            + "\"reflections\":\"权限过滤一开始漏了历史方向，已补上。\","
            + "\"nextWeekPlan\":\"补齐审计页面并做一次回归。\"}";
    assertEquals(3, score("WEEKLY", content).score());
  }

  @Test
  void invalidContentFallsBackToOnePointWithoutThrowing() {
    ProviderScore result = score("DAILY", "not-json");
    assertEquals(1, result.score());
    assertFalse(result.reason().isBlank());
  }

  @Test
  void registrySelectsHeuristicProvider() {
    ScoringProperties properties = new ScoringProperties();
    properties.setEnabled(true);
    properties.setProvider("heuristic");
    properties.validate();
    var registry =
        new ScoringProviderRegistry(
            properties,
            List.of(new MockReportScoringProvider(), new HeuristicReportScoringProvider()));
    assertInstanceOf(HeuristicReportScoringProvider.class, registry.provider());
  }
}
