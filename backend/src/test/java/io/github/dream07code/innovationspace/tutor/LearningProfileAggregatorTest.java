package io.github.dream07code.innovationspace.tutor;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 画像聚合口径。
 *
 * <p>这些数字会直接展示给学生，所以口径必须能靠单测说清楚：同题多次提交只算一题、
 * 薄弱点按「没通过题数多 + 通过率低」排序、推荐只给公开题库里还没通过的题、
 * 没有做题记录时不给任何推荐。
 */
class LearningProfileAggregatorTest {
  private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

  @Test
  void countsDistinctProblemsAndComputesPassRate() {
    var profile =
        LearningProfileAggregator.aggregate(
            7L, attempted(), published(), 82, Instant.parse("2026-11-09T10:00:00Z"), NOW);

    assertEquals(3, profile.attemptedProblems());
    assertEquals(2, profile.passedProblems());
    assertEquals(67, profile.passRate());
    assertEquals(6, profile.submissionCount(), "提交条数按题累加");
    assertEquals(82, profile.averageScore());
    assertEquals(Instant.parse("2026-11-09T10:00:00Z"), profile.lastSubmittedAt());
    assertEquals(NOW, profile.snapshotAt());
  }

  @Test
  void ranksWeakTagsByFailedProblemsThenPassRate() {
    var points =
        LearningProfileAggregator.aggregate(7L, attempted(), published(), null, null, NOW)
            .weakPoints();

    assertEquals(2, points.size(), "只保留有未通过题目的标签");
    assertEquals("滑动窗口", points.get(0).tag());
    assertEquals(0, points.get(0).passRate());
    assertEquals(1, points.get(0).attempted());
    assertEquals("数组", points.get(1).tag());
    assertEquals(1, points.get(1).passed());
    assertEquals(2, points.get(1).attempted());
  }

  @Test
  void recommendsWeakestTagsAndSkipsPassedProblems() {
    var recommendations =
        LearningProfileAggregator.aggregate(7L, attempted(), published(), null, null, NOW)
            .recommendations();

    assertEquals(3, recommendations.size());
    assertEquals(5L, recommendations.get(0).problemId(), "命中两个薄弱标签的题排最前");
    assertEquals("「滑动窗口」目前 0/1 通过，先补这个知识点。", recommendations.get(0).reason());
    assertEquals(2L, recommendations.get(1).problemId(), "同题补练排在新题之后");
    assertEquals(4L, recommendations.get(2).problemId());
    assertTrue(
        recommendations.stream().noneMatch(item -> item.problemId() == 1L),
        "已经通过的题不能再推荐");
  }

  @Test
  void emptyHistoryProducesNoFabricatedContent() {
    var profile = LearningProfileAggregator.aggregate(7L, List.of(), published(), null, null, NOW);

    assertEquals(0, profile.attemptedProblems());
    assertEquals(0, profile.passRate());
    assertEquals(0, profile.submissionCount());
    assertNull(profile.averageScore());
    assertEquals(List.of(), profile.weakPoints());
    assertEquals(List.of(), profile.recommendations());
  }

  @Test
  void allPassedMeansNoWeakPointsButNewPracticeIsStillSuggested() {
    var allPassed =
        List.of(
            new AttemptedProblem(
                1L, "两数之和", "EASY", List.of("数组"), 1, true, NOW.minusSeconds(60)),
            new AttemptedProblem(
                3L, "链表反转", "EASY", List.of("链表"), 1, true, NOW.minusSeconds(30)));

    var profile =
        LearningProfileAggregator.aggregate(7L, allPassed, published(), 100, NOW, NOW);

    assertEquals(100, profile.passRate());
    assertEquals(List.of(), profile.weakPoints());
    assertFalse(profile.recommendations().isEmpty(), "还有没通过的题时可以继续推荐");
    assertEquals(
        "换一道还没通过的题目继续练。", profile.recommendations().getFirst().reason());
  }

  private static List<AttemptedProblem> attempted() {
    return List.of(
        new AttemptedProblem(
            1L, "两数之和", "EASY", List.of("数组", "基础"), 2, true, NOW.minusSeconds(300)),
        new AttemptedProblem(
            2L, "滑动窗口", "MEDIUM", List.of("数组", "滑动窗口"), 3, false, NOW.minusSeconds(120)),
        new AttemptedProblem(
            3L, "链表反转", "EASY", List.of("链表"), 1, true, NOW.minusSeconds(60)));
  }

  private static List<CandidateProblem> published() {
    return List.of(
        new CandidateProblem(1L, "两数之和", "EASY", List.of("数组", "基础")),
        new CandidateProblem(2L, "滑动窗口", "MEDIUM", List.of("数组", "滑动窗口")),
        new CandidateProblem(4L, "数组去重", "EASY", List.of("数组")),
        new CandidateProblem(5L, "最长无重复子串", "MEDIUM", List.of("滑动窗口", "数组")),
        new CandidateProblem(7L, "课程表", "HARD", List.of("图")));
  }
}
