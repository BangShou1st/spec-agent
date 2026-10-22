# 阶段 4：架构与语义决策独立审查

日期：2026-10-21（Asia/Shanghai）。本报告执行范围是本地源码、冻结 JSON、历史报告和纯 Python 回归；没有连接数据库、请求模型、读取凭据或调整 Provider。没有修改生产 Prompt、生产代码、历史实验记录、oracle 或 holdout。

## 当前结果与执行证据

| 检查 | 本轮状态 | 证据及限制 |
| --- | --- | --- |
| C03 分类、三维裁决、历史回放及参数/来源契约 | PASS | 下述 116 项离线回归真实执行通过；不是 116 个模型样本 |
| C07 必要性与执行授权边界 | PASS（静态审查） | 参数检查、policy 分类和执行分层存在；必要性模型质量未新增测量 |
| C13/C15 来源有效性与事实依据 | PASS（静态审查） | 白名单校验不提供蕴含证明；历史 C15 失败原因维持 UNKNOWN |
| C16 pending dependency 表示 | BLOCKED（产品能力） | 输入缺少类型化 pending dependency，WAIT 恒拒绝；已知 false negative 仍存在 |
| 新模型质量、真实执行、P2 全集成 | NOT_MEASURED | 本轮零新增模型/数据库实验；不能用离线通过代替 |

实际命令（仓库根目录）：

```powershell
.\agent-brain\.venv\Scripts\python.exe -m pytest tools/tests/test_p3b_semantic_ab.py agent-brain/tests/test_contracts.py agent-brain/tests/test_decision_source_refs_compat.py -q
# 116 passed in 0.20s，exit code 0
```

其中 `test_c03_gemini_lite_20261020_replay_and_attribution_correction` 读取冻结实验文件并重算分类；`test_c07_tool_call_is_never_counted_as_no_unnecessary_call` 覆盖“模型选择调用但未执行”及“选择不调用但实际执行”的反例；严格契约正反例覆盖 V2/V3、未知字段与未知协议拒绝、类型化 persistenceIntent 和 sourceRefs 兼容。它们没有启动 Java/Spring。未单独并发启动 Gradle，以免与主验收批次共享输出；Java 执行结果以主报告为准。

## C03：拒绝候选规则，区分范围漂移与重复追问

权威取证顺序是原始 `tools/p3b/c03-live-runs/gemini-lite-20261020/c03_a.json` / `c03_b.json`、逐次 sidecar、独立复核 `c03_ab_decision_p3bae3_recheck_v2.json`，最后才是早期自然语言报告。

| 样本 | Prompt 语义 | Runtime/执行 | 含义 |
| --- | --- | --- | --- |
| A base、A holdout | PASS（候选动作与窄判据） | MISSING_TYPED_PERSISTENCE_INTENT；VETOED；awaiting_approval | 不重复追问不等于获准创建决策节点，更不证明成功落库 |
| B base | INCORRECT | PASSED；completed | 追问其他技术栈/中间件属于范围漂移；重复追问 0，范围漂移 1 |
| B holdout | PASS（同上） | MISSING_TYPED_PERSISTENCE_INTENT；VETOED；awaiting_approval | 不可把资格失败归成 Prompt 语义失败 |

最终仍为 REJECT。A 未表现出要消除的重复行为，不能声称候选规则改善了基线。候选规则允许寻找“全新维度必要信息”，与 B base 扩张问题范围相符；这是可解释假设，单次样本不证明模型内部因果。早期报告把 B base 计成重复，并用“自主固化”描述 A，均须以上述独立分类和实际状态限定。生产 Prompt 原样保留，9/9 历史预算不续增，PENDING_HUMAN_REVIEW 不被 AI 复核升级。

## C07：相关、参数完备、必要性、授权是不同事实

`ActionEligibilityValidator.validateVisibleCapability` 只校验可见描述符和声明必填参数；`validateCapabilityArguments` 校验运行时 ref 的 grounding。`AdvisorPolicyEngine.evaluateCapabilityInvocation` 从服务端注册描述符判定：NONE 自动执行、LOCAL_DURABLE 要求确认、外部副作用拒绝。这里没有用“参数齐全”授予权限。

