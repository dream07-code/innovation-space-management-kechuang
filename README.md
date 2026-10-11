# 学院科创空间管理系统

用于学院科创空间的分方向人员管理、日报周报提交、AI 内容评分、积分排行和成员资格管理。

## 项目状态

代码位于 `backend/`。基础工程已扩展数据库会话认证、首次改密、角色/方向权限、学生及负责人管理、学号更正/密码重置与脱敏审计，仍不是完整业务管理系统。真实账号资料没有写入数据库或Git。

启动、受控迁移、管理员初始化、环境变量和测试方式见 [后端说明](backend/README.md)。日报周报、AI评分、积分排行、月评及自动移出仍待后续实现。

## 需求范围

- 七个方向的人员信息增删改查：AI 全栈、鸿蒙、网络安全、C++、大数据、人工智能、项目创意设计。
- 学生由所属方向负责人或学院管理员添加，学生端不允许自行注册。
- 日报与周报提交，接入大模型按内容质量评定 1–3 分。
- 积分流水、方向排行榜和全空间排行榜。
- 每月末位成员评定，以及连续两周未提交日报的自动移出。
- 管理员复核、规则配置和操作留痕。
- Knife4j 接口文档与 OpenAPI 契约，方便前后端分离开发及联调。

## 文档

- [需求文档 v0.3](docs/requirements.md)
- [后端基础工程技术设计，已确认](docs/superpowers/specs/2026-10-11-backend-bootstrap-design.md)
- [后端基础工程实施计划及验收清单](docs/superpowers/plans/2026-10-11-backend-bootstrap.md)
- [后端基础工程验收记录](docs/superpowers/reports/2026-10-11-backend-bootstrap-verification.md)
- [认证与人员管理设计](docs/superpowers/specs/2026-10-11-auth-member-management-design.md)
- [认证与人员管理实施计划](docs/superpowers/plans/2026-10-11-auth-member-management.md)
- [认证与人员管理验收及审查记录](docs/superpowers/reports/2026-10-11-auth-member-management-verification.md)
- [前后端共用API契约](docs/api/README.md)

需求文档区分了用户已明确要求、建议默认规则和待确认事项。建议默认规则不代表已经获得业务方确认。

## 数据与仓库安全

- 不向仓库提交真实学生名单、学号、联系方式、日报原文或其他个人信息。
- 不提交数据库备份、访问令牌、大模型 API 密钥和真实环境配置。
- 后续开发使用虚构测试数据；部署环境中的密钥通过环境变量或密钥管理服务提供。
- 暂未选择开源许可证；是否公开仓库及授权方式由项目负责人确定。
