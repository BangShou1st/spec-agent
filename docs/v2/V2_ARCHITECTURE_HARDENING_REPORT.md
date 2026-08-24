# V2 Architecture Hardening Sprint Report

日期：2026-08-24
基线：`cd5076c`
执行顺序：H4 → H5 → H1 → H3
状态：完成；未 push。

## 结果摘要

| 项目 | 结果 | Commit |
|---|---|---|
| H4 Floating Layout | 生产布局查询改用语义 `data-layout-role`，不再依赖测试 selector | `0225e48` |
| H5 Legacy Regeneration | 删除 `RouteService.regenerateFromNode`，测试 caller 迁移到 V2 路径 | `163215d` |
| H1 Action Family | Java runtime dispatch 在边界解析为 `ActionFamily`，switch 穷举且无静默 default | `f681c53` |
| H3 Lineage | ContextBuilder 复用 authoritative resolver；两个 read-model walker 合并 | `815c31b` |
| 开发端口 | `start-dev.bat` 检测占用端口并递增选择空闲端口，不停止已有进程 | `fcf258c` |

## H4

- `GraphWorkspaceQueryService` 对应的 floating layout 逻辑改用 `data-layout-role`：`graph-node`、`start-placeholder`、`toolbar`。
- 保留既有 `data-test`，但生产障碍物和交互障碍物计算不再读取测试选择器。
- 不改变 layout 算法、持久化格式或 workspace store。
- Playwright 配置支持 `PLAYWRIGHT_PORT`、`PLAYWRIGHT_BACKEND_PORT` 与 `PLAYWRIGHT_BASE_URL`。

## H5

- 删除 `RouteService.regenerateFromNode` 及其旧的 ContextBuilder 依赖。
- route lifecycle、graph workspace、requirement state、lineage API、scripted isolation 测试 caller 均已迁移。
- 现代路径保持为 `commitReplacementFromNode` + `ContextBuilder.buildForRegenerate`；未删除后者。
- backend `src/main` 与 `src/test` 的 legacy method 引用计数为 0。调查证据文档仍保留该名称的历史记录，不属于运行时代码。

## H1

- `AdvisorPolicyEngine`、`ProposalActionExecutor`、`ProposalAcceptanceService`、`NodeQueryService`、`ReplacementCycleService` 在 dispatch boundary 使用 `ActionFamily.fromCode(...)`。
- 相关 switch 改为 enum switch，显式覆盖九个 family；未知 family 在 boundary 失败，不再被 `default` 静默吞掉。
- `ActionFamilyContractIntegrationTest` 改为遍历 `ActionFamily.values()`，并对每个 family 验证 auto-execute、可确认执行或显式拒绝的闭集结果。
- 未改 Python family 定义、registry 或 H1 之外的中央元数据抽象。

## H3

- `ContextBuilder` 不再自行跟随 parent pointer；所有 context lineage 解析委托给 `RouteHistoryResolver.resolveLineage`，因此 cycle、missing node、depth overflow 不会再被静默截断。
- 新增 `ReadModelLineageWalker`，由 `GraphWorkspaceQueryService` 与 `RouteLineageQueryService` 共同使用；两者继续各自处理 project ownership、route root/tip 和 API 错误映射。
- 共享 walker 明确保留 10,000-node 上限，并覆盖 cycle、missing node、10,000/10,001 边界。
- H3 没有把 `RouteService.lineageContains` 或 `StaleContextChecker` 的部分语义强行合并；它们仍分别承担 containment 和 stale-context 校验职责。

H3 前调查到的 4 个完整 walker 已收敛为 2 个完整实现：runtime 的 `RouteHistoryResolver` 与 read-model 的共享 `ReadModelLineageWalker`；ContextBuilder、两个 read-model 服务不再各自复制遍历逻辑。两个部分 walker 保留在其专属语义边界内。

## H2 与 workspaceStore 决策

- H2：`DEFERRED`。未抽取 `ProposalExecutionCoordinator`，未修改 Answer/Decision cycle 的闭包结构；H1 对 `NodeQueryService` 的 enum boundary 改造不计入 H2 实施。
- `frontend/src/stores/workspaceStore.ts`：NO CHANGE。相对 baseline 无 diff。

## 端口启动行为

`start-dev.bat` 默认探测 backend `8080`、frontend `5173`。若端口处于 Listen 状态，脚本逐个递增到下一个空闲端口，并将实际 backend 端口注入 Vite proxy 与 Spring Boot；脚本不再杀掉已有进程，也会打印最终 URL。

实际 E2E 验证使用 frontend `5175`、backend `8081`，以验证端口可配置与占用规避路径；未停止其他已有开发服务。

## 验证记录

| 命令 | 结果 |
|---|---|
| `backend\\gradlew.bat compileTestJava` | PASS |
| H1 targeted policy/validator/executor/acceptance/cross-language tests | PASS |
| H3 targeted context/resolver/walker/graph/route tests | PASS |
| `backend\\gradlew.bat test` | PASS |
| `backend\\gradlew.bat test --tests com.specagent.architecture.ArchitectureTests --tests com.specagent.architecture.AgentBoundaryArchitectureTests` | PASS |
| `frontend\\npm run typecheck` | PASS |
| `frontend\\npm run test:unit -- --run` | PASS，37 files / 328 tests |
| `PLAYWRIGHT_PORT=5175 PLAYWRIGHT_BACKEND_PORT=8081 npm run test:e2e -- e2e/floating-workspace.spec.ts` | PASS，2/2 |

首次完整 floating E2E 中第一个几何断言出现时序超时、第二个通过；单测重跑和随后完整重跑均通过 2/2，故最终结果按最后一次完整运行记录。

## 过程备注

- 仓库要求引用的 `docs/ANTI_OVERFITTING.md` 与 `docs/DEVELOPMENT_WORKFLOW.md` 在 baseline 中不存在；本 sprint 未伪造这两个文件。
- baseline 已存在的未跟踪调查、验收、截图等文件均保留，未执行清理或 broad staging。