C07 的 `P3BCorpus` oracle 明确为 NO_DETERMINISTIC_SURFACE：当前任务不需要检索时，相关的只读能力仍可能合法但不必要。历史 0/2 不必要能力调用只代表该两个样本。不能把必要性判断硬编码成参数检查，也不能把所有只读能力调用改成拒绝以提高 C07 得分，这会误杀 C08。

最小后续方案是在现有语义评测中保留四列：原始选择、eligibility、policy、执行账本；在不增请求的既有决策输入中明确呈现当前目标及已充分的信息。若未来要新增“显式调用授权”契约，须先冻结产品语义及 Runtime 命令来源；不能从 freeText 推导执行许可，也不能本轮改变 NONE 的生产自动执行规则。

独立回归矩阵：相同能力和完备参数，目标已闭环（C07，不应调用）/明确需要资料（C08，应调用）；缺必填参数（C06/C09，调用被拒）/无声明必填参数（仍 eligible）；NONE/LOCAL_DURABLE/EXTERNAL 分别保持现行 policy；缺角色/跨组织/撤权必须由授权与执行围栏拒绝。前三组模型必要性结论仍须盲态语义审核，不以 gate PASS 计正确。

## C13/C15：引用存在不等于引用有效，更不等于内容有依据

可复现源码事实：

- `AgentBrainResponseValidator.validateSourceRefs` 检查 refs 是否在快照白名单；这不是事实与引用之间的蕴含校验。
- `AgentInputSnapshotBuilder.allowedSourceRefs` 收集冻结节点、回答、patch、claim 来源、relatedNodes、retrievedContext 和 context/route 引用；它本身没有为每个 ref 输出统一 current/superseded/conflicted 状态。
- `ClaimView` 带 claim status；`NodeView` 只有 id/body/kind。来源状态可能由正文、claim、retrievedContext 的 authority/provenance 等局部事实表达，不能据此声称所有来源均没有有效性信息，也不能声称通用 ref 白名单已经表达失效关系。
- `CapabilityResultView` 明确能力结果是带 provenance 的外部观察，不自动进入 effective claims。保留这一边界。
- C13 fixture 的 rejected claim 与旧资源存在，但不能仅凭资源 seed 名称或 rejected claim 文本就构成 Runtime 可验证的逐来源 superseded 关系；C15 允许回应也不证明回应中的事实有依据。

因此要逐例区分：状态没有进入投影（表示问题）、状态与引用关联不明（映射问题）、关联明确但回答违背状态（语义错误）。历史 C13 holdout 的不可读中文是模型输出质量问题；旧 C15 失败无足够机器码，仍为 UNKNOWN，不能追认成“模型幻觉已证实”。

最小契约设计（尚未实施）：在下一显式版本的输入中按已知 Runtime 事实投影 `sourceRef + revision + validity + supersededBy/sourceAuthority`；未知保持 UNKNOWN，状态由授权来源更新，不能由模型自报。保留可用于说明历史的失效引用，单列可支持当前事实的引用用途；不要简单删除所有旧来源，也不要把整条自然语言回答交给关键词判定器。

独立验收矩阵：现行来源支持结论（正例）；旧来源支持历史叙述（正例）；旧来源被当成当前事实（反例）；跨来源冲突未解决（应披露冲突/必要追问）；没有事实支持（应承认未知）；白名单内 ref 但内容不蕴含断言（不能算 grounding PASS）；跨组织/已撤权来源（投影前排除，执行时仍复核授权）。上述契约应先有严格 Java/Python fixture 与独立审核标签，再谈生产接入。

## C16：最小 pending dependency 迁移提案

失败证据为 `P3BCorpus.c16GenuineExternalWait`：语义期望 WAIT，但 gate 期望 NO_PENDING_DEPENDENCY。`AgentInputSnapshot` 没有 pendingDependencies 字段；`ActionEligibilityEvaluator.evaluate` 对 WAIT 无条件 deny。现有 V3 增加 eligibility envelope，没有补上该表示缺口。`CapabilityResultView` 表示已完成的结果，不能拿它冒充在途依赖。

