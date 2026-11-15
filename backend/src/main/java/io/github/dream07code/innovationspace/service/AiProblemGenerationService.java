package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/**
 * AI 出题：学生按知识点 / 难度 / 题型生成一道专属题目。
 *
 * <p>生成结果必须先用参考解法跑通全部测试点才会落库；两次都不过就返回错误，
 * 不保存半成品题目。
 */
public interface AiProblemGenerationService {

  /**
   * @param request topic / difficulty / category
   * @return 与 {@code GET /assessment-problems/{id}} 同结构的题目
   */
  Map<String, Object> generate(Map<String, Object> request, SessionPrincipal actor);
}
