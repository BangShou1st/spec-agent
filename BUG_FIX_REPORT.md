# Spec Agent 当前验收阶段缺陷修复报告

日期：2026-08-24

## 结论

本轮验收缺陷已修复并完成回归。BUG-01～BUG-05 均有对应实现或验收修正；BUG-02 在修复前先完成了旧 500 的独立复现和链路核对，没有把未证实的竞态假设直接实现为重试。

## 缺陷处理

### BUG-01：浮动窗口布局和指针阻断

- 删除了 Inspector 的固定 `x=836` 依赖；默认值只表示布局偏好，实际位置由 `computeAutoFloatingWindowLayout` 根据当前工作区、节点、占位符、工具栏和另一浮窗的 DOM 矩形动态计算。
- 自动布局在窗口尺寸变化、工作区 `ResizeObserver`、图刷新、节点位置变化和窗口打开/恢复后重新计算，并使用 `requestAnimationFrame` 合并更新。
- 自动布局会优先避免当前交互节点、起始占位符、工具栏以及另一浮窗；小视口会在边界内缩小可用窗口尺寸。
- 拖拽/缩放明确写入 `positionMode: manual`；重置回到 `auto`。旧版 v2 未带模式字段时，仅修复已知旧默认坐标，保留用户非默认旧位置。
- 回归覆盖初始布局、重置、常规视口、900×700 小视口、拖拽、缩放、刷新恢复、窗口互不遮挡、画布尺寸不变和实际输入可操作性。

### BUG-02：旧 500 的证据与修复边界

修复前的受控复现：

1. 新建项目并归档当前路线后，`POST /api/v1/projects/{id}/nodes` 创建根草稿稳定返回 HTTP 500，响应为 `INTERNAL_ERROR / An unexpected internal error occurred`。
2. 同一归档状态下，`POST /api/v1/projects/{id}/agent-runs` 的 `ANSWER_TIP` 也稳定返回同样的 HTTP 500。
3. 并发归档与创建节点的一次受控运行中，归档返回 200、创建节点返回 201；这不足以证明报告中的竞态假设。
4. 旧日志最早落在 `GraphCommandService.createRootDraftNode`，随后读取 route 并回滚，异常被统一包装成 500；没有足够证据把根因归为 `AnswerCycleRunController.getActiveRouteId` 的竞态。

实际修复：

- Graph 根草稿命令通过既有的命令执行错误边界，把运行时状态冲突映射为结构化 HTTP 409 `RUNTIME_CONFLICT`。
- Agent run 命令在执行前检查 active route；无 active route 返回 HTTP 409 `NO_ACTIVE_ROUTE`，检查与实际执行之间若状态改变，也由同一边界转换为结构化冲突。
- 保留幂等 replay 的既有语义；`REGENERATE_NODE` 不被错误地要求 active route。
- 没有加入 sleep、盲目重试、catch-ignore 或吞掉异常。

新增后端集成回归：归档路线后根草稿和 `ANSWER_TIP` 都断言 409 及错误码，确保不再回到 500。

### BUG-03：VueFlow Shift 警告

VueFlow 1.48.2 的 `selectionKeyCode` validator 接受 Boolean/null，而内部默认值已是 Shift 字符串。移除了无效的显式 `:selection-key-code="'Shift'"`，保留 `multi-selection-key-code="['Meta', 'Control']"`；多选拖拽回归通过。

### BUG-04：草稿节点真实交互链路

验收不再用全局泛化的 `textarea/contenteditable` 查询。当前知识草稿流程为：工具栏 `add-idea` → 悬停 `edit-draft` → 节点内 `draft-text` textarea → 保存 → `knowledge-text`。Playwright 与 v3 验收均按该语义选择器验证，未使用 force pointer 操作。

### BUG-05：Fork eligibility

验收拆成正反两例：知识草稿 `KNOWLEDGE` 节点没有 Fork；历史 `INTERACTION` 节点显示 Fork，并完成提交及新路线断言。测试先构造真实三节点历史谱系，未对不符合条件的节点强行点击。

## 验收误报修正

- WARN-03：恢复路线改为等待 `route-list .badge-open` 条件，不以固定 sleep 判断可见性；E2E helper 对浮窗关闭和窗口互不遮挡也使用条件等待。
- WARN-04：保留产品行为：单根节点不显示可执行 Regenerate；历史非根节点显示并打开再生对话框。正反例均已覆盖。
- WARN-05：空标题验证按产品/API 合约断言 HTTP 400，不再把 422 当作等价成功。
- v3 脚本统一使用 Playwright 实际端口 5174、`data-test` 语义选择器，并过滤已由断言覆盖的 400/404/409 fetch 控制台提示，避免把预期错误合约记为 ERROR。

## 隔离性

每个 Playwright 场景独立创建项目；浮窗、节点位置、Focus/Active 等浏览器状态通过项目初始化和条件等待隔离。已有调查材料、截图、临时验收脚本和 findings 文件均保留在工作区，没有纳入本次提交。

## 回归结果

- 后端：`backend\\gradlew.bat test --no-daemon` — BUILD SUCCESSFUL。
- 前端类型：`npm run typecheck` — 通过。
- 前端单测：37 个测试文件、327 个测试通过。
- 前端 Playwright：25/25 通过。
- 当前验收 v3：39 PASS、0 WARN、0 ERROR、0 INFO。

## 未纳入提交的文件

以下用户已有验收调查产物保持原样，仅作为证据留在工作区：`ACCEPTANCE_TEST_PLAN.md`、`ACCEPTANCE_TEST_REPORT.md`、`BUG_ROOT_CAUSE_INVESTIGATION.md`、`HANDOFF_POST_AD_CUTOVER.md`、`acceptance-findings*.json`、`acceptance_test*.mjs` 及 `screenshots/`。
