package io.github.dream07code.innovationspace.assessment;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 客观题纯函数判分引擎。
 *
 * 交卷时由服务层组装条目、题干与作答值调用，纯静态、无 IO：
 * 单选/判断等值比对；多选两边转字母集合比较（漏选/多选均为 0 分）；
 * 填空学生答案与备选逐个 trim 后区分大小写比对；未作答按 0 分返回。
 * 选项字母与 options 下标一一对应（下标 0 → 'A'）。
 */
public final class QuizGrader {

  public record ItemSpec(int position, long questionId, int score) {}

  public record QuestionSpec(String type, List<String> options, Object answer) {}

  public record Graded(int position, long questionId, boolean correct, int awarded) {}

  private QuizGrader() {}

  public static List<Graded> grade(List<ItemSpec> items, Map<Long, QuestionSpec> questions, Map<Integer, Object> answers) {
    return items.stream()
        .map(item -> grade(item, questions.get(item.questionId()), answers.get(item.position())))
        .toList();
  }

  private static Graded grade(ItemSpec item, QuestionSpec question, Object answer) {
    boolean correct = answer != null && matches(question, answer);
    return new Graded(item.position(), item.questionId(), correct, correct ? item.score() : 0);
  }

  private static boolean matches(QuestionSpec question, Object answer) {
    return switch (question.type()) {
      case "SINGLE_CHOICE", "TRUE_FALSE" -> Objects.equals(answer, question.answer());
      case "MULTI_CHOICE" -> letterSet(answer).equals(letterSet(question.answer()));
      case "FILL_BLANK" -> matchesAlternative(answer, question.answer());
      default -> false;
    };
  }

  private static Set<String> letterSet(Object value) {
    if (!(value instanceof List<?> letters)) {
      return Set.of();
    }
    var result = new HashSet<String>();
    for (Object letter : letters) {
      result.add(String.valueOf(letter));
    }
    return result;
  }

  private static boolean matchesAlternative(Object answer, Object accepted) {
    if (answer instanceof String text && accepted instanceof List<?> alternatives) {
      var trimmed = text.trim();
      for (Object alternative : alternatives) {
        if (alternative instanceof String candidate && trimmed.equals(candidate.trim())) {
          return true;
        }
      }
    }
    return false;
  }
}
