-- 考核积分奖励：考核可选开启积分，关闭时按成绩比例发放（第一名 20 分封顶）。
ALTER TABLE assessment
  ADD COLUMN points_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER status;

ALTER TABLE point_ledger DROP CHECK ck_points_source;
ALTER TABLE point_ledger
  ADD CONSTRAINT ck_points_source CHECK (source IN ('SUBMISSION_BASE','AI_ADJUSTMENT','MANUAL_ADJUSTMENT','REVERSAL','ASSESSMENT_REWARD'));
