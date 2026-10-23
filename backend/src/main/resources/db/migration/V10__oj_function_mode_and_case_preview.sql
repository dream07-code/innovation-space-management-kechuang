-- 函数式判题（力扣风格）：题目声明函数签名，判题侧生成调用桩。
ALTER TABLE assessment_problem
  ADD COLUMN judge_mode VARCHAR(16) NOT NULL DEFAULT 'STDIO' AFTER allowed_languages,
  ADD COLUMN function_name VARCHAR(64) NULL AFTER judge_mode,
  ADD COLUMN parameter_types JSON NULL AFTER function_name,
  ADD COLUMN return_type VARCHAR(32) NULL AFTER parameter_types,
  ADD CONSTRAINT ck_assessment_problem_judge_mode CHECK (judge_mode IN ('STDIO','FUNCTION'));

-- 逐测试点结果保留安全截断的输入/实际输出/期望输出，用于给学生展示一个参考测试点。
ALTER TABLE oj_test_case_result
  ADD COLUMN input_preview VARCHAR(2000) NULL AFTER safe_error,
  ADD COLUMN actual_output VARCHAR(2000) NULL AFTER input_preview,
  ADD COLUMN expected_output VARCHAR(2000) NULL AFTER actual_output;
