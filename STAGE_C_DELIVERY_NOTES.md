# Stage C 交付说明

> 日期: 2026-08-22
> 分支: main
> 状态: 实施完成，全部测试绿（后端 / Python / 前端）
> 范围: `docs/v2/AGENT_RUNTIME_IMPLEMENTATION_PLAN.md` §7 — Graph Workspace（通用节点 / 自由 continuation / 语义关系 / Undo-Redo / 任意节点问 AI）

## 改了什么

### A. 通用工作区节点模型（additive 迁移）
- **V10 `graph_workspace`**: `nodes` 增加 `kind` / `subtype` / `content jsonb` / `author_kind` / `knowledge_status` / `retracted_at` / `updated_at`；新表 `node_relations`（语义关系，`ACTIVE` 部分唯一索引）与 `graph_operations`（类型化操作日志）；现有行解释为 INTERACTION/QUESTION（默认值）
- **V11**: 兼容性验证后放宽 `question NOT NULL`（非交互节点正文存 `content`）
- **V12**: `agent_proposals.anchor_refs` 持久化（接受时的新鲜度校验依据）
- 领域类型: `NodeKind` / `NodeAuthorKind` / `KnowledgeStatus` / `NodeSubtypes`（按 kind 的子类型白名单）
- `NodeService.createWorkspaceNode` / `reviseUserDraft`（仅 USER+KNOWLEDGE+PROPOSED 可原地编辑）/ `setKnowledgeStatus` / `setRetracted`

### B. 事务性图命令 + 操作日志（com.specagent.graph）
- **`GraphCommandService`**（每个命令 = 校验 + 变更 + 同事务追加操作日志）:
  - `createRootDraftNode` — 空路线首个草稿（**0 模型调用**）
  - `appendContinuation` — tip 上追加；**非 tip 源自动创建 CONTINUATION 探索分支路线**（冻结 inherited answers，绝不历史插入，原路线 lineage 不动）
  - `reviseDraftNode` / `setKnowledgeStatus` / `createSemanticRelation`（先查后插防重复；自引用拒绝）
- **`UndoRedoService`** — operation-specific 补偿:
  - 创建节点 → 软 retract + route tip/root 回退（root 创建清空锚点）
  - 分支创建 → 节点 retract + 路线软删（DELETED，历史保留）
  - 草稿编辑 → 恢复 before 内容
  - 语义关系 → RETRACTED
  - **Redo 仅在前置条件仍成立时**（tip 未前进 / 仍为软删 / 无重复 ACTIVE）；撤销后的新操作**隔断** redo 分支
  - 撤销创建的前置：无子节点、无任何路线的不可变回答、无其他路线 tip 引用
- RouteBranchType 新增 `CONTINUATION`；路线标签 "探索分支 N"

### C. 任意节点 Contextual AI Query（不强制 mutation）
- `POST /api/v1/projects/{pid}/nodes/{nid}/query` → 202 + runId → 后台 **NODE_QUERY** run
- `NodeQueryService`: `ContextBuilder.buildForNodeQuery`（anchor 任意节点 + 显式 route）→ **恰好 1 次 DECISION 调用**（无 STATE_UPDATE）→ `RESPOND_TO_USER` 消息写入 run event（`RESPOND_MESSAGE`）
- **查询零副作用**：mutation 类动作一律降级为待确认 AgentProposal
- 事件枚举 `NODE_QUERY` 加入跨语言契约（Java `AgentProtocol` + Python `protocol.py`）

### D. Proposal 接受即执行（补齐 Stage B 半接线）
- **`ProposalAcceptanceService`**: PROPOSED → 新鲜度校验 → 命令层执行 → ACCEPTED + `ACCEPT_AGENT_PROPOSAL` 操作日志（causedBy=proposal:id）
  - CREATE_NODE / REQUEST_USER_INPUT：anchor 必须仍是 route tip（否则 `StaleProposalException`，保持 PROPOSED 可重试）
  - CONNECT_NODE(SEMANTIC)：两端节点存在且未 retract → `createSemanticRelation(origin=AGENT, createdByProposalId)`
- `AgentBrainResponseValidator`: CREATE_NODE payload 按 kind 校验（INTERACTION=问题形状；KNOWLEDGE/RESOURCE/ARTIFACT=subtype+content.text）；CONNECT_NODE 校验 relationClass/relationType/ref∈allowedSourceRefs
- `ProposalActionExecutor.CREATE_NODE` 支持通用 kind/subtype/content（agent 创作的知识节点 author=AGENT, PROPOSED）

### E. 跨语言契约与 Python brain
- `NodeView` 增加 `kind`（Java + Pydantic，白名单校验）；非交互节点 `body.text` 投影为 `content.text`
- `EVENT_KINDS` 增加 `NODE_QUERY`；协议版本串不变（加法变更）
- DECISION prompt：节点四类词汇、CREATE_NODE/CONNECT_NODE payload 形状、NODE_QUERY 优先 RESPOND_TO_USER 规则（并修复了重复规则号 7）

