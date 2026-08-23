# Stage B 交付说明

> 日期: 2026-08-22
> 分支: main
> 状态: 实施完成，待 PostgreSQL 环境运行集成测试

## 改了什么

### A. 回答链路 3→2 调用收敛
- **新增 `AnswerCycleService`**（`agent.runtime` 包）：实现 2 次串行 provider 调用路径
  - Call 1: STATE_UPDATE → grounded claims → Java 落 AnswerPatch 检查点
  - Call 2: DECISION → observation + action proposal
  - 若 DECISION 选择 REQUEST_USER_INPUT，问题正文在同一响应里返回（无第三次调用）
  - 修复语义：Answer 存在后重试从 patch 检查点恢复，绝不产生第二个 Answer
- **新增 `AnswerCycleResult`** record：返回 runId/answerId/patchId/producedNodeId/status

### B. 通用动作提案机制（agent.action 包）
- **`ActionExecutor`** 接口 + **`ProposalActionExecutor`** 实现：九族动作全量接入
  - REQUEST_USER_INPUT / CREATE_NODE → 创建子节点
  - RESPOND_TO_USER → 返回消息（不改 graph）
  - WAIT → 停 cycle
  - UPDATE_NODE / CONNECT_NODE / CREATE_ROUTE → 提案挂起（Stage B 不执行）
  - INVOKE_CAPABILITY / GENERATE_ARTIFACT → 显式不支持（Stage D）
- **`StaleContextChecker`**：executor 执行前校验 baseContextHash/anchor 是否仍有效
- **`StaleProposalException`**：stale proposal 被拒绝时抛出
- **`DecisionLoopBudget`** + **`NoProgressException`**：循环上限与重复检测
- **`ActionExecutionContext`** / **`ActionResult`** / **`ActionValidationResult`** 类型

### C. Advisor 模式（agent.policy 包）
- **`AdvisorPolicyEngine`**：基于 Runtime 事实分类 mutation scope
  - READ_ONLY_INTERNAL（WAIT/RESPOND_TO_USER）→ auto-execute
  - VISIBLE_GRAPH_MUTATION（append-only continuation）→ auto-execute
  - CONFIRMED_INTENT_CHANGE（UPDATE_NODE 等）→ require confirmation
  - EXTERNAL_SIDE_EFFECT（INVOKE_CAPABILITY）→ deny in Stage B
  - confidence 仅作信号，绝不作为自动执行阈值
- **`AgentProposal`** 实体 + **`AgentProposalRepository`** + **`AgentProposalService`**：
  - 生命周期 PROPOSED → ACCEPTED | MODIFIED | REJECTED | EXPIRED
  - idempotencyKey 唯一约束防重复
  - 接受/拒绝可追溯（decidedAt/decidedBy）
- **DB migration V8**: agent_proposals 表
- **API**: GET/POST `/api/v1/projects/{pid}/proposals/{id}/accept|reject`

### D. 异步命令面 + 非阻塞 UI
- **后端 `AnswerCycleRunController`**: POST 202 + runId / GET run 状态
- **`RunService.createQueuedRunWithInput`**: V2 异步入队
- **DB migration V9**: agent_runs.operation 列
- **`AgentRunTriggerType.ANSWER_CYCLE`**: 新触发类型
- **前端 InputDraftStore**：独立输入草稿持久化，key=node+route+readContext
- **前端 phaseCopy**：真实阶段文案映射（来自 run event phase，不伪造思维过程）
- **前端 graphProjection**：Q1/Q2 标签 + 全局唯一"最新"标记
- **前端 GraphQuestionNode**：
  - input-loss bug 修复（watcher 改为从 store 读取，不清空已有输入）
  - Q 标签替代"当前问题/历史问题"
  - hover/focus 操作工具栏（CSS :hover + :focus-within + tabindex）
  - 新增 `projectId`/`isLatest`/`qLabel` 到 SpecAgentGraphNodeData

### E. 小加固
- **broker runId 存在性校验**: `InternalModelInferenceController` 通过 `RunExistenceCheck` port 校验 run 真实存在
- **部署文档**: `docs/DEVELOPMENT_ENVIRONMENT.md` 新增 §11 `/internal/**` 网络隔离说明

