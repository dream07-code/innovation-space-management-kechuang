package io.github.dream07code.innovationspace.service;

/** 考核积分奖励：考核关闭时按成绩比例一次性发放（详见 spec 2026-10-26）。 */
public interface AssessmentPointsRewardService {
  /**
   * 结算一场考核的积分奖励。points_enabled 关闭、全场最高分为 0 时直接返回；
   * 幂等键绑定考核与成员，重复结算不重复发放。必须在 close 的事务内调用。
   */
  void settle(long assessmentId);
}
