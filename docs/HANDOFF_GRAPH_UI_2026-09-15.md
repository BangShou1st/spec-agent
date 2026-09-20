# 交接文档：图谱 UI 修复（2026-09-15，分支 `system-bugfix-v1`）

> **第四轮更新（同日晚）**：「只看这条路线」缺陷已修复（见 §五），`RouteActionMenu.vue` 死代码已删除，
> §三 的两个设计问题已给出取证结论并替换为待拍板的工作包（§六）。
> 当前基线：`vue-tsc` 干净 + 单测 **648/648**。

新会话请先读本文件，再读 `E:\project\spec-agent\.workbuddy\memory\2026-09-15.md`（本次各轮的完整决策记录）
与 `E:\project\spec-agent\.workbuddy\memory\MEMORY.md`（项目长期约定，含图谱布局契约与视图状态三分法）。

---

## 一、第一至三轮已完成（12 项，`vue-tsc` + 单测 635/635）

| # | 改动 | 文件 | 验证 |
|---|---|---|---|
| 1 | 历史节点被选中时展开为**完整问答**（不再两行截断；含已回答选项 + 富文本回答 + 全选项并标出已选项；保持只读） | `components/graph/GraphQuestionNode.vue`、`style.css` | 单测 4 项 + 浏览器截图 |
| 2 | "从这里继续 / 问 AI"等悬浮操作轨道**置于最上层**（`hover`/`:focus-within` 时节点 z-index=12；**不按"选中"提升**，否则轨道会抢邻节点点击） | `style.css` | 单测 + 浏览器 |
| 3 | **禁用 Backspace 删除**（`delete-key-code = null`）：Runtime 没有单节点删除命令，Vue Flow 默认只删前端内存，刷新即恢复 | `components/graph/GraphCanvas.vue` | 单测 + 浏览器 8→8 |
| 4 | 节点正文**富文本渲染**（知识/笔记、历史回答统一走 `RichAssistantText`，与 AI 助手一致） | `components/graph/GraphKnowledgeNode.vue` | 单测 + 浏览器 |
| 5 | 问题/目的**保留模型原始换行**（`white-space: pre-wrap`）：修掉"一段式"的显示侧根因 | `style.css` | 浏览器实测（3/2 个换行已按行渲染） |
| 6 | 问题提示词**约束返回格式**（`questionText` 单句 ≤60 汉字、禁 markdown、禁内联罗列 1./2./3.，多子问题必须拆成 options） | `agent-brain/src/spec_agent_brain/prompts/decision.py` 规则 5a/5b | 文本级断言（10 条既有 prompt 断言全保留） |
| 7 | 自动布局**高度感知**：`computeInitialLayout(nodes, saved, { heightOf })` — 同列下一槽位 = 上一张实测高度 + `VERTICAL_GAP` | `graph/graphLayout.ts`、`components/graph/GraphCanvas.vue`(autoLayout 传实测高度) | 单测 + 浏览器重叠对 1→0 |
| 8 | 首次布局**内容高度估算** `estimateNodeCardHeight()`（实测校准：482 字/20 换行 → 802px 真卡）；`placeNewNode` 改为**盒碰撞**（旧的中心距离规则会误判长卡可用槽位） | `graph/graphProjection.ts`、`graph/graphLayout.ts` | 单测 5 项（含 ±35% 校准断言） |
| 9 | 路线菜单**收敛为两个视图动作**：定位路线 / 只看这条路线（移除 浏览此路线、弱化路线、隐藏路线、独览→改名"只看"）；阅读聚焦改由**点击路线卡主体**设置 | `components/workspace/RouteSidebar.vue` | 单测 + 浏览器（菜单项实测 `["定位路线","只看这条路线","归档并隐藏"]`） |
| 10 | **归档默认隐藏**（`DEFAULT_FILTERS.archived = false`），**删除路线入口移除**（`archiveRoute` 成为唯一的"收起"动作） | `stores/graphUiStore.ts`、`RouteSidebar.vue`、`views/WorkspaceView.vue` | 单测 + 浏览器（筛选默认未勾选） |
| 11 | **本地文件上传（.txt/.md/.markdown）**：浏览器内 `File.text()` 读取正文写入 `content.text`，`fileName` 一并保存；上限 256KB；PDF/Word/图片明确拒绝并提示 | `components/ResourceDialog.vue` | 浏览器实测（读入 sample-note.md，预览含换行，提交可用） |
| 12 | 顺带修掉一个测试环境 unhandled rejection：Spec Dock 的延迟定时器在卸载时清理 | `views/WorkspaceView.vue` | 单测 exit code 0 |

