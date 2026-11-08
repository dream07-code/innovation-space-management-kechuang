package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/**
 * AI 助教对话：学生就一次判题结果连续追问，至少覆盖「这道题为什么错」。
 *
 * <p>上下文固定为题目 + 学生代码 + 本次 Judge0 判题结果，三者都由服务端按提交 id 从库里取，
 * 不接受前端传入。对话历史由前端逐轮带回，服务端不保存会话——因此不新增数据库结构。
 */
public interface SubmissionTutorService {

  /**
   * 回答一轮提问。
   *
   * @param request 含 {@code message}（本次问题）与可选 {@code history}（历史对话）
   * @return 含 {@code available}、{@code reply}、{@code model}、{@code promptVersion}；AI 不可用时
   *     仍返回 200，{@code available=false} 并附可展示的 {@code error}
   */
  Map<String, Object> chat(long submissionId, Map<String, Object> request, SessionPrincipal actor);
}
