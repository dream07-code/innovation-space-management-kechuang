-- V18：题库练习的运行记录。
-- 练习（practice-run）原来只判题不留痕，学习画像因此完全看不到练习活动：
-- 学生把题刷通了，画像的提交数/做题数还是 0。这里补一张只记判题结论的表。
--
-- 刻意与考核链路分开：练习不写 assessment_submission、不写 oj_judge_result、
-- 不产生 assessment_result，所以「已发布成绩均分」的口径完全不受影响。
CREATE TABLE problem_practice_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  problem_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  language VARCHAR(16) NOT NULL,
  -- 与 OjVerdict 的枚举名一致（ACCEPTED / WRONG_ANSWER / TIME_LIMIT_EXCEEDED …）；
  -- 只存名字不建枚举，新增判定时不必改表。
  verdict VARCHAR(32) NOT NULL,
  passed_test_cases INT NOT NULL,
  total_test_cases INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  -- 画像按「本人 + 题目」聚合，索引前缀就是 member_id，problem_id 紧随其后。
  KEY idx_practice_attempt_member_problem (member_id, problem_id, created_at),
  -- 删学生或删题目时练习记录跟着走，不能把删除挡成 500。
  CONSTRAINT fk_practice_attempt_problem FOREIGN KEY (problem_id) REFERENCES assessment_problem (id)
    ON DELETE CASCADE,
  CONSTRAINT fk_practice_attempt_member FOREIGN KEY (member_id) REFERENCES member (id)
    ON DELETE CASCADE,
  CONSTRAINT ck_practice_attempt_cases CHECK (
    passed_test_cases >= 0
    AND total_test_cases >= 0
    AND passed_test_cases <= total_test_cases),
  CONSTRAINT ck_practice_attempt_language CHECK (CHAR_LENGTH(language) > 0)
);
