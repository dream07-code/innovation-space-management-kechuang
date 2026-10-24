package io.github.dream07code.innovationspace.assessment;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuizGraderTest {

  @Test
  void singleChoiceGradesByExactLetterMatch() {
    var questions = Map.of(
        11L, new QuizGrader.QuestionSpec("SINGLE_CHOICE", List.of("快速排序", "归并排序", "冒泡排序", "堆排序"), "A"));
    assertGraded(11L, 5, questions, "A", true);
    assertGraded(11L, 5, questions, "B", false);
  }

  @Test
  void multiChoiceAwardsFullScoreOnlyOnExactSetMatch() {
    var questions = Map.of(
        21L, new QuizGrader.QuestionSpec("MULTI_CHOICE", List.of("选项一", "选项二", "选项三", "选项四"), List.of("A", "B", "D")));
    assertGraded(21L, 10, questions, List.of("A", "B", "D"), true);
    assertGraded(21L, 10, questions, List.of("A", "D"), false);
    assertGraded(21L, 10, questions, List.of("A", "B", "C", "D"), false);
    assertGraded(21L, 10, questions, List.of("D", "A", "B"), true);
  }

  @Test
  void trueFalseGradesByBooleanEquality() {
    var questions = Map.of(
        31L, new QuizGrader.QuestionSpec("TRUE_FALSE", null, Boolean.TRUE));
    assertGraded(31L, 2, questions, Boolean.TRUE, true);
    assertGraded(31L, 2, questions, Boolean.FALSE, false);
  }

  @Test
  void fillBlankTrimsAndMatchesAnyAlternativeCaseSensitively() {
    var questions = Map.of(
        41L, new QuizGrader.QuestionSpec("FILL_BLANK", null, List.of("answer", "res")));
    assertGraded(41L, 3, questions, "  answer ", true);
    assertGraded(41L, 3, questions, "Answer", false);
    assertGraded(41L, 3, questions, "res", true);
  }

  @Test
  void unansweredPositionsGetZero() {
    var questions = Map.of(
        51L, new QuizGrader.QuestionSpec("SINGLE_CHOICE", List.of("选项一", "选项二"), "A"),
        52L, new QuizGrader.QuestionSpec("TRUE_FALSE", null, Boolean.TRUE));
    var items = List.of(
        new QuizGrader.ItemSpec(1, 51L, 4),
        new QuizGrader.ItemSpec(2, 52L, 6));
    Map<Integer, Object> answers = new HashMap<>();
    answers.put(2, null);
    assertEquals(List.of(
        new QuizGrader.Graded(1, 51L, false, 0),
        new QuizGrader.Graded(2, 52L, false, 0)), QuizGrader.grade(items, questions, answers));
  }

  private static void assertGraded(long questionId, int score, Map<Long, QuizGrader.QuestionSpec> questions, Object answer, boolean expectedCorrect) {
    var graded = QuizGrader.grade(
        List.of(new QuizGrader.ItemSpec(1, questionId, score)), questions, Map.of(1, answer)).get(0);
    assertEquals(1, graded.position());
    assertEquals(questionId, graded.questionId());
    assertEquals(expectedCorrect, graded.correct());
    assertEquals(expectedCorrect ? score : 0, graded.awarded());
  }
}
