package io.github.dream07code.innovationspace.assessment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 「推荐下一题」规则：做对升一档，做错同难度补练。 */
class NextProblemPolicyTest {

  @Test
  void promotesToNextDifficultyWhenAccepted() {
    assertEquals("MEDIUM", NextProblemPolicy.target("EASY", true).difficulty());
    assertEquals("HARD", NextProblemPolicy.target("MEDIUM", true).difficulty());
    assertEquals(NextProblemPolicy.RULE_NEXT_DIFFICULTY, NextProblemPolicy.target("EASY", true).rule());
  }

  @Test
  void staysOnSameDifficultyWhenWrong() {
    for (String difficulty : new String[] {"EASY", "MEDIUM", "HARD"}) {
      var target = NextProblemPolicy.target(difficulty, false);
      assertEquals(difficulty, target.difficulty());
      assertEquals(NextProblemPolicy.RULE_SAME_DIFFICULTY, target.rule());
      assertFalse(target.reason().isBlank());
    }
  }

  @Test
  void keepsHardAtHardWhenAlreadyTop() {
    var target = NextProblemPolicy.target("HARD", true);

    assertEquals("HARD", target.difficulty());
    assertEquals(NextProblemPolicy.RULE_SAME_DIFFICULTY, target.rule());
    assertTrue(target.reason().contains("最高难度"));
  }

  @Test
  void treatsUnknownDifficultyAsEasyRatherThanFailing() {
    assertEquals("EASY", NextProblemPolicy.target(null, false).difficulty());
    assertEquals("EASY", NextProblemPolicy.target("weird", false).difficulty());
    assertEquals("MEDIUM", NextProblemPolicy.target(null, true).difficulty());
  }
}