浏览器证据截图在 `.workbuddy/scratch/`：`final-historical.png`、`final-knowledge.png`、
`layout-overlap.png`、`question-lines-page.png`、`route-menu.png`、`resource-file.png`。

## 一·补（第四轮，同日晚）：修复「只看这条路线」+ 答疑

| # | 改动 | 文件 | 验证 |
|---|---|---|---|
| 13 | **「只看这条路线」变成真正的单路线镜头**：新增 ephemeral `isolatedRouteId`，`isolateRoute(routeId)` / `clearIsolation()`；不再改写持久化的 `routeDisplayStates` | `stores/graphUiStore.ts` | 单测 5 项 |
| 14 | 投影 `routeVisible()`（及 `getVisibleRouteIds`）把镜头放在**第一优先级**，压过生命周期筛选、手工 dim/hide、active 强制可见；`uiState.isolatedRouteId` 透传到 3 个 `projectGraph` 调用点 | `graph/graphProjection.ts`、`GraphCanvas.vue`、`WorkspaceView.vue` | 单测 4 项 + 浏览器 |
| 15 | 镜头**带走 Focus**；`reconcile` 只在路线真消失时清镜头；`hideRoute`/`showAll`/`resetView`/`initProject` 同步维护 | `stores/graphUiStore.ts` | 单测 |
| 16 | 菜单项变开关（`只看这条路线` ⇄ `退出只看`）；镜头下点另一张卡**移动镜头**（不再静默无响应）；卡片加 `只看中` 徽标 | `components/workspace/RouteSidebar.vue`、`style.css` | 单测 3 项 + 浏览器 |
| 17 | 画布顶部加 `只看：<路线>` + `显示全部` 胶囊（模态视图状态必须可见可退） | `components/graph/GraphCanvas.vue`、`style.css` | 单测 1 项 + 浏览器 |
| 18 | 删除死代码 `components/RouteActionMenu.vue`（全仓库无引用且带删除按钮） | — | 全量单测 |

**根因（可复现）**：旧 `isolateRoute` 把 `activeRouteId` 排除在隐藏之外 + `routeVisible` 对 active 无条件 true
⇒ 只有"只看运行路线"看起来成功；第二次只看非运行路线时运行路线仍留在画布上。
**证据**：`.workbuddy/scratch/isolate-{0..3}-*.png`（基线 9 节点 → 只看主路线 7 → 只看探索分支 8 → 显示全部 9，0 console error）。

### 命令备忘

```bash
export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"      # 本机 Bash PATH 默认是坏的
cd E:/project/spec-agent/frontend
"C:/Users/32962/.workbuddy/binaries/node/versions/22.22.2-3/node.exe" node_modules/vue-tsc/bin/vue-tsc.js --noEmit
"C:/Users/32962/.workbuddy/binaries/node/versions/22.22.2-3/node.exe" node_modules/vitest/vitest.mjs run
```

---

## 二、未验证 / 已知风险（新会话优先处理）

1. **4 个 e2e 用例已按新菜单改写，但本机跑不通**：`e2e/lifecycle.spec.ts`（去掉软删除段，改为"归档默认隐藏 → 勾选已归档筛选找回 → 恢复"）、
   `e2e/graph-routes.spec.ts`（去掉 dim/hide，改为点卡片聚焦 + 归档默认隐藏，并新增 `只看这条路线 连续两次都生效，且可一键退出`）、
   `e2e/fork.spec.ts`、`e2e/shared-focus.spec.ts`（`focus-route` 点击 → 点卡片主体 / `.vue-flow__pane` dispatchEvent('click') 清聚焦）。
   **必须**在 test profile 后端下跑：`SPRING_PROFILES_ACTIVE=test SPEC_AGENT_MODEL_GATEWAY=fake SPEC_AGENT_MODEL_INFERENCE=fake
   SPEC_AGENT_BRAIN_WORKER_ENABLED=true`。普通 dev 后端（`start-dev.bat`）下这些用例必然失败（项目自身注释记录 `draftQuestion` 现返回 INTERNAL_ERROR / BUG-03）。
