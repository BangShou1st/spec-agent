# ARCH-QUALITY-01 · 全仓架构与代码质量审计

status: Historical(本轮快照,2026-10-25)
authority: Historical Reference Only(后续以最新代码为准)

## 1. 审计范围

| 区域 | 方式 | 覆盖 |
|---|---|---|
| Java backend(16 个顶级包) | 包级 import 依赖图 + 重点文件逐行审查 | access/agent/assistant/capability/common/connection/mcp/model/modelsettings/retrieval/runtime/skill/testing/web/workspace |
| Python agent-brain | 独立审计代理通读 src 全部 42 文件(约 5.5k 行)+ tests + Java 侧对应校验器比对 | decision/state_update/artifact 三引擎、contracts、global_assistant、retrieval、model_client、app |
| Vue frontend | 目录结构 + 依赖方向检查 | features/*(8)、shared/*、app/* |
| 跨语言边界 | contracts/ 目录治理盘点 | 122 个契约/fixture 文件 |
| 架构守卫 | ArchitectureTests(973 行)既有规则盘点 | 38 条规则 + 3 个配套守卫类 |

## 2. 包间依赖方向(Java backend,实测 import 图)

```
access      → common
agent       → access capability common model retrieval runtime skill workspace
assistant   → access agent capability common model modelsettings retrieval runtime skill workspace
capability  → access common retrieval workspace
common      → (无)
connection  → access common mcp
mcp         → capability common
model       → common
modelsettings → access common model
retrieval   → access common modelsettings runtime workspace
runtime     → access common
skill       → access capability common
testing     → model
web         → agent common connection mcp model skill workspace
workspace   → access common
```

**循环依赖:0**(与既有 `packagesAreFreeOfCycles` 守卫一致)。方向整体健康:web 为叶子、common 为根、assistant 为最上层编排。

## 3. 主要发现(按严重度)

### P1-1 三个 GA 协议 Adapter 重复实现同一份响应契约校验【已修复 → 簇 1】

- 位置:`GaChatCompletionsAdapter.java:71-83`、`GaAnthropicMessagesAdapter.java:172-190`、`GaResponsesAdapter.java:71-89`
- 证据:三处逐条实现完全相同的不变量——① `toolChoice=none` 拒绝任何 toolCall / `required` 拒绝空 toolCall(异常 `GA native response violates tool choice`);② 工具名必须在请求 Catalog 内;③ toolCall id 不得重放历史(②③异常 `GA native tool identity not authorized`)。异常消息逐字一致,仅集合构建风格不同(流式 Collectors vs forEach+HashSet)。
- 测试缺口:仅 ChatCompletions 版本有直接单测(`GaNativeModelContractTest.providerReservedToolsAndReusedCallIdsCannotReachTheHost`);**Anthropic/Responses 两份实现零测试覆盖**。
- 影响:安全关键校验三处维护,漂移即漏洞;MS-C2/MS-C3 演进时曾需人工保持三份同步。

### P1-2 `GaNativeModelBroker`(assistant.api)跨包引用 provider 具体类【已修复 → 簇 1】

- 位置:`GaNativeModelBroker.java:200` 全限定 `new com.specagent.model.provider.GaChatCompletionsAdapter().validateResponse(...)`
- 证据:assistant.api 包对 model.provider 的唯一引用;既有守卫 `agentLayerShouldNotDependOnProviderImplementationPackages` 只覆盖 `com.specagent.agent..`,不覆盖 assistant.api,该引用未被任何架构规则拦截。
- 影响:编排层依赖协议适配器具体类;且无论实际走哪个协议,Broker 权威复核调用的都是 ChatCompletions 版本——恰恰证明该校验本属于协议无关契约层。

### P1-3 `ModelProvidersService.update` 冗余布尔比较【已修复 → 簇 2】

- 位置:`ModelProvidersService.java:148-151`(改前)——`changed` 定义中重复了 `identityChanged` 已包含的 `!equalsNullable(current.selectedModel(), model)`。
- 证据:纯布尔化简 `A||A||B||C ≡ A||B||C`;MS-CROSS 审查已记录此冗余。
- 影响:误导读者以为 selectedModel 有独立的第二重语义。

### P1-4 密钥脱敏原语四处各自维护【已修复 → 簇 2】

- 位置:`ModelProvidersService.suffix`、`CustomProviderSettingsService.suffix`(短密钥→null);`KiloSettingsService.suffix`、`OpenCodeSettingsService.suffix`(短密钥→`""`);另有 `hasKey` ×2、`equalsNullable` ×2 逐字重复。
- 证据:MS-CROSS-R1(aadcd00)的短密钥防泄漏修复必须同时核对四个副本;两组语义(null vs "")外部表现等价(各自读取点的 mask 均对 blank/null 归一),但语义分叉无任何机制约束。
- 影响:安全关键规则的未来修订存在漏改风险。

### P2(记录,本轮不实施)

| # | 发现 | 位置 | 不实施原因 |
|---|---|---|---|
| P2-1 | 来源守卫(origin guard)4 处近似重复 | ModelProvidersService.update / CustomProviderSettingsService.save+discover / ProviderModelCatalogService.requireSameOriginForReuse | 输入类型不同(ModelProviderRecord vs CustomProviderSettings)、消息文案有差异(是否带 "or clear the key");其中一处已是共享方法;强行统一需引入跨类型抽象,收益低于复杂度 |
| P2-2 | Kilo/OpenCode 历史行 masked_suffix 列存 `""` vs 新写入 `null` 的存储层归一 | kilo_settings/opencode_settings 表 | 读取侧全部经 mask 归一,外部不可见;归一历史数据需数据迁移,超出纯重构边界 |
| P2-3 | `GaExecutionModelTargetResolver` import CustomApiFormat/OpenCodeModelCatalog | assistant.runtime | 冻结目标解析的业务必需依赖;消除需在 contract 层复制协议枚举,反而制造第二份定义 |
| P2-4 | `ModelProviderViewService:76` 自有 mask 条件 | modelsettings | UI 投影自带 hasKey && length>4 前置条件,语义与其他 mask 不同,非重复 |
| P2-5 | Python 侧发现(详见 §5) | agent-brain | 本轮未改 Python 生产代码,全部记录为后续候选 |

## 4. 职责过载评估(未拆分,附原因)

- `GaNativeModelBroker`(347 行):入口/身份/路由/推理/校验/流式/错误映射/Continuation 于一身,但每个职责都是同一"冻结绑定执行"不变量的组成部分;拆散会割裂事务与授权语义。本轮仅将其响应校验依赖改指契约层(簇 1),其余保留。
- `GaChatCompletionsAdapter` 等三个协议 Adapter:请求构建+解析+校验混合,但三职责均为协议纯函数,同包同域,不构成跨模块耦合。校验部分已收敛(簇 1)。

## 5. Python agent-brain 审计结论(独立代理通读 42 文件 ≈5.5k 行)

- P0:无。全量 import 图单向分层无环(wire/diagnostics/config 基元层 ← contracts ← 三引擎/global_assistant/retrieval ← app 组合根);`requirements.lock` 无任何 DB 驱动、src 无连接代码;`os.environ` 仅 6 处(config.py:40,47,51,71、prompts/decision.py:97、retrieval/api.py:21)全部是自身服务配置/本地 Ollama URL,无厂商 API key 读取;模型推理统一经 Java broker(broker_client 只持内部 token)。
- P1 候选(本轮不实施,记录):
  1. WAIT/dependencyRef 规则三份实现:decision/engine.py:470-475(生产)、contracts/decisions.py:165-171(**src 内零调用,仅测试用**,与生产版几乎逐行相同的镜像)、Java ActionEligibilityValidator.java:63-69(权威)。Python 两份间漂移无机制约束。
  2. 三引擎异常与诊断体系割裂:decision 版带完整 ContractDiagnostic(engine.py:42-54,218-256),state_update/artifact 只有裸异常(state_update/engine.py:55 等),app.py:83-84 将无诊断失败统一记 UNKNOWN。
  3. `global_assistant/execution.py::start`(41-185,约 145 行,4 层嵌套闭包)承担 HTTP 限流+信封校验+内联 httpx 认领 RPC+注册表+线程/队列+终态裁决+清理共 6 类职责。
  4. `/health` 能力公告硬编码复制契约字面量(app.py:45-48,183);broker_client.py:51 硬编码 "model-inference.v1" 而 protocol.py:17 的 `INFERENCE_PROTOCOL_VERSION` 定义后从未被使用。
- P2 候选:ref 前缀词表 4 处不一致(5 前缀 vs planning 的 7 前缀)、SHA-256 hex 校验五种写法(inputs.py:317/decisions.py:138/ranking.py:105/retrieval/contracts.py:14/Java ActionEligibility.java:57)、严格 JSON 钩子双份(wire.py:18-37 vs checkpoints.py:142-150)、Ollama tags 校验三份+`_capacity` 私有名跨模块引用(configured_embedding.py:21)+SDK 私有 `_client.close()` 5 处、decision/engine.py 决策编排+解析+诊断库过载(513 行)、SharedRetrieval 健康协商+RRF 融合+RPC 编排纠缠(service.py:143-266)、checkpoint 迁移逐消息 RPC(checkpoints.py:171-191)。
- 测试夹具:conftest.py:13 已有 FIXTURES_DIR 但 15+ 文件各自重算路径;`_request()` ≥5 文件重复;ScriptedClient 5 文件重复。正面:请求/响应夹具统一走 contracts/fixtures JSON,tests 中 0 次直接构造 envelope,契约漂移先炸 fixture 解析。

## 6. Frontend 审计结论

feature-based 结构(features/* + shared/*),实测:features 之间零互相引用;shared 不反向依赖 features;HTTP 错误处理统一在 shared/http(client/displayError/errorCopy)。**无 P1 发现,本轮不实施前端改动。**

## 7. 跨语言边界审计结论

契约以 JSON Schema + 双端 replay fixtures 共享(contracts/ 共 122 文件),Java/Python 各自校验器消费同一 fixtures——共享机制本身就是防漂移设计。双端重复校验(Python 预校验 + Java fail-closed 复核)是有意的纵深防御,权威归 Java,非缺陷。

## 8. 未实施问题汇总与理由

1. Python P1×4 / P2×7(§5):本轮 Java 侧两簇已含 13 个文件,按"不同时重构 Java Broker、Python Brain、Graph Runtime 与整个前端"的范围控制原则,全部记录为 ARCH-QUALITY-02 候选。
2. P2-1/P2-2(§3):收益低于复杂度 / 需数据迁移。
3. suffix 短密钥 `null` vs `""` 的存储值统一已在簇 2 顺带完成(新写入统一 null);历史行归一(P2-2)不做。