### F. 前端（Graph Workspace UI）
- 类型: `GraphWorkspaceNodeView` + kind/subtype/content/authorKind/knowledgeStatus/userEditableDraft；`relations`；`branchType` 增加 `continuation`
- **节点类型注册表**: `nodeTypeForKind(kind)` → `question` | `knowledge`（GraphCanvas 按 kind 注册渲染组件，新增子类型复用现有卡片）
- **`GraphKnowledgeNode`** 卡片：子类型徽章 / 知识状态徽章 / 草稿就地编辑（textarea + 子类型选择）/ 确认内容 / 从这里继续（共享节点未选阅读路线时禁用并提示，**绝不 fallback**）
- **Undo/Redo 工具栏**（撤销/重做按钮 + 后端 availability 驱动禁用态）
- **空项目 "+ 想法"**（工具栏 + 开始占位符）→ 0 模型调用直接创建草稿
- **Inspector "问 AI"**: 任意节点输入问题 → 异步 run 轮询 → 内嵌显示回答；**语义关系区**显示该节点相关关系（AI 建议的标注来源）
- workspaceStore 新增: createRootIdea / continueFromNode / reviseDraft / confirmKnowledge / undoGraph / redoGraph / askNodeAI（轮询）

### G. Stage B 遗留修复（复查发现）
- `AgentProposalController.listProposals` 的 status 参数此前被忽略 → 真正按状态过滤
- `AnswerCycleService` auto-execute 前接线 `StaleContextChecker`（此前声明未用）
- `markPersistedNode` 不再把 answerId 填进 node id 列
- `AdvisorPolicyEngine` 未知 family 默认从 VISIBLE_GRAPH_MUTATION 收紧为 CONFIRMED_INTENT_CHANGE
- `AgentProposalRepository`: `Instant` 直接绑定 PG 不支持（save/updateStatus 转 Timestamp）、`::jsonb` named-param 写法改 `CAST(... AS jsonb)`、rowMapper 的 `getObject(JsonNode.class)` 探测移除 — 这三条路径此前无集成测试覆盖
- `GraphQuestionNode.vue` 中置 import 移到顶部（vue-tsc 报错）

### H. API 前缀融合（用户要求：结构不出现 v2 字样）
- 所有新命令面统一 `/api/v1` 前缀（agent-runs / proposals / nodes / continuation / draft / knowledge-status / relations / graph-operations / nodes/{id}/query），与既有工作区 API 同一命名空间
- 类名 / 文件名 / 包名无 V2 字样；迁移文件名 `V10__graph_workspace`（非 `graph_workspace_v2`）
- `V2__runtime_schema.sql` 为已应用历史迁移，不改名

## 测试结果

| 测试集 | 结果 |
|--------|------|
| 后端全量 `./gradlew test` | ✅ BUILD SUCCESSFUL（含新增集成测试与 ArchUnit） |
| 新增 GraphCommandIntegrationTest | ✅ 7/7（0 模型调用 / tip 追加 / 历史源分支不改写 / 草稿编辑边界 / 关系去重 / 外部节点拒绝） |
| 新增 UndoRedoIntegrationTest | ✅ 7/7（root 撤销重做 / tip 回退 / 下游存在拒绝 / redo 隔断 / 编辑恢复 / 分支软删恢复 / 关系撤销恢复） |
| 新增 NodeQueryIntegrationTest | ✅ 1 次 DECISION、RESPOND_MESSAGE、图零变更 |
| 新增 ProposalAcceptanceIntegrationTest | ✅ 4/4（执行+日志 / stale 拒绝保持 PROPOSED / SEMANTIC 建立 AGENT 溯源 / 二次接受拒绝） |
| ArchUnit | ✅ 新增 graph 包两条规则（不依赖 model/brain/gateway） |
| Python brain pytest | ✅ 26/26（契约加法兼容） |
| 前端 vitest | ✅ 35/35 文件, 313/313 |
| 前端 vue-tsc | ✅ 0 错误 |

## 出口条件自验（实施计划 §7）

| # | 出口条件 | 状态 |
|---|---------|------|
| 1 | 空项目无模型调用可用（用户可 author 并从手工节点分支） | ✅ `emptyProjectAcceptsRootDraftWithoutAnyModelRun` + `continuationFromHistoricalNodeCreatesBranch...` |
| 2 | AI 可回答节点问题而不强制 mutation | ✅ `nodeQueryAnswersWithContextAndNeverMutatesTheGraph` |
| 3 | Question 工作流向后兼容 | ✅ 全量回归绿；V1 路径未改 |
| 4 | 无 node-type/business-agent 爆炸 | ✅ kind 注册表 + subtype 白名单；动作族不变 |
| 5 | undo/redo precondition 测试 | ✅ UndoRedoIntegrationTest 7 项 |

## 已知边界（后续 Stage）

1. **UPDATE_NODE / CREATE_ROUTE 接受执行**: Stage C 显式 unsupported（Python prompt 未教其 payload；无对应命令）——待 Stage D 前评估
2. **INVOKE_CAPABILITY / GENERATE_ARTIFACT**: 仍 deny，待 Stage D
3. **RESOURCE / ARTIFACT 子类型**: 模型已可提案（CREATE_NODE payload 验证就绪），但前端 knowledge 卡是通用渲染；专用渲染待资源能力落地
4. **contextual query 的 pending 提案入口**: 前端 Inspector 显示"可查看待确认提案"提示；提案列表 UI 待整合
5. **CHANGE_FOCUS / FINALIZE_ANSWER 等操作类型**: 未纳入操作日志（各自已有生命周期语义）；按需扩展