本轮保持拒绝是安全失败关闭，不是 C16 产品需求已完成。禁止检测“等供应商”等业务词来允许 WAIT。

最小后续改动应保持在现有 snapshot builder、版本化协议、eligibility 和 continuation 中：

1. 从已有持久化调用/任务事实投影只读 `pendingDependencies`，字段至少包含 Runtime 分配的 dependencyRef、类型、状态、所属 run/project 及 revision；没有可信持久化事实就不创建依赖。
2. 为 WAIT 选择绑定一个快照内依赖引用，允许条件是该依赖确实仍 pending、属于当前授权上下文且有恢复/事件来源。若新协议增加 payload，必须版本迁移，旧严格 V2/V3 不接受偷偷塞入字段。
3. 将依赖事实纳入快照/eligibility identity；执行前重查当前 revision 与授权，完成、失败、取消或撤权后不能接受陈旧 WAIT。
4. continuation 复用现有幂等子 run、预算、过期锚点与执行围栏。依赖完成时由既有事件路径触发后续，不加第二 Planner、额外模型探测或隐式轮询模型。
5. 终态“无后续事项”与外部等待分开，以 Runtime disposition 表达；不把完成伪装成 WAIT，不在本轮擅加 COMPLETE 动作族。

| 输入/竞态 | 预期 | 独立验证方式 |
| --- | --- | --- |
| 同项目、授权、真实 PENDING 依赖 | WAIT eligible | 内存仓储 fixture + Java/Python 契约正例 |
| 没依赖，仅自然语言声称等待/未知信息 | NO_PENDING_DEPENDENCY | 反例，不新增持久化依赖 |
| 已完成/失败/取消的依赖 | WAIT 不 eligible | 每种终态独立反例 |
| 跨组织、跨项目、不可见依赖 | 拒绝，不暴露正文/状态 | 授权正反例 |
| 冻结后完成或撤权 | stale/authorization 拒绝 | 受控竞态 fixture；真实事务恢复后另验 |
| 多个在途依赖，模型引用不存在者 | 拒绝选中引用 | payload 与 membership 反例 |
| 重复完成事件/重启 | 至多创建同一 continuation child，预算不重置 | 既有幂等契约扩展 |
| 终态无依赖 | 完成，不 WAIT | 生命周期正例 |

该表是未来实现验收标准，尚未执行，不能计入本轮 PASS。涉及生产生命周期和协议变更，当前证据不足以安全落地完整实现；本轮交付可审查的最小边界设计，不用空实现伪装功能闭合。

## 职责边界与文档漂移

Context 投影确定授权可见且冻结的事实；Eligibility 限制确定性必要条件；模型只在合法动作中做必要性与内容选择；Policy 判断自动执行/确认/拒绝；Executor 在当前授权、预算、幂等、stale 与执行围栏内落实副作用；Continuation 判断能否开启下一周期，不规划下一动作。

`docs/v2/ACTION_ELIGIBILITY_ARCHITECTURE.md` 的早期文字仍描述“未解决 blocker 限制普通持久化 continuation / 追问必须识别 blocker”，而当前 evaluator 的冻结原则明确不把语义冲突映射成动作集合限制，validator 只机械拒绝精确重复问题。这是文档与实现的历史漂移，不能仅据旧文档补一个全面 conflict deny，或把旧设计当当前生效行为。本报告以当前代码与场景 oracle 为准；需要另行确认设计版本，避免改变已冻结生产语义。

## P2：只列最新仍然未闭合的边界

审查 `PHASE2_IMPLEMENTATION_STATUS.md` §3.48 及 `P2_V93_*` 机器证据后，旧交接里的“V92/V93 归属 NOT NULL/FK/default 尚未收紧”“完整回归仍失败”已经被后续证据取代，不重复列待办。历史 1972 tests、34 skipped 是旧基线，不能作为本轮当前工作树通过证据。Alice/Bob 组织撤权和 V93 长任务重连是历史真实 OIDC + 双 JVM + test-only native fixture，不能说成真实模型已恢复。

