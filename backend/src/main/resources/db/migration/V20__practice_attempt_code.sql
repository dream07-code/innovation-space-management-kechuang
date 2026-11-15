-- V20：练习记录保存提交的代码。
--
-- 错题本要能「重做」：回到做题页时把上次写的代码带回编辑器，还要让 AI 说清错在哪。
-- V18 只记了判题结论，没有代码，两个能力都做不了，这里补一列。
--
-- 只存原始代码文本，不参与判题与计分；练习不是考核提交，历史数据允许为空。
ALTER TABLE problem_practice_attempt
  ADD COLUMN code MEDIUMTEXT NULL AFTER language;
