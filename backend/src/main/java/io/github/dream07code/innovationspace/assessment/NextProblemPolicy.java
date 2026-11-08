package io.github.dream07code.innovationspace.assessment;

/**
 * 「推荐下一题」的难度规则。
 *
 * <p>刻意做成纯函数：规则只有一条，但它是批改报告里唯一会被评审追问"为什么推这道"的地方，
 * 放在这里能直接用单测锁住，不需要数据库。
 *
 * <p>规则：做对推下一难度（EASY→MEDIUM→HARD），做错推同难度补练；
 * 已经是 HARD 且做对时没有更高难度，改为同难度换一题。
 */
public final class NextProblemPolicy {
  public static final String RULE_NEXT_DIFFICULTY = "NEXT_DIFFICULTY";
  public static final String RULE_SAME_DIFFICULTY = "SAME_DIFFICULTY";

  private NextProblemPolicy() {}

  public record Target(String rule, String difficulty, String reason) {}

  public static Target target(String currentDifficulty, boolean accepted) {
    String current = normalize(currentDifficulty);
    if (!accepted)
      return new Target(RULE_SAME_DIFFICULTY, current, "本次未通过，先在同难度补练一道，把同类问题练稳。");
    return switch (current) {
      case "EASY" -> new Target(RULE_NEXT_DIFFICULTY, "MEDIUM", "本次通过，难度升到中等。");
      case "MEDIUM" -> new Target(RULE_NEXT_DIFFICULTY, "HARD", "本次通过，难度升到困难。");
      default -> new Target(RULE_SAME_DIFFICULTY, "HARD", "已到最高难度，换一道同难度题目继续练。");
    };
  }

  private static String normalize(String value) {
    if ("MEDIUM".equals(value) || "HARD".equals(value)) return value;
    return "EASY";
  }
}
