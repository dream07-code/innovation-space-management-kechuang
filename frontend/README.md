# frontend

AI 编程实训助手前端：Vue 3 + Vite + TypeScript + Element Plus，接口按 [`docs/api/openapi.yaml`](../docs/api/openapi.yaml) 契约调用。

## 目录

```text
src/api/         HTTP 客户端、令牌存储、错误映射、按模块拆分的接口封装
src/views/       oj/（考核、题库、代码提交、阅卷、成绩）、member/（日报周报、积分）、admin/、leader/
src/layouts/     应用外壳：品牌侧栏、顶栏、返回导航
src/config/      按角色生成的导航菜单
src/stores/      Pinia：会话、方向
src/mocks/       仅开发用的 MSW 虚构数据，不是产品功能的一部分
```

## 开发

```bash
pnpm install
cp .env.example .env.local     # 按需修改，含凭据的文件不要提交
pnpm dev                       # http://localhost:5173
```

后端显式关闭了 CORS，浏览器不能跨域直连，因此 `/api` 与 `/actuator` 必须经 Vite dev proxy 转发到后端（默认 `http://127.0.0.1:8080`，可用 `VITE_DEV_PROXY_TARGET` 覆盖）。

**数据一律来自真实后端**：开发环境默认不启用 MSW，`/api` 请求直接经 dev proxy 转发；`src/mocks` 里的虚构数据只有显式设置 `VITE_USE_MOCK=true` 才会启用，仅用于脱离后端做纯前端调试。

## 构建与检查

```bash
pnpm build        # 类型检查 + 生产构建
pnpm type-check
pnpm test         # Vitest 单元测试
pnpm lint
```

## 约定

- **前端隐藏按钮不是权限控制**：路由守卫只负责界面状态，角色与方向隔离由后端逐对象校验（见 `src/router/index.ts` 顶部说明）。
- 接口类型由契约生成：`pnpm gen:api` 更新 `src/api/types.gen.ts`，不要手改生成文件。
- 契约里未实现的接口在开发环境会真实失败，不做静默兜底——宁可看见错误，也不返回假成功。
