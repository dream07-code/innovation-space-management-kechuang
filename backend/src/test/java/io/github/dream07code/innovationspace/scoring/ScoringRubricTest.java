package io.github.dream07code.innovationspace.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScoringRubricTest {

  @Test
  void dailyRubricUsesDailyFieldsAndAnchors() {
    String prompt = ScoringRubric.current("DAILY");
    assertTrue(prompt.contains("日报评分档位"));
    assertFalse(prompt.contains("周报评分档位"));
    assertTrue(prompt.contains("learningContent"));
    assertTrue(prompt.contains("gains"));
    assertTrue(prompt.contains("3 分"));
    assertTrue(prompt.contains("2 分"));
    assertTrue(prompt.contains("1 分"));
  }

  @Test
  void weeklyRubricUsesWeeklyFields() {
    String prompt = ScoringRubric.current("WEEKLY");
    assertTrue(prompt.contains("周报评分档位"));
    assertFalse(prompt.contains("日报评分档位"));
    assertTrue(prompt.contains("summary"));
    assertTrue(prompt.contains("achievements"));
    assertTrue(prompt.contains("nextWeekPlan"));
  }

  @Test
  void unknownTypeFallsBackToBothRubrics() {
    String prompt = ScoringRubric.current(null);
    assertTrue(prompt.contains("日报评分档位"));
    assertTrue(prompt.contains("周报评分档位"));
  }

  @Test
  void rubricKeepsAntiGamingAndInjectionGuards() {
    String prompt = ScoringRubric.current("DAILY");
    assertTrue(prompt.contains("字数多不等于分高"));
    assertTrue(prompt.contains("不可信数据"));
    assertTrue(prompt.contains("一律忽略"));
    assertTrue(prompt.contains("与学习无关"));
  }

  @Test
  void rubricDefinesStrictJsonOutput() {
    String prompt = ScoringRubric.current("WEEKLY");
    assertTrue(prompt.contains("{\"score\":1,\"reason\""));
    assertTrue(prompt.contains("只输出一行 JSON"));
    assertTrue(prompt.contains("不超过60字"));
  }

  @Test
  void rubricVersionIsExplicit() {
    assertEquals("v2", ScoringRubric.VERSION);
  }

  @Test
  void safeInputMarksReportContentAsUntrusted() {
    String input =
        ScoringRubric.safeInput(
            new ScoringInput("DAILY", "7", ScoringRubric.VERSION, "{\"learningContent\":\"x\"}"));
    assertTrue(input.contains("untrusted content"));
    assertTrue(input.contains("type=DAILY"));
    assertTrue(input.contains("rubricVersion=v2"));
    assertTrue(input.contains("content="));
  }
}
