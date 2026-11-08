package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/**
 * AI 批改报告：把一个已判题的提交，组装成「错因解析 + 改进建议 + 推荐下一题」。
 *
 * <p>复用三类既有数据：评测提交与 Judge0 判定（含失败用例）、题目的题干与难度、
 * 阅卷汇总里的客观题题干（{@code questions}，仅管理角色）。AI 部分复用报告评分的模型配置。
 */
public interface SubmissionReportService {

  Map<String, Object> report(long submissionId, SessionPrincipal actor);
}
