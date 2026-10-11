# API 文档校验记录

日期：2026-10-11；契约：`1.0.0-draft`。本记录只证明文档结构和所列契约检查，不证明业务接口已经实现或运行通过。

## 结果

- OpenAPI 3.0.3 规范校验通过，无重复 YAML key。
- 49 个 operationId 均唯一；74 个 schema；618 处本地 `$ref` 全部可解析。
- 86 个请求/响应示例（含公共错误示例）通过对应 OpenAPI schema 校验。
- 状态标记：认证、成员、日报、周报、积分和排行榜业务路径为 implemented；月评/自动移出/任务路径保持 pending。
- 请求 DTO 拒绝未知字段；普通资料 PATCH 不允许写学号；创建学生不允许写角色、密码、积分或负责方向列表。
- 非认证 POST 业务写操作均声明 Idempotency-Key；管理负责人、重置他人密码和更正学号均为学院管理员专用。
- 排行榜 DTO 不含学号、账号或联系方式；受限首次登录示例没有 refresh token。
- Markdown 本地链接和 `git diff --check` 通过；不含真实初始口令、令牌、私钥或真实学生名单。

## 独立审查与修正

一次只读契约审查指出的事项均已修正并核验：

1. **部分更新必填字段不一致**：原示例生成过程误修改了 schema 的 required 列表。修复后，可单独更新联系方式、日报收获或周报成果；只有 version（负责人另加 reason）必须携带。使用四个有效 partial payload 验证通过；仅 version 或夹带 studentNo 均被拒绝。
2. **已提交报告编辑绕过有效内容校验**：补充“合并后的完整正文”必须满足正式提交的非空白条件，失败 400 且报告、积分和任务不变。该跨资源状态校验由未来后端实现，静态 OpenAPI 不能代替运行时验证。
3. **月评未等待评分完成**：生成、确认、派任务和 Worker 移出前都必须检查评分/影响积分的复核已结清；前端可识别 409 `SCORING_PENDING`。不能仅凭版本未变就按保底分淘汰。
4. **任务标识命名**：执行响应为 TaskResponse，前端使用 `data.id` 轮询；评定详情的 `taskId` 只是指向该任务的关联字段。

本次同步已由后端契约集成测试验证业务路径和响应 schema；月评等未实现路径不作运行承诺。负责人和学生的实际资料仍待项目负责人安全提供，不产生任何数据库写入。

## 复现基础规范与示例检查

在仓库根目录执行；校验依赖放在独立临时环境，不是产品依赖：

```bash
python3 -m venv /tmp/innovation-api-check
/tmp/innovation-api-check/bin/pip install \
  openapi-spec-validator==0.9.0 openapi-schema-validator==0.9.0 PyYAML==6.0.3
/tmp/innovation-api-check/bin/python -m openapi_spec_validator docs/api/openapi.yaml
/tmp/innovation-api-check/bin/python - <<'PY'
from pathlib import Path
import yaml
from openapi_schema_validator import OAS30ReadValidator, OAS30WriteValidator

spec = yaml.safe_load(Path('docs/api/openapi.yaml').read_text())
count = 0
def check(content, validator):
    global count
    for media in content.values():
        examples = ([media['example']] if 'example' in media else [])
        examples += [item['value'] for item in media.get('examples', {}).values()]
        for value in examples:
            validator(spec).evolve(schema=media['schema']).validate(value)
            count += 1

for item in spec['paths'].values():
    for operation in item.values():
        if 'requestBody' in operation:
            check(operation['requestBody']['content'], OAS30WriteValidator)
        for response in operation['responses'].values():
            if '$ref' not in response:
                check(response.get('content', {}), OAS30ReadValidator)
for response in spec['components']['responses'].values():
    check(response['content'], OAS30ReadValidator)
print(f'{count} examples validated')
PY
git diff --check
```

后续新增路径、字段或示例后须重新运行校验；本次静态通过不替代未来的鉴权、幂等、事务、并发和日期边界集成测试。