2. ~~**`components/RouteActionMenu.vue` 是死代码**~~ → 已删除（第四轮）。
3. **首次布局估算的精度**：`estimateNodeCardHeight` 故意偏大（约 +10%~+35%）。若首屏觉得节点间距过大，调低常量即可；
   真实高度在测量后由 `重新自动布局` 接管。
4. 提示词改动（第 6 项）**未跑模型/评测**：建议单独跑一轮决策链评测再合并。

---

## 三、Q1/Q2 的取证结论（第四轮已查清，等你的取舍）

### Q1. 聚焦 ≠ 运行路线，但"多条运行路线并发"目前做不到

**你的决策**：不要"聚焦即运行"，要的是**多条运行路线同时存在、互相独立**（前端在聚焦 A 时，
B 上的节点仍能正常生成/回答问题，链路互不影响）。这个方向是对的，而且**存储层已经支持**：

- `routes.lifecycle_status` 里没有 active 语义（`V2__runtime_schema.sql:47` 注释明说
  "active is NOT a lifecycle status"），fork 后旧路线仍保持 `OPEN`（`RouteService:191-197`）⇒ 多条 OPEN 路线天然共存；
- **没有**项目级"单 run"约束：`agent_runs` 上只有幂等键索引（`V15:8-10`）与单续跑子约束（`V23:12-14`）；
  claim 是 `UPDATE ... FOR UPDATE SKIP LOCKED` 且按 `trigger_type` 各取一条（`AgentRunRepository:341-358`）
  ⇒ 同一项目可以同时有多个 `RUNNING` run。

**真正的阻点在语义层（硬约束，不是前端置灰）**：

| # | 阻点 | 证据 |
|---|---|---|
| 1 | 提交答案必须落在 `projects.active_route_id`，**没有按 routeId 显式提交的 API** | `AnswerCycleService:134`（`loadActiveRoute`）、`:161`（落库用 `route.id()`）、`:147`（非 active tip 直接抛错） |
| 2 | 生成下一个问题（draft question）同样只认 active | `RunService:61-69`、`DecisionCycleService:138-148` |
| 3 | 上下文构建要求 context route == active route | `ContextBuilder:90-94`、`ContextGuard:87-95` |
| 4 | 前端全局闸门：`submitting` / `drafting` / `routeCommandPending` 都在**项目级**，一条路线在跑就挡住其它路线的一切动作 | `workspaceStore:375/581/873/908/1101/1122/1143/1164` |

⇒ 只改前端是**倒退**：拆掉前端锁后，请求会被后端 1/2/3 拒绝（或更糟：静默写错路线）。
必须后端先支持"按显式 routeId 跑一条链"，前端再改成分路线状态。建议分两阶段：
- **阶段 A（后端）**：`POST /answers`、draft question enqueue 接受显式 `routeId`；校验 `route==OPEN && nodeId==该路线 tip`；
  `DecisionCycleService` / `ContextGuard` 改为认"run 自己的 route"。风险点：run 幂等键要带 routeId、上下文快照已按 route 冻结（`V20/V21`）、评测集要重跑。
- **阶段 B（前端）**：把 `submitting/pendingAnswerNodeId/answerRunStatus` 从单值改成按 routeId 的 map；
  `canAnswer` 不再要求"属于 active route"；每个路线可各自显示 pending 卡。

### Q2. 共享节点"只聚焦这个节点"：为什么现在会卡住

