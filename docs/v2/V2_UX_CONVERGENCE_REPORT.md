# Spec Agent V2 UX Convergence Sprint Report

日期：2026-08-25  
范围：V2 Graph-first Workspace UX convergence

## 1. Executive Summary

本轮完成了 V2 UX gap verification，并补齐 4 个此前仍不完整的收口项：

1. Shared Node 头部改为友好的路线标签，不再展示数据库式的路线数量摘要。
2. Question Node 选项改为“单选 + 标签 + 影响说明”的纵向结构。
3. Fork / 起草问题增加真实 AgentRun 的 pending projection：先绑定已存在的 canonical Route，再显示 AgentRun 运行中的虚拟 pending card；缺少 Route identity 时显式失败，不创建临时投影，失败保留可重试入口，成功刷新后由 canonical Node 替换。
4. Inspector 增加由用户明确发起的 semantic relation 创建入口；Canvas 仍只展示 continuation edges。

同时修复浮动窗口在 fit/locate 动画结束后可能覆盖当前 Node，以及拖动/缩放后与工具条互相拦截的问题。未改变后端 Graph canonical API、Runtime authority 或历史不变量。

## 2. Gap Matrix

| UX / Runtime 要求 | 本轮结果 | 主要落点 |
|---|---|---|
| Node 层级与最新节点 | 保留并验证；Latest / Current 区分明确 | `GraphQuestionNode.vue`, `graphProjection.ts` |
| Shared Node 身份 | 保留单一 canonical identity；只扩展 visual instance | `graphProjection.ts` |
| 友好路线标签 | 完成；去除 `共享 · N 条路线` 展示 | `GraphQuestionNode.vue`, `GraphKnowledgeNode.vue` |
| Shared Answer | 按 route scope 展示，不合并为全局答案 | `graphProjection.ts`, `GraphQuestionNode.vue` |
| 选项纵向布局 | 完成；标签和影响说明垂直排列 | `style.css` |
| Hover / Focus actions | 保留 hover 与 keyboard focus toolbar | `style.css`, graph node components |
| Answer 非阻塞 | 保留 Canvas / Route / History / Focus 可用；仅重复提交本地锁定 | `workspaceStore.ts`, `WorkspaceView.vue` |
| Runtime phase copy | 完成；phase 映射到用户可见文案，不展示 CoT | `phaseCopy.ts`, `WorkspaceView.vue` |
| Fork pending card | 完成；只接受真实 Route identity，AgentRun 失败可重试，成功由 canonical refresh 收敛 | `graphProjection.ts`, `workspaceStore.ts`, `GraphQuestionNode.vue` |
| Route 立即出现 | 保留先创建 Route、后启动 AgentRun 的顺序 | `workspaceStore.ts` |
| Focus / Active 分离 | 保留；Focus 只改变阅读上下文，不隐式 Activate | `graphUiStore.ts`, existing route E2E |
| Free Node / Draft | 保留 0 模型调用的草稿入口；空项目 Start Placeholder 事件已接通 | `GraphCanvas.vue`, `WorkspaceView.vue` |
| Continuation edge | Canvas 仍只投影 continuation；semantic relation 不污染默认箭头 | `graphProjection.ts`, `NodeInspector.vue` |
| Semantic relation | 完成明确创建入口；写入走 canonical API | `NodeInspector.vue`, `workspaceStore.ts` |
| Contextual AI | Node toolbar / Inspector 均可打开 contextual query；事件同时校验 canonical Node 与被点击 visual instance | `GraphCanvas.vue`, `WorkspaceView.vue`, `NodeInspector.vue` |
| Undo / Redo | 保留 operation history compensation，不删除旧 Answer | existing Graph command APIs / store |
| Reveal / layout | 保留；浮窗不改变 Graph Canvas 尺寸 | `WorkspaceView.vue`, floating layout |
| Floating workspace | 完成 auto reflow、动画结束后二次避让、手动窗口工具条避让 | `floatingWindowLayout.ts`, `FloatingWindow.vue`, `WorkspaceView.vue` |
| Accessibility | route chip 使用完整 label title；按钮有可见文案、状态与重试入口 | graph components / CSS |

## 3. Node UX

- Canonical Node 仍是 Workspace Unit，Question / Knowledge 不互相伪装。
- Latest、Current、Historical 状态继续独立表达。
- Shared Node 只保留一个 canonical identity；多路线通过 route membership 和 route-scoped answer projection 表达。
- Question Node 的历史操作仍通过 hover/focus toolbar 暴露，包含 Fork、重新回答、重新生成、问 AI。
- Knowledge Node 同样提供问 AI 入口，不新增第二套 Agent 类型。

## 4. Runtime / Pending UX

pending card 是浏览器 projection，不写入半成品 Node；它只能挂在真实 canonical Route 上：

```text
已有 canonical Route -> 创建 AgentRun -> PENDING/RUNNING pending card
                                  -> FAILED + 重试
                                  -> canonical refresh -> 真实 Node 替换 pending card
```

`DRAFT_QUESTION` 的 Runtime contract 要求 active Route 必须存在；前端拿不到该 identity 时进入显式 error/recovery path，绝不使用空字符串或其他 sentinel。运行状态来自 AgentRun polling；前端只做状态投影。失败时保留稳定的用户可见错误和 Retry，不伪造成功 Node。Answer runtime phase 同样展示真实阶段映射。

## 5. Route / Shared Node UX

- Route label 优先于 UUID / 数据库编号。
- Focus、Active、Visibility 继续是三个独立概念。
- Shared Node 没有 Focus 时不会偷偷使用 Active route 作为回答上下文。
- Route-specific Answers 继续按路线显示；不同路线不会被压成全局单值。

