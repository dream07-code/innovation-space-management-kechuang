package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/**
 * 错题本：学生本人做错过的题，以及「错在哪」和「上次的代码」。
 *
 * <p>数据来源是两类真实判题记录：正式考核提交（assessment_submission）与题库练习
 * （problem_practice_attempt）。同一道题只保留最近一次尝试——如果最近一次已经通过，
 * 说明这题已经补上了，不再出现在错题本里。
 */
public interface MistakeBookService {

  /** 错题列表，按上次尝试时间倒序。 */
  Map<String, Object> list(SessionPrincipal actor);

  /** 最近一次做错的原因（AI 批改），按题目 id 现取现算并缓存。 */
  Map<String, Object> cause(long problemId, SessionPrincipal actor);

  /** 最近一次做错时提交的代码，供「重做」回填编辑器。 */
  Map<String, Object> lastCode(long problemId, SessionPrincipal actor);
}
