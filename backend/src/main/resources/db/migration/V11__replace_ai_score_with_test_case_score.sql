-- 取消 AI 评分：自动分改为按通过的测试点计算。
-- ai_score 重命名为 auto_score；历史 AI 分数直接按测试点重算，不再保留旧模型给出的分数。
ALTER TABLE assessment_result
  RENAME COLUMN ai_score TO auto_score;

-- 旧约束只允许 HUMAN/AI/PENDING/UNAVAILABLE，必须先移除，否则下面写入 AUTO 会违反约束。
ALTER TABLE assessment_result DROP CHECK ck_assessment_result_source;

UPDATE assessment_result
SET auto_score = CASE
    WHEN total_test_cases > 0 AND technical_verdict <> 'INTERNAL_ERROR'
      THEN ROUND(passed_test_cases * 100.0 / total_test_cases)
    ELSE NULL
  END;

UPDATE assessment_result SET final_score = COALESCE(human_score, auto_score);

UPDATE assessment_result
SET score_source = CASE
    WHEN human_score IS NOT NULL THEN 'HUMAN'
    WHEN auto_score IS NOT NULL THEN 'AUTO'
    ELSE 'PENDING'
  END;

UPDATE assessment_result
SET result_status = CASE
    WHEN final_score IS NOT NULL THEN 'COMPLETE'
    ELSE 'NEEDS_REVIEW'
  END;

ALTER TABLE assessment_result
  ADD CONSTRAINT ck_assessment_result_source CHECK (score_source IN ('HUMAN','AUTO','PENDING'));

-- ai_evaluation 保留为历史表，不再产生新记录：评审已改为人工调整分数。