真正遗留：

- 全 Controller/RPC/工具/恢复入口、memory/checkpoint/source-dependency 审计闭合；引用真实性不能代替使用者当前授权。
- 完整五身份、两组织/项目角色与全部 UI、跨实例撤权/退出矩阵；已有两身份正反例不能覆盖全部。
- embedding/公共索引全部部署组合、费用边界；企业 IdP/TLS、目标环境 drain 与承载测量。
- 真实模型恢复后业务完成资格；GA recovery 默认关闭不改变。
- 能力 IN_PROGRESS 的所有调用点、崩溃一致性与人工核对流程；无自动 TTL 重放不等于证明外部副作用恰好一次。

以上本轮均 NOT_MEASURED。后续需要明确隔离的新测试数据库、可信部署/身份环境和针对模型请求的独立预算；本轮不恢复历史进程、不迁移业务库、不用当前纯离线回归代替这些验收。

## dev → main 只读合并资格审查（按用户最新顺序）

先修 dev，再判断能否合 main，最后处理 main CI。本节未切分支、合并、提交、推送或执行数据库迁移。

实际只读命令：`git rev-parse main dev`、`git merge-base --is-ancestor main dev`（退出 0）、`git rev-list --left-right --count main...dev`（`0 28`）、`git diff --stat main..dev`、按生产文件定向 `git diff main..dev -- <path>`。

本地基线为 main `74b9815d69a562d6782c4efbef1b7daf3f075905`、dev `899ccbc2d7476e57ff1487ae88fd15ac9483d356`。28 个提交、144 个文件、25,617 行新增/307 行删除，包含大量实验产物及真实生产改动，不是只含 CI 修复的差异。main 是 dev 祖先，**Git 拓扑上可以 fast-forward，没有由分叉导致的三方冲突；当前验收不足，不建议把“技术可合”写成“可以安全发布/合入”。** 工作区还有用户及本轮未提交修改，当前 HEAD 的祖先关系不自动覆盖这些补丁；最终送审应固定修复后 SHA 重测。远端最新状态以主代理本轮 fetch/查询为准。

### 生产模型冻结改变了哪些行为

`RoutingModelInferenceGateway` 不再只读当前 provider 路由，而是通过 `ActiveModelTargetService` 取得包含模型、凭据、endpoint/API format/revision 的不可变设置，在第一次推理前把指纹原子绑定到 `agent_runs`，同一快照传入具体 provider 网关。后续配置漂移、目标解析失败、无可信 provider snapshot、缓存淘汰后持久化不匹配均失败关闭；不会在同一 Run 中静默更换模型/密钥。4096 项 LRU 是比较缓存，数据库绑定才是跨重启/实例约束。

这是一项合理安全边界，但明显改变运行中的 Run 遇到配置轮换时的可用性：不是继续持有旧凭据执行，而是重新读取当前目标后比较指纹，变更即拒绝。持久化只保存摘要，不保存可恢复旧配置。不能用旧 live 成功或 Python 契约通过证明新 Java 多实例、并发首次绑定及重启全部正确。

恢复独立依赖后至少需本次 SHA 的 `RoutingModelInferenceGatewayTest`、`KiloFrozenModelBindingTest`、`CustomFrozenTargetBindingTest`、`AgentRunModelBindingIntegrationTest`，覆盖同配置正例、provider/model/凭据/endpoint/revision 改变反例、并发绑定、重启、淘汰、终态/不存在 Run 和不可解析目标。真实数据库部分本轮不执行；离线测试通过只覆盖其实际断言面。

### Prompt 与协议发布边界

“本轮没有修改生产 Prompt”不等于“dev 相对 main 没有改 Prompt”。现有提交已改默认规则 5b，并新增 5e：没有可信依据时使用空 options 并允许自由输入；这是生产行为差异，需要保留现有 `test_decision_prompt` 和前端自由输入显示正反例。

