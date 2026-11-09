package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/**
 * 学习画像：把本人已有的提交、判题与已发布成绩聚合成一份快照。
 *
 * <p>读取时重新聚合并覆盖写入快照表，因此画像永远和最新的判题结果一致；
 * 没有任何做题记录时返回空状态，不生成、也不展示推断出来的薄弱点或推荐。
 */
public interface LearningProfileService {
  Map<String, Object> profile(SessionPrincipal actor);
}