**结构性原因（这才是关键）**：`nodes` 表**没有 route_id 字段**（`V2__runtime_schema.sql:70-81`，
只有 `parent_node_id`）。节点属于哪条路线，是靠 `routes.tip_node_id` 沿 `parent_node_id` **回溯**算出来的 ⇒
一个节点天然可以属于 N 条路线（实测：本项目 3 个节点同属主路线与探索分支），
"这个节点属于哪条路线"在存储层就是**多值**的，必须有外部指针（active / focus）才能坍缩成单值。
而所有写命令要的不是单个节点，而是**"路线 + 节点"这一对**（血缘要记 `sourceRouteId` + `branchAtNodeId`）。
所以"只聚焦节点"给不出路线身份 → 4 处能力会 fail-closed：

| 位置 | 无 `readingRouteId` 时的结果 | 证据 |
|---|---|---|
| 卡片 从这里开新路线 / 重新选择答案 / 换一个问题 | 对话框提交条件不成立（按钮禁用），handler 里 `if (!sourceRouteId) return` 静默返回 | `WorkspaceView.sourceRouteForNode()` / `:484`；`ForkRouteDialog` 的 `canSubmit` |
| 知识卡 从这里继续 | 直接 `disabled` | `GraphKnowledgeNode:267-268` |
| Inspector 问 AI | 禁用并提示"共享节点请先选择一条查看路线" | `NodeInspector:78-85` |
| Inspector 需求状态 / Spec Dock | 无 routeId → 显示"未选择"，不加载逐路线需求与规格 | `WorkspaceInspector:60-80`；`SpecDock:50` |

**注意**：这个下拉**不影响回答**（回答永远写 active route，`AnswerCycleService:161`），
只影响"来源路线/上下文路线"。所以"删掉它"不是删一个控件，而是要另找一处让用户表达路线。

三个替代方案（仍待你选，新增 D）：
- **B（推荐）**：卡片只留只读"共享 · N 条路线"，把路线选择挪进动作弹窗（`ForkRouteDialog`/`ReanswerRouteDialog`/`RegenerateNodeDialog` 已有"来源路线"展示位，改成可选）。
- **A**：点击共享节点时，若它属于当前运行路线就自动把查看路线设为运行路线；否则在动作时才提示。
- **C**：卡片完全不出现路线概念，共享节点的叉/重答/换题统一走 Inspector。
- **D（配合"只看"镜头，成本最低）**：保留下拉但让它**默认被镜头/Focus 填满**——你现在可以先用"只看这条路线"
  一键把阅读路线定死（第四轮已实现），共享节点的歧义面因此大幅缩小。若仍嫌下拉碍事，再走 B。

### 附加：资源独立（"先浮动、用户自己连线"）需要新命令

现状：`attachResource` **硬约束**必须挂在 OPEN 路线的 tip（`GraphCommandService:231-243`），
浮动创建路径只产 KNOWLEDGE（`NodeService:133-148`）；且 `ContextBuilder:96-111` 只收 active route 的 lineage
⇒ 浮动资源**不会被模型读到**。所以"独立 + 用户自己连线"= 需要两件后端能力：
① 允许 `parentNodeId = null` 的 RESOURCE（浮动）；② 一条"把手动连线变成 lineage 插入"的新命令
（当前前端刻意不手工改血缘：`GraphCanvas.onConnect` 只发关系提案）。这两件都在后端，未动。

---

## 四、衔接词（新会话直接粘贴）

> 继续 `E:\project\spec-agent` 的 `system-bugfix-v1` 分支。
> 先读 `docs/HANDOFF_GRAPH_UI_2026-09-15.md`、`.workbuddy/memory/2026-09-15.md` 和 `.workbuddy/memory/MEMORY.md`，
> 那里有已完成的 13 项前端改动（含第四轮"只看这条路线"镜头修复）、未验证的 e2e 清单、
> 以及 §三 里 Q1/Q2 的取证结论与待拍板工作包。
> 我这次的决策是：【Q2 走 A/B/C/D】+【Q1 阶段 A 是否开工】+【资源独立是否开工】+【PDF/Word 本地解析是否开工】。
> 改完必须跑：`vue-tsc --noEmit` 与 `vitest run`（当前基线 648/648 全绿），并遵守 `MEMORY.md` 里的图谱布局契约
> （节点卡片宽度不能超过 240px 量级，否则右侧悬浮操作轨道会侵入相邻节点的点击区域）。
