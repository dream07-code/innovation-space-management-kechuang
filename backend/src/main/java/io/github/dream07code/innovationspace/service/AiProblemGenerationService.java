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
   * @param request topic / difficulty / category / requestId（可选，用于取消）
   * @return 与 {@code GET /assessment-problems/{id}} 同结构的题目
   */
  Map<String, Object> generate(Map<String, Object> request, SessionPrincipal actor);

  /**
   * 取消一次正在进行的生成。
   *
   * <p>只给该次请求打标记：生成线程会在重试前、写库前、写库后检查标记，
   * 命中就不落库（已落库则立刻回滚），所以取消不会在题库里留下题目。
   *
   * @param request requestId
   */
  Map<String, Object> cancel(Map<String, Object> request, SessionPrincipal actor);
}
