# Stage D 交付说明

> 日期: 2026-08-23
> 分支: main
> 状态: 实施完成，全部测试绿（后端 / Python / 前端）
> 范围: `docs/v2/AGENT_RUNTIME_IMPLEMENTATION_PLAN.md` §8 与 `CAPABILITY_RUNTIME.md` — Capability Foundation（registry / adapter 边界 / descriptor 过滤 / Resource 节点 / 能力观察 / 幂等）

## 改了什么

### A. 能力基础（com.specagent.capability，自包含包）
- **`CapabilityAdapter`** 通用端口 + 三个边界：
  - `InternalCapabilityAdapter`（已实装实现）
  - `SkillAdapter`（边界声明，Skill 包演化不改动作协议）
  - `McpAdapter`（边界声明：显式区分 tools/resources/prompts 三类原语，host 拥有连接与凭证）
- **`CapabilityDescriptor`**：capabilityId / version / description / input/output schema / readOnly / **sideEffectClass（NONE | LOCAL_DURABLE | EXTERNAL_REVERSIBLE | EXTERNAL_IRREVERSIBLE）** / requiredPermissions / **supports（如 "RESOURCE:TEXT"，上下文相关性声明）**
- **`CapabilityRegistry`**：发现 / **权限过滤（未授权 = 不可见而非仅禁用）** / adapter 路由；重复 id 拒绝
- **`CapabilityRuntime`** + **V13 `capability_invocations` 表**：Runtime 持有幂等键——**同 key 重放返回已记录结果，绝不重复执行副作用**；未知能力 → 类型化 FAILED（fail-closed）；意外异常记为 typed 失败，不静默重试

### B. 首个内部能力：resource.extract_text
- 只读（NONE 副作用），从 RESOURCE 节点提取**有界摘录**（≤2000 字符 + truncated/totalChars 元数据）
- **绝不全文注入 prompt**；结果带 sourceRefs（node: 引用）与 provenance（`EXTERNAL_SOURCE_EVIDENCE` + subtype + 节点创建时间）

### C. INVOKE_CAPABILITY 全链路（按副作用分级）
- **`AdvisorPolicyEngine`**：不再笼统 deny——按 descriptor.sideEffectClass 分级：
  - NONE（只读）→ 自动执行
  - LOCAL_DURABLE → 需确认
  - EXTERNAL_REVERSIBLE / EXTERNAL_IRREVERSIBLE → deny（"需要显式授权策略"）
  - 未知/缺失 capabilityId → deny（fail-closed）
- **GENERATE_ARTIFACT 重新分级**：本地生成制品 → 需确认（不再是外部副作用 deny；执行仍待制品运行时）
- **Validator**：capabilityId 非空 + arguments 形状 + **通用 ref 反走私规则**（任何 ref 形状的参数值必须在 allowedSourceRefs 内，不绑定具体参数名）
- **Executor**：幂等键 = `run:{runId}:proposal:{idempotencyKey}`（重试同一提案 → 重放）；typed 失败转为消息返回，run 不崩溃

### D. Planner 只见过滤后的 descriptors（防过拟合）
- **`AgentInputSnapshotBuilder`**（唯一投影缝合点）：
  - **权限过滤** + **supports 驱动的相关性过滤**（descriptor 声明 "KIND[:SUBTYPE]"，与 lineage 节点匹配；无 RESOURCE 的上下文看不到资源能力——**无关能力不可见即不会被调用**）。新增能力只需声明 supports，不改 builder/prompt/planner
  - **`capabilityResults`**：最近 5 条已完成调用作为有界观察进入后续周期（外部证据，绝不自动成为 confirmed 事实）
- wire 契约：`CapabilityDescriptor` 加 description/sideEffectClass；新 `CapabilityResultView`；`AgentInputSnapshot.capabilityResults`（Java + Pydantic 同步，加法兼容）
- 决策 prompt：只描述 INVOKE_CAPABILITY / capabilityResults 的**通用语义**（不写死任何能力 ID）；fake engine 同样通用触发（availableCapabilities 非空 + lineage 存在非交互节点）

