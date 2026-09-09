# Capability / Skills / MCP Phase 0 — Contract Freeze

> Status: **Frozen**  
> Date: 2026-09-09  
> Branch: `capability-skills-mcp`  
> HEAD: `eafe4389b283ff6a0877944d78f48a490144baf9`  
> Baseline docs: `docs/v2/CAPABILITY_SKILLS_MCP_IMPLEMENTATION_PLAN.md`(v4)、`docs/v2/CAPABILITY_RUNTIME.md`

本文档记录 Phase 0 在真实仓库上的契约冻结决策。它不改变模型行为；它把「双 catalog 漂移、Brain 描述符过小、provider SPI 缺失」等已知缺口转化为可供 Phase 1+ 直接实施的不变式。

---

## 1. Repository reality audit(对照 main 基线)

- Capability Runtime 已存在:`CapabilityAdapter` / `CapabilityRegistry` / `CapabilityRuntime` / `CapabilityInvocationRepository`(V13 migration)/ `SideEffectClass`。`INVOKE_CAPABILITY` 已通过 `ProposalActionExecutor` → `CapabilityRuntime.invoke`(幂等 claim→execute→complete)接线。
- 静态注册:构造器注入 `List<CapabilityAdapter>`,重复 id fail-closed;`descriptorsFor(Set<String>)` 只做权限过滤,无 supports 过滤、无动态 provider。
- Brain 可见 capability 投影语料:仅从 permission 过滤后的 Runtime descriptor 映射出 `{id, version, description, readOnly, sideEffectClass}`(见 `AgentInputSnapshotBuilder.visibleCapabilityDescriptors`),**不带 inputSchema / supports / permissions**。
- 双 catalog:`AgentInputSnapshot.availableCapabilities`(Builder 实际填充)与 `AgentRequestEnvelope.capabilities`(BuildEnvelope 固定传 `List.of()`)。后者当前为空。
- Legacy 占位:`McpAdapter`(接口 + `PrimitiveKind` 枚举,无实现)、`SkillAdapter`(marker,无实现)。
- ArchUnit:`capability..` 不得依赖 `agent..`/`model..`/`api..`/`web..`,不得依赖 `*Gateway`/`credential..`/`settings..`(见 `AgentBoundaryArchitectureTests`),任何新代码必须维持该约束。
- 无 Skill package/runtime、无 Connection registry、无 MCP runtime、无动态 provider、无 discovery 投影。
- 测试基线与环境:全量 `test` = 832 通过;`PythonBrainCrossLanguageIntegrationTest` 需真实 agent-brain(Python)才能跑,离线环境 skip。

## 2. Frozen decisions

### 2.1 Canonical planner-facing capability catalog

**决策:冻结 `AgentInputSnapshot.availableCapabilities` 为唯一 canonical planner-facing capability catalog。**

- `AgentRequestEnvelope.capabilities` 保留为协议兼容字段(两侧契约仍包含它),但实现层**永不填充**,不再承载任何语义;任何 future 演进只允许发生在 `snapshot.availableCapabilities`。
- 由于该字段已固定在 wire 契约(Python `extra=forbid`),保留而非删除,避免破坏协议版本;文档与测试双重防回归:`Builder.buildEnvelope` 继续传空,并新增契约测试断言顶层 catalog 始终为空。
- 原因:frozen Decision projection 才是模型实际消费的输入;envelope 顶层字段无独立消费方,双源会漂移。

### 2.2 Brain-facing Capability Descriptor projection

**决策:扩展 planner-facing `agent.contract.CapabilityDescriptor`(Java + Python 双侧同步),至少包含:** `id, version, description, inputSchema(可选), readOnly, sideEffectClass, supports(可选)`。

- `inputSchema` 采用 bounded JSON Schema 语义(仅 `type`/`properties`/`required` 等轻量结构),由 Runtime 持有 typed source of truth(Host Function Tool 的 Java DTO/schema),禁止手写 drift。
- `outputSchema` 及其余 Runtime 侧 metadata 按 context budget 决定是否暴露;默认不暴露。
- 永不暴露:credentials、provider SDK 细节、raw endpoint、implementation class、超大 description。
- 向后兼容策略:新增字段在 Java record 中为非必填(compact constructor 归一化为空 map/list),缺失字段的旧 frozen payload 反序列化保持默认;Python Pydantic 同步增加默认值字段,旧 fixture 无字段也可解析(新字段为已知字段,`extra="forbid"` 不触发)。
- 权重:`supports` 暴露给模型可辅助判断 relevance;必须与服务端过滤逻辑同源。

### 2.3 Capability Provider SPI

**决策:在 `com.specagent.capability` 增加 `CapabilityProvider` SPI,Registry 演进为「静态 adapters + 动态 providers」共存的统一目录。**

```text
CapabilityRegistry
  -> List<CapabilityAdapter>   (legacy bridge, Host Function Tools / internal capabilities)
  -> List<CapabilityProvider>  (dynamic providers, e.g. McpToolCapabilityProvider)
```

- capacity 合并后仍按 capabilityId 去重,重复 fail-closed。
- `CapabilityAdapter` 保留原名作为 legacy bridge,不大规模 rename。
- Agent 只消费经过 `CapabilityVisibilityService` 的投影;动态 provider 的 descriptor 变化在每次 fresh snapshot 构建时反映。

### 2.4 Capability Visibility 分层

**决策:确定性过滤与语义相关度严格分层。**

