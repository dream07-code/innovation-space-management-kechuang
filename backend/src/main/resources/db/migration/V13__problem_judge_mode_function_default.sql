-- 新建题目只允许函数传参判题：列默认值从 STDIO 收敛为 FUNCTION。
-- CHECK 约束仍保留 STDIO/FUNCTION 双值，存量命令行读写题目继续可判题。
ALTER TABLE assessment_problem
  MODIFY judge_mode VARCHAR(16) NOT NULL DEFAULT 'FUNCTION';