### E. RESOURCE 节点（用户可挂载）
- **`GraphCommandService.attachResource`**：空路线根 或 当前 tip 追加（**禁止挂历史节点**）；无知识状态语义（资源不是 claim）
- 操作日志 `ATTACH_RESOURCE`（可撤销/重做，与其他节点创建同补偿逻辑）
- **API**: `POST /api/v1/projects/{pid}/resources`（零模型调用）
- **前端**：工具栏 "+ 资源" → ResourceDialog（TEXT/URL/FILE + 内容/链接）→ workspaceStore.attachResource；资源节点复用 knowledge 卡渲染 + Inspector 显示 kind·subtype

### F. 顺手修复（测试盲区暴露的既有 bug）
- `AnswerCycleService` COMPLETED 事件用 `Map.of` 装 nullable producedNodeId → NPE（此前 fake 路径总产生节点，从未暴露）；改 HashMap

## 高内聚低耦合与防过拟合自查

| 原则 | 落实 |
|---|---|
| capability 包自包含 | ArchUnit 新规则：capability 不依赖 agent/model/api/web/credential/settings/gateway；依赖方向只有 agent → capability |
| 唯一投影点 | 能力数据进模型上下文只在 snapshot builder 一处完成 |
| policy 不认识具体能力 | 只读 descriptor.sideEffectClass，无任何能力 ID 分支 |
| 通用过滤 | supports 声明驱动，无硬编码能力/节点判断 |
| 通用 ref 校验 | ref 前缀集合驱动，不绑定参数名 |
| prompt 不过拟合 | 只写语义规则，能力清单是运行时注入数据 |
| 测试场景族 | 多族覆盖：TEXT/长文本截断/非资源节点/未知能力/重放/无资源项目/挂载位置拒绝 |

## 测试结果

| 测试集 | 结果 |
|--------|------|
| 新增 CapabilityRegistryTest | ✅ 5/5（权限过滤不可见 / 重复拒绝 / 查找） |
| 新增 CapabilityIntegrationTest | ✅ 8/8（有界摘录+溯源 / 幂等重放不重复执行 / 未知能力 fail-closed / 非 RESOURCE 拒绝 / 相关性过滤双向 / 观察注入快照 / 历史节点挂载拒绝 / 只读分级元数据） |
| 新增 CapabilityAnswerCycleIntegrationTest | ✅ 1/1（端到端：answer cycle → fake 决策提 INVOKE_CAPABILITY → policy 自动执行 → 恰好一次调用记录） |
| AdvisorPolicyEngineTest | ✅ 12/12（含新的四级能力分级 + GENERATE_ARTIFACT 重新分级） |
| ArchUnit | ✅ 新增 capability 包两条自包含规则 |
| 后端全量 | ✅ BUILD SUCCESSFUL |
| Python brain | ✅ 26/26（契约加法兼容） |
| 前端 | ✅ 313/313 + vue-tsc 0 错误 |

## 出口条件自验（实施计划 §8）

| # | 出口条件 | 状态 |
|---|---------|------|
| 1 | Planner 只见过滤后的 descriptors；无关能力不被调用 | ✅ `snapshotExposesResourceCapabilityOnlyForRelevantLineage`（无资源项目 → 空清单）+ 权限过滤单测 |
| 2 | 能力成功/失败场景通过且无重复 mutation | ✅ 端到端恰好一次调用 + 幂等重放 + typed FAILED |
| 3 | 外部副作用需正确审批策略 | ✅ EXTERNAL_* → deny（无授权配置时不可执行）；LOCAL_DURABLE → 确认 |

## 已知边界（Stage D 之后）

1. **MCP / Skill adapter 实装**：边界与原语映射已声明，真实连接/凭证/审批流待具体 MCP server 接入时落地
2. **外部副作用授权配置**：deny 文案已区分"需要显式授权策略"；授权数据源（项目/用户级 capability permissions）待权限系统落地
3. **GENERATE_ARTIFACT 执行**：分级已修正为本地生成需确认；制品运行时（Spec/Report 之外的产出物）未实装
4. **能力结果消费标记**：观察按"最近 N 条"注入（幂等、无消费状态）；大量调用历史后如需精确"未读"语义再引入消费标记
5. **RESOURCE 专用渲染**：前端复用 knowledge 卡；IMAGE/REPOSITORY 等子类型的专用展示待真实资源接入
