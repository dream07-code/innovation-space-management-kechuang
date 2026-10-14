# OJ Judge0 测试点与做题工作台验证报告

## 范围

本轮完成正式考核隐藏测试点、题库练习测试点、JSON 批量导入、Judge0/Mock 逐测试点结果、练习判题和双栏做题工作台。正式考核与练习使用独立测试点集合；隐藏测试点、原始 stdout/stderr 和 Judge0 Token 不进入普通题目、提交或结果响应。

## 验证结果

- 后端 focused integration：`AssessmentSchemaIT`、`AssessmentSubmissionHttpIT`、`AssessmentEvaluationHttpIT`、`AssessmentManagementHttpIT`、`AssessmentResultHttpIT`、`AssessmentReviewHttpIT`、`OJApiContractIT`、`OpenSourceOjAdapterIT`、`OjAdapterTest` 全部通过。
- 后端单元测试：`mvn test` 通过。
- 后端完整验收：`mvn clean verify` 通过，115 个集成/单元测试全部通过。
- 验收时发现 `AuthenticationHttpIT` 的固定窗口限流测试在跨越 60 秒边界时偶发返回 401；测试已改为避开窗口边界，重新完整验收通过。
- V8/V9 迁移兼容性已同步修正 `FlywayMigrationIT`、`ReportPointsMigrationIT` 和 `JarBoundaryIT` 的迁移数量与 JAR 资源边界断言。
- 前端：27 个测试文件、89 个用例通过；`type-check`、`lint`、`build` 通过。
- 三份 OpenAPI 副本字节一致；V8/V9 迁移和 `app.migration.required-version=9` 已验证。
- Mock 模式支持题目导入、练习测试点读取、练习运行和逐测试点安全摘要。

## Judge0 实测

已拉取 `judge0/judge0:latest`，启动独立 PostgreSQL/Redis 和 privileged Judge0 容器，`GET /about` 返回 Judge0 `1.13.1`。当前开发主机使用 cgroup v2，Judge0 isolate 作业返回 `INTERNAL_ERROR`，错误为无法创建 `/box/script.py`。因此增加 `OJ_RLIMIT_FALLBACK=true` 兼容开关：让 Judge0 1.13.1 使用逐进程/线程 rlimit，避开旧 isolate 的 cgroup v1 路径。已在本机 cgroup v2 环境用真实 Judge0 提交 `print(1)` 返回 `Accepted`。该模式降低资源隔离强度，正式启用前仍需在目标服务器完成超时、内存、编译错误和无测试点样例验收；若要求完整 cgroup v2 资源控制，应另行构建基于 Isolate 2.7 的镜像。

## 上线约束

Judge0 必须独立网络、独立数据库/Redis、仅服务端访问，并通过密钥管理注入 Token。正式考核隐藏测试点和原始判题输出不得返回浏览器；练习测试点只通过学生可见的练习接口返回。