## 6. Free Node / Continuation / Semantic Relation

- 空项目可以先创建用户草稿想法，保持 0 模型调用。
- 从任意 Node continuation 仍追加新分支，不改写既有历史。
- Canvas 默认箭头只代表 continuation。
- Semantic relation 必须由用户在 Inspector 选择目标 Node 和关系类型后创建；前端不根据邻近位置或标签推断关系。

## 7. Contextual AI / History

任意真实 canonical Node 都可以从 Node toolbar 或 Inspector 发起 contextual query。GraphCanvas 事件明确携带 canonical Node id 与被点击的 visual instance；WorkspaceView 负责校验 visual key、写入 selection 并打开 Inspector，NodeQuery 最终只使用 canonical Node id。pending virtual node（`pending:runId`）没有问 AI action，store 也拒绝将其作为 query anchor。查询沿现有 Node / route lineage 上下文执行，不复制全局聊天上下文，也不直接修改 Graph。Undo / Redo 继续调用 operation history compensation，旧 Node / Answer 不被物理删除。

## 8. Floating Workspace / Accessibility

- fit/locate 动画结束后再次测量浮窗，避免窗口依据过时 viewport 位置停在当前 Node 上。
- Auto layout 会在可用空间不足时收缩窗口宽度或高度。
- 手动拖动或缩放后，浮窗自动避开 toolbar corridor，toolbar 与窗口控件都保持可操作。
- route chip 提供完整路线名 title；状态 badge 使用文字而非只依赖颜色；失败状态提供 Retry。

## 9. Backend Changes

本轮没有新增或修改后端代码、数据库 migration 或 API contract。现有 canonical Graph read model、Graph command、AgentRun、relation API 已足够支撑本轮 UX；新增行为集中在前端 projection、store orchestration 和浏览器布局层。

## 10. Architecture Integrity Check

- Runtime 仍是唯一事实源；前端 pending card 是可丢弃 projection。
- pending projection 的 `routeId` 必须来自 active/canonical route 或 AgentRun read response；缺失时不生成 pending card。
- Active Route 继续决定 runtime mutation scope；Focus Route 只决定 reading context。
- Shared Node 保持单一 canonical identity，Answers 保持 route-scoped immutable records。
- Contextual AI 不再依赖 GraphCanvas 先产生 selection side effect；selection orchestration 由 WorkspaceView 显式完成，visual key 只用于定位被点击实例，query anchor 仍为 canonical Node。
- Model 仍只能提出 proposal / action，Runtime 负责 validation、policy、persistence 和 execution。
- 默认 Advisor / confirmation boundary 未改变；本轮没有扩大 Autonomous 权限。
- 没有引入全局 chat context、隐式 route fallback、私有 CoT 或 fake production path。

## 11. Acceptance Results

| 验收项 | 结果 | 证据 |
|---|---:|---|
| A1 空项目 / 草稿 Node | PASS | full E2E #1、前端单测 |
| A2 Question / Answer / Latest | PASS | full E2E #1/#2/#20/#24 |
| A3 Shared Node / route-scoped Answer | PASS | full E2E #8/#9/#25 |
| A4 Focus / Active / visibility | PASS | full E2E #15/#16/#23/#25 |
| A5 Fork / pending / history | PASS | `workspaceStore.spec.ts`, `graphProjection.spec.ts`, targeted `fork.spec.ts` |
| A6 Continuation / semantic relation boundary | PASS | Graph projection + Inspector unit tests；Canvas route E2E |
| A7 Non-blocking runtime feedback | PASS | full E2E #1/#20/#21；store tests |
| A8 Contextual AI | PASS | `WorkspaceView.spec.ts`, targeted `contextual-ai.spec.ts`；NodeQuery 使用 canonical node id |
| A9 Undo / Redo / compensation | PASS | backend GraphCommand / UndoRedo integration tests |
| A10 Floating workspace / responsive layout | PASS | full E2E #4/#5/#17/#18；floating layout tests |
| A11 Spec / artifact runtime phase | PASS | full E2E #24；phase copy tests |
| A12 Accessibility / actionable failure states | PASS | full E2E 25/25；GraphQuestionNode / Workspace unit tests |

## 12. Verification Commands

```text
frontend: npm run typecheck
frontend: npm run test:unit -- --run
frontend: npm run build
frontend: npm run test:e2e                         # 25 passed
backend:  .\gradlew.bat test                      # BUILD SUCCESSFUL
backend:  targeted GraphWorkspace / UndoRedo / GraphCommand tests
```

本轮 E2E 使用临时端口：frontend `5174`、backend `8081`；默认 `start-dev.bat` 已具备“8080/5173 被占用时向上选择下一个可用端口”的启动逻辑，本轮未重复修改该脚本。

本次 final review blocker recheck 只运行 targeted 范围：frontend typecheck PASS；受影响 Vitest 6 个 spec、148/148 tests PASS；`contextual-ai.spec.ts` 1/1、`fork.spec.ts` 4/4、`shared-focus.spec.ts` 1/1 PASS。未重新运行完整 E2E 25/25。

## 13. Remaining UX Debt

未纳入本轮收口的项目仍按 V2 计划保留：更完整的 artifact/resource 专用交互、关系可视化可选层、复杂图的大规模虚拟化，以及更细的多语言 accessibility audit。这些不影响本轮 canonical Graph、route scope、Runtime authority 和 UX convergence 验收。

## 14. Git / Workspace Handoff

- 未创建 commit，未 push。
- 保留工作区中原有的未跟踪验收材料、截图和调查文件，未执行清理操作。
- 本轮源码改动集中在 `frontend/src`；报告位于本文件。