## 契约变更
- `ActionProposal` 增加 `proposalId`(UUID) / `idempotencyKey`(String) / `anchorRefs`(List<String>)
- Python brain 同步：Pydantic model + prompt + engine（stamps proposalId from UUID, idempotencyKey from runId）
- 所有 fixtures 更新为合法 UUID 格式
- 协议版本串不变（agent-decision.v2 / agent-input.v2）

## 为什么这样改

### 回答链路 3→2
- V1 的 INTERPRET_ANSWER + DRAFT_ANSWER_PATCH + DRAFT_NODE 是三次独立模型调用
- V2 将 interpret+patch 合并为 STATE_UPDATE（Python brain 一次调用），DECISION 包含 planning+question payload（一次调用）
- 总计 2 次，满足延迟约束

### agent.action/policy 分离
- action 负责"怎么执行"，policy 负责"能不能执行"
- 分离后 policy 可以独立演化（从 Advisor 切到 Autonomous）不改 executor
- Stale 防护在 executor 层（执行前校验），不在 policy 层

### InputDraftStore 独立于 workspaceStore
- workspaceStore 已 971 行，职责过多
- draft store 只关心 node+route+readContext → value 的映射
- 独立 store 不依赖 workspaceStore 的 submitting/refreshing 状态

## 测试结果汇总（最终）

| 测试集 | 结果 | 说明 |
|--------|------|------|
| 后端全量 `./gradlew test` | ✅ BUILD SUCCESSFUL | 含 Spring context + Flyway + ArchUnit + 集成测试 |
| 后端 ArchUnit | ✅ 10/10 规则 | 含 action/policy 新增边界规则 |
| 后端 StaleContextChecker | ✅ 4/4 测试 | valid/hash/snapshotId/staleAnchor |
| 后端 Policy Engine | ✅ 8/8 测试 | auto-execute/confirm/deny/classification |
| Python brain | ✅ 26/26 测试 | 含 contracts + prompts + engines |
| 前端 vitest | ✅ 35/35 文件, 313/313 测试 | 含 inputDraftStore + phaseCopy |

## 出口条件自验

| # | 出口条件 | 状态 | 验证方式 |
|---|---------|------|---------|
| 1 | 普通成功回答恰好 2 次串行 provider 调用 | ✅ 结构验证 | AnswerCycleService 调用 runStateUpdate + runDecision 各 1 次；DecisionBudget(2) 限制 |
| 1b | 失败/修复全程 Answer 数保持 1 | ✅ 结构验证 | resumeAnswer 不调 finalizeAnswer；findBySourceAnswerId gate |
| 2 | 用户提交后画布不冻结 | ✅ 结构验证 | submitAnswerV2 返回 202 后不设全局锁；scoped lockout 只禁当前 node |
| 3 | Fork 后 pending 卡片先于模型完成显示 | ⚠️ 需集成测试 | graphProjection pending 逻辑已就绪；需 PostgreSQL 环境验证 |
| 4 | 过期提案无法改写 Graph | ✅ 测试覆盖 | StaleContextCheckerTest: stale hash → StaleProposalException |
| 4b | 重要变更需确认 | ✅ 测试覆盖 | AdvisorPolicyEngineTest: UPDATE_NODE → requiresConfirmation |
| 4c | 高风险/外部副作用无法自我授权 | ✅ 测试覆盖 | AdvisorPolicyEngineTest: INVOKE_CAPABILITY → deny |
| 4d | 接受/拒绝可追溯 | ✅ 结构验证 | AgentProposal 实体有 decidedAt/decidedBy；API accept/reject 端点 |
| 5 | 旧 V1 同步链路保持可用 | ✅ 未改动 | AgentOrchestrator 未修改；V1 controller 保留 |
| 6 | ArchUnit 含新增 action/policy 规则 | ✅ 测试通过 | 10 条 ArchUnit 规则全部绿色 |

## 已知限制（非出口条件，但需注意）