```text
CapabilityVisibilityService
  -> provider 可用性(动态 provider 只返回 enabled/available 的 descriptor)
  -> permissions
  -> supports/context 兼容(结构化事实:KIND / KIND:SUBTYPE)
  -> bounded catalog(max visible / max bytes / truncated flag / fingerprint)
```

- `CapabilityCandidateRetriever` 只负责语义候选缩减,第一版为 pass-through(小目录)。
- 禁止把 permission/trust/availability/semantic score 塞进一个 god ranker。
- `supports` 过滤逻辑从 `AgentInputSnapshotBuilder` 抽出到 `CapabilityVisibilityService`,Builder 只做「host descriptor → wire descriptor」投影。

### 2.5 Legacy `SkillAdapter` / `McpAdapter` 处置

- `SkillAdapter` / `McpAdapter` 保留为 marker/extension 接口,不作为 Skill/MCP 的语义定义者。
- Skill 是 procedural/context knowledge,默认不转为 CapabilityAdapter(`skill.activate`/`skill.read_resource` 是 Host Function Tool,调用 Skill Runtime)。
- MCP 是协议边界:一个 server 的 tools/resources/prompts 分别映射到动态 Capability / Resource context / Prompt asset,绝不 flatten 成一个 Tool。
- 未来真正提供 bounded executable operation 的 Skill 才可能通过 optional bridge 暴露 Capability。

### 2.6 Shared outbound network policy

**决策:新增 `com.specagent.common.network.OutboundNetworkPolicy`(或等价独立包),统一承载 Git 导入与 Custom MCP 的出站请求检查。**

- 禁止两处各自写一套 ad-hoc URL 校验。
- 具体能力见实现计划 §13;Phase 2(Git)/Phase 3(MCP)必须共用。

### 2.7 Secret 处理

- 现有 `provider_credentials`(V3 migration)是预留表(encrypted_secret + masked_suffix),但当前仓库无加密实现;`opencode_settings`(V5)目前明文存 apiKey。
- **决策:新增窄接口 `com.specagent.connection.SecretStore`(Phase 3),connection credential 只存 `credentialRef`,绝不存明文;本地最小 AES-GCM 实现 + 环境变量密钥;secret 禁止进入 Brain、trace、capability result、descriptor、错误响应。**
- 个人使用场景,不做企业 KMS;但 secret ownership 必须正确。

### 2.8 DB migration 设计(Phase 2/3 落地)

- 遵循仓库习惯:snake_case 复数表、UUID 主键、jsonb 结构列、`TIMESTAMP`、Flyway `V26__` 起续编。
- Skill:`skills`(稳定 skill_id/当前版本/enabled/source 元数据)、`skill_versions`(不可变内容哈希)、`skill_packages`(包文件,staged vs installed)、`skill_staged_imports`(审核队列)、必要索引/唯一约束。
- Connection:`connections`(product 概念,credential_ref 引用,不存 secret)、`mcp_discovery_cache`(per-connection primitives)。
- 复用现有 `capability_invocations` 做 MCP tool 调用幂等持久化,不重复造表。
- 不为 speculative enterprise feature 建表。

### 2.9 配置键(全部可配,默认由测试/合理数据决定)

见实现计划 §16 候选清单。本专项统一前缀:

```text
skill.catalog.max-visible           默认 24
skill.catalog.max-metadata-bytes    默认 4096
skill.search.max-results            默认 10
skill.activation.max-instruction-bytes 默认 60000
skill.resource.max-inline-bytes     默认 20000
skill.import.max-archive-bytes      默认 2_097_152 (2 MiB)
skill.import.max-extracted-bytes    默认 10_485_760 (10 MiB)
skill.import.max-files              默认 256
skill.import.max-depth              默认 8

capability.catalog.max-visible      默认 32
capability.catalog.max-descriptor-bytes 默认 2048

mcp.discovery.timeout-ms            默认 10000
mcp.call.timeout-ms                 默认 15000
mcp.result.max-inline-bytes         默认 30000
mcp.connection.max-redirects        默认 3
mcp.connection.max-metadata-bytes   默认 4096
```

模型输出无法修改这些限制。

### 2.10 Protocol 版本处置

- `agent-input.v2` / `agent-input.v3`、`agent-decision.v2` / `agent-decision.v3` 维持不变;本专项不新增 envelope 协议版本。
- Descriptor 新增字段属于 v2/v3 的向后兼容扩展(可选字段 + 双侧默认值),不 silent reinterpret。
- frozen projection schema `agent-input-projection.v1` 维持;旧 payload 缺新字段 → 默认空,replay 行为保持原样。

## 3. Phase 0 stop gate 检查

- [x] 无未解决的双 catalog 权威源(冻结 `snapshot.availableCapabilities`)。
- [x] 动态 provider 归属明确(`CapabilityProvider` SPI,registry 演进)。
- [x] Brain 有 versionable 路径接收 bounded arg schema(`inputSchema` 双侧扩展)。
- [x] Skill discovery owner/interfaces 冻结(Phase 2:SkillDiscoveryService/Visibility/Retriever/Projector)。
- [x] 既有 continuation/policy 路径显式复用(DecisionExecutionService / AdvisorPolicyEngine / ProposalActionExecutor 不改核心逻辑)。
- [x] migrations/security 边界可评审(§2.8 / §2.6 / §2.7)。
- [x] 当前 backend 测试基线已知(832 green)。

## 4. 关联文档

- 实施计划:docs/v2/CAPABILITY_SKILLS_MCP_IMPLEMENTATION_PLAN.md
- Capability Runtime:docs/v2/CAPABILITY_RUNTIME.md
- 跨语言契约:contracts/README.md