C03/C08/C09 候选规则存在于 `P3B_DECISION_PROMPT_RULES`，但 `_ACTIVE_P3B_PROMPT_RULES = ()`，未默认推广。`SPEC_AGENT_P3B_EVAL_PROMPT_RULES` 可以在进程导入时启用候选，因此部署环境必须确认没有遗留实验 override；仅检查 ACTIVE 空集合还不等于证明部署运行的是基线。本轮没有读取或修改部署配置。

`git diff --name-only main..dev -- contracts agent-brain/src/spec_agent_brain/contracts` 无改动：本批没有新增 wire 协议版本/动作族。Java `AgentContracts`、`RemotePythonDecisionEngine` 与 Python `engine.py`/`app.py` 增加输出契约诊断，诊断 ID/阶段/白名单机器码穿过 HTTP 和 RUN_FAILED；缺失或未知仍应 UNKNOWN。虽然契约文件没变，Java/Python 错误归因语义已变，需同一发行版本及兼容旧错误形状的正反例，不能把未知 502 一律记作模型错误。当前 116 项通过包含 Python 严格契约与 sourceRefs 兼容，没有证明新 Java HTTP 诊断链集成已通过。

### V98/V99 是合并后的发布门槛

| 迁移 | 静态确定事实 | 风险与当前证据 |
| --- | --- | --- |
| V98 | `agent_runs` 新增 nullable CHAR(64) 指纹列 | 生产 DDL，需要核对目标 schema/Flyway 校验和、锁等待与维护窗口；本轮未测 |
| V99 | 对 status 非 created/completed/failed 的**所有**旧行更新为 `legacy-unbound` | 包括已有真实指纹的 V98 活动 Run，SQL 无 `IS NULL` 限制；故意阻止后续推理重新绑定，不是无影响 backfill；本轮未测 |

V99 不删除 Run，也不直接终态化；它写入一个不会匹配合法 SHA-256 的哨兵，使后续绑定拒绝。`awaiting_approval` 等不在三个排除状态中，也会被更新，但这本身不证明接受提案路径一定再次推理或必然失效，须按路径分别验证。若目标部署曾单独运行 V98，V99 还会覆盖当时合法生成的活动指纹；不能称为仅修复“历史没有指纹的 Run”。是否要保留已有绑定属于部署兼容决策，本轮只记录准确行为，不擅改已提交迁移或猜测目标库状态。

现有 `MigrationV99ModelBindingBackfillTest` 通过真实 Spring/PG、创建专用库并在 finally DROP 清理，且只验证 model_called/created 两种状态。它既不是纯离线测试，也不足以证明全状态迁移矩阵；本轮**未运行**。当前禁止数据库变更，不能顺手启动该测试，历史通过也不当作当前验证。

独立发布验收应在获准的新隔离库中覆盖：空库到 V99；V97/V98 升级；created/model_called/awaiting_approval/completed/failed；NULL/已有合法指纹；跨实例活动任务 drain 后升级；失败事务回滚；旧二进制/新 schema 的部署一致性；已迁移库重复启动不重复执行。DDL 与 V99 行更新都必须在已核实备份和维护方案下实施，不能靠本轮 CI 降级改变它们的风险。

### 合并结论

**技术上可 fast-forward；发布验收不足，暂不建议直接将 28 个 dev 提交合入 main。** 当前离线证据支持继续修复 dev 和准备可审查提交，不能替代模型冻结数据库集成、V98/V99 迁移与实际启动/多实例验收。C03 REJECT、C16 表示缺口与 P2 既有遗留不应以合并为由隐藏；也不必以修复它们全部作为所有独立开发的前置条件。

若本轮 dev 独立验证完成但上述真实集成无法安全执行，送审结论应明确“独立检查已验证，生产迁移/完整后端发布验收仍受阻”，保留实际 skipped/blocked；不能将只含 CI 解耦的小分支与整个 dev 的生产变更混成同一个低风险修复。本节不授权合并或推送，最终动作遵循用户最新授权及主代理的具体交付边界。
