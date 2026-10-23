package io.github.dream07code.innovationspace.assessment.service;

import org.springframework.stereotype.Service;

/**
 * 离线启发式评分：不调用外部模型，按判题结果与代码静态特征估算质量分。
 *
 * <p>部署时若把 {@code AI_PROVIDER} 设为 {@code heuristic}（无密钥、无外网依赖），
 * 由本类给出分数，避免落到“AI 评分调用失败 / 暂不可用”。
 *
 * <p>结论是保守的估算值，只用于没有接入真实模型时的兜底展示；最终分仍以人工评分为准。
 */
@Service
public class HeuristicAssessmentAiProvider implements AssessmentAiProvider {
  private static final String MODEL = "heuristic-assessment";
  private static final int MAX_CODE_FOR_BONUS = 4000;

  @Override
  public AssessmentAiScore evaluate(AssessmentAiInput input) {
    var judge = input.judgeResult();
    int base =
        switch (judge.verdict()) {
          case ACCEPTED -> 82;
          case WRONG_ANSWER -> 55;
          case TIME_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED -> 40;
          case RUNTIME_ERROR -> 20;
          case COMPILATION_ERROR -> 10;
          case INTERNAL_ERROR -> 0; // 已在上面提前返回，这里只是穷尽分支
        };

    if (judge.verdict() == io.github.dream07code.innovationspace.assessment.OjVerdict.INTERNAL_ERROR)
      return new AssessmentAiScore(0, "判题服务内部错误，未产生可评估的运行结果", MODEL);

    String code = input.code();
    var notes = new java.util.ArrayList<String>();
    int bonus = 0;

    if (judge.totalTestCases() > 0 && judge.passedTestCases() == judge.totalTestCases()) {
      bonus += 6;
      notes.add("全部 " + judge.totalTestCases() + " 个测试点通过");
    } else if (judge.passedTestCases() > 0) {
      notes.add("通过 " + judge.passedTestCases() + "/" + judge.totalTestCases() + " 个测试点");
    } else {
      notes.add("没有测试点通过");
    }

    if (code.length() <= MAX_CODE_FOR_BONUS) {
      bonus += 2;
      notes.add("代码长度 " + code.length() + " 字符");
    } else {
      notes.add("代码偏长（" + code.length() + " 字符）");
    }

    if (hasComment(code)) {
      bonus += 3;
      notes.add("含注释");
    }
    if (hasDebugResidue(code)) {
      bonus -= 5;
      notes.add("疑似保留调试输出或未完成标记");
    }

    int score = Math.max(0, Math.min(100, base + bonus));
    String reason = "按判题结果与代码特征估算：" + String.join("，", notes);
    if (reason.length() > 1000) reason = reason.substring(0, 1000);
    return new AssessmentAiScore(score, reason, MODEL);
  }

  private static boolean hasComment(String code) {
    for (String line : code.split("\n")) {
      String text = line.trim();
      if (text.startsWith("//") && text.length() > 2) return true;
      if (text.startsWith("#") && !text.startsWith("#include") && !text.startsWith("#!")
          && text.length() > 1) return true;
      if (text.contains("/*") && text.contains("*/")) return true;
    }
    return false;
  }

  private static boolean hasDebugResidue(String code) {
    String lower = code.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("todo") || lower.contains("fixme") || lower.contains("\"debug");
  }
}