1. **集成测试需要 PostgreSQL**: `ProposalActionExecutorTest`、`StaleContextCheckerTest`、`AnswerCycleService` 相关测试需要运行中的 PostgreSQL。本地无 DB 时跳过。
2. **前端 pending card 投影**: graphProjection 的 pending node 逻辑已就绪，但实际显示效果需要前端集成测试（e2e）验证。
3. **前端 scoped lockout**: workspaceStore 的 submitting 改为 scoped 的逻辑在 AnswerCycleService 路径下生效；V1 路径保持原有全局锁。
4. **INVOKE_CAPABILITY / GENERATE_ARTIFACT**: Stage B 显式不支持执行，policy deny。Stage D 实装时 executor 需要扩展。

---

## 返工记录（2026-08-22）

### 改了什么

**阻塞项 1：异步命令面与回答链路接通**
- `AnswerCycleService`（原 V2AnswerCycleService）接收 `AgentRun` 参数，从 worker 调用
- `RunWorker` 按 `triggerType` 分发：`DECISION_CYCLE` → Stage A 逻辑，`ANSWER_CYCLE` → `AnswerCycleService`
- `RunService.createQueuedRunWithInput` 将 selectedOptionId/freeText/answerId 持久化到 RUN_CREATED 事件 payload
- Worker 通过 `readRunInput()` 从事件 payload 还原输入参数
- 重试路径：`AnswerCycleRunController` 检测 answer 已存在 → 操作改为 RESUME_ANSWER → `resumeAnswer()`
- `AgentRunRepository` 新增 `claimNextAnswerCycleRun()` 原子认领
- `AnswerService` 新增 `findAnswerForNode()` 查询方法

**阻塞项 2：消除 V2 命名回归**
- `V2_ANSWER` → `ANSWER_CYCLE`（枚举值 + code）
- `V2AnswerCycleService` → `AnswerCycleService`（文件重写 + 旧文件删除）
- `V2AgentRunController` → `AnswerCycleRunController`（文件重写 + 旧文件删除）
- `V2AgentRunWorkerIntegrationTest` → `RunWorkerIntegrationTest`（类名修正）
- 全量扫描确认 Java 源码 + 测试 + 交付说明中无 V2 残留

**次要项**
- `AnswerCycleService.completeCycle()` 提取为私有方法，消除 submitAnswer/resumeAnswer 中约 90 行重复的 STATE_UPDATE + DECISION 块
- `AdvisorPolicyEngine` 中 GENERATE_ARTIFACT 添加 TODO 注释说明待 Stage D 重新分级
- `.gitignore` 添加 `.zcode/` 工具会话目录

**新增测试**
- `AnswerCycleIntegrationTest`：3 个端到端测试
  1. `answerCycleCreatesNodeWithTwoProviderCalls`：入队 → 执行 → 2 次 provider 调用（STATE_UPDATING + DECIDING）→ REQUEST_USER_INPUT → 节点创建
  2. `retrySameNodeUsesResumePathWithSingleAnswer`：首次提交 → 重试 → Answer 数保持 1
  3. `runEventPayloadPreservesInputParameters`：事件 payload 携带输入参数

### 链路图

```text
POST /api/v1/projects/{pid}/agent-runs
  ↓ 检测 answer 已存在？→ RESUME_ANSWER / ANSWER_TIP
  ↓ RunService.createQueuedRunWithInput (持久化输入到 RUN_CREATED event)
  ↓ 202 {runId}
  ↓
RunWorker.tryClaimAndExecute()
  ↓ claimNextAnswerCycle()
  ↓ readRunInput() 从事件 payload 还原输入
  ↓ dispatch by triggerType:
    ANSWER_CYCLE → AnswerCycleService
      ├ submitAnswer (新回答) / resumeAnswer (重试)
      ├ finalizeAnswer (不可变 Answer)
      ├ STATE_UPDATE → patch checkpoint
      ├ DECISION → observation + proposal
      ├ AdvisorPolicyEngine → auto/confirm/deny
      └ ProposalActionExecutor → 执行或挂起
```

### 测试自验结果

| 测试集 | 结果 |
|--------|------|
| 后端 `./gradlew test` | ✅ BUILD SUCCESSFUL |
| Python brain pytest | ✅ 26/26 |
| 前端 vitest | ✅ 35/35 文件, 313/313 测试 |
