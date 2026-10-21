# 风险闭环后续：绑定完整性与跨语言能力契约

日期：2026-10-21（Asia/Shanghai）。基线为隔离分支 `codex/risk-closure-offline-20261021` 的 `880bdd9`。本轮继续使用 `E:/project/spec-agent-risk-closure`，没有新开聊天或改 PR #19；原目录仍为 dev/2ff6f07。

## 工作队列与边界

| 项目 | 状态 | 结果、阻塞原因或解除条件 |
| --- | --- | --- |
| 绑定摘要与配置一致性、输入拒绝 | DONE | 修复并运行独立/根离线回归；保持原 model-target-v1 摘要算法和既有有效值 |
| Brain/Host 嵌套能力引用边界 | DONE | 双端递归校验，错误不回显模型参数；同类坏输入不重试 |
| required 参数与 fake 决策一致性 | DONE | 根级 required 不依赖 properties；supports 匹配不能绕开 fake 的必填 query 排除 |
| C16 当前契约/模式差异、注入防护 | DONE | Java 3 项、Python 4 项可运行回归；明确 V2/shadow 与 V3/enforced 不同；没有实现真正异步等待 |
| C16 真实等待/完成/恢复端到端资格 | BLOCKED_ENVIRONMENT | 当前 wire 没有 pendingDependencies，快照仅取已完成调用；RUNNING 是调用占位/去重状态，不证明有可恢复完成通知。缺可信依赖来源与完成→continuation 接入证据。解除需版本化依赖投影、当前授权/revision 复核、可靠完成事件和获准隔离环境验收；不是打开 WAIT 开关或靠自然语言造事实 |
| V98/V99 真实升级、中断/并发与重启 | BLOCKED_AUTHORIZATION | 未授权数据库连接、迁移或集成测试。需另行明确新隔离库、数据矩阵和执行授权，不能直接运行会 CREATE/DROP DATABASE 的旧测试 |
| 历史 spec_agent_test 审计 | BLOCKED_AUTHORIZATION | 实例归属、schema、专用只读账户及范围待确认；原授权清单保留，不连接试探 |
| 干净环境完整后端 CI | BLOCKED_ENVIRONMENT | Zen 私有依赖不可用；本机缓存下编译/打包通过不证明干净远端可解析 |
| 本轮独立测试、根离线、Python、回放与打包 | DONE | 以下真实执行证据；未推送，远端本分支 CI NOT RUN |

原四阶段中的 AE4、P3B 基线/评分、历史模型证据、架构边界继续沿用，不重新进行模型实验。当前可以独立完成的上述最小修复及对应离线验证均已完成；完整 WAIT 产品能力、事故影响和数据库发布资格继续开放。

## 真实修改与证据

### 1. P2：摘要不能由调用方任意指定，哨兵不能作为候选绑定

`ActiveModelTargetPort.Target` 的公开四参数构造器原先仅检查摘要非空，允许把旧 digest 与已经换凭据/端点的 runtimeSettings 组合。当前生产 ActiveModelTargetService 使用三参数计算构造器，因此没有证据说正常生产路径已经利用此漏洞；这是可复现的内部契约缺口，不宣称已发生远程攻击。

现在四参数构造器复算并核对实际快照摘要，异常不打印配置/凭据；标准三参数路径的算法、字段和 v1 编码未改。固定 Kilo fixture 摘要 `39375349ba2ef73358ba7b940c72d90fdb32ea78d02f5e1c8b59db99171bf1eb` 钉住旧格式；其他离线断言覆盖 OpenCode 凭据、OpenRouter 修订、Custom 端点/凭据/API 格式/修订变化。

共享 RunModelBindingPort 明确只接受 64 位小写 SHA-256。InMemory 和 JDBC 实现对 null run、空/非法摘要、legacy-unbound 返回 false；JDBC 在此之前不调用。原仓储接受调用方把 legacy-unbound 本身当候选，从而失去“哨兵不能匹配合法摘要”的接口保证；现拒绝该值，但**没有更改数据库里任何哨兵、有效绑定或迁移 SQL**。数据库赢家更新、零行拒绝与异常不重试由 Mockito 验证，不能替代 PostgreSQL 并发验证。

路由新增两项真实生产类回归：持久端口拒绝/抛错都必须早于 Provider，不能重试、跨 Provider 回退或退回内存绑定。所有 Provider 是 Mockito mock，无真实模型请求。

相关文件：ActiveModelTargetPort.java:41；RunModelBindingPort.java:12；InMemoryRunModelBindingPort.java；AgentRunModelBindingRepository.java；ModelTargetBindingContractTest、AgentRunModelBindingRepositoryOfflineTest、RoutingModelInferenceGatewayTest。

### 2. P1：Brain/Host 的嵌套能力参数引用门禁不一致

原 Java Host 的 validateInvokeCapability 只查 arguments 的直接字符串值，嵌套 Map/List 中的外部 node/answer/patch/context/route ref 可通过。ActionEligibilityValidator 虽递归，但默认 shadow 不负责执行拒绝，因此不能依赖它弥补 Host 传输边界。Brain 原先只查 action.sourceRefs，不查能力参数引用，顶层和嵌套引用都可能先穿过 Brain。

现在 Java Host 与 Python Brain 均递归检查相同五类运行时引用。允许快照内引用及普通值；不解析自然语言片段、不根据业务词作判断、不改变权限策略。Java 产生有界 JAVA_RESPONSE_CONTRACT_INVALID 与稳定字段路径；Python 产生 MODEL_OUTPUT_UNGROUNDED_REFERENCE 和 action.payload.arguments，拒绝值不进入错误文本，也不触发修复模型重试。

修复前 Host 两项中一项失败，Brain 11 项中十项失败；修复后相关反例通过。允许 nested ref/null/false/0 的正例仍通过。该证据证明门禁缺口，不证明此前真的执行过越权副作用。

相关文件：AgentBrainResponseValidator.java:297；agent-brain/src/spec_agent_brain/decision/engine.py:437；新增 AgentBrainArgumentGroundingTest、test_capability_argument_grounding.py。

### 3. P2：标准 required 与确定性 fake 路径缺口

JSON Schema 的 required 是独立关键词；原 ActionEligibilityValidator 把它放在 properties 存在的分支下，导致 `{type:object, required:[query]}` 缺参数仍被放行。现独立提取 required，保留原紧凑描述符兼容及合法参数正例，不新增语义必要性规则。

LocalDeterministicDecisionEngine 也要求 properties 中定义 query 才承认根 required，且其优先 supports 匹配路径根本没有 required-query 排除。现两条选择路径都排除无法合成必填 query 的调用，既不编造 nodeRef 代替 query，也不削弱断言。新增两项回归先在旧实现真实断言失败，修复后通过。初次 fake 夹具曾因旧 fixture 的 kind=null 产生 NPE，修正为明确 RESOURCE/允许 nodeRef 的输入后才完成有效反例；初始夹具日志和中间失败日志均保留，不将夹具错误作为生产缺陷证据。

相关文件：ActionEligibilityValidator.java:230；LocalDeterministicDecisionEngine.java；ActionEligibilityCharacterizationTest 新增两项。

## C16 的实际进展与未完成部分

新 C16WaitContractTest、test_c16_wait_contract.py 共七个测试方法/pytest 项，明确验证：自然语言 pending、SUCCEEDED/FAILED/RUNNING/IN_PROGRESS 的结果状态本身不能产生可信 WAIT 资格；伪造 payload.dependencyRef 不能越过 enforced gate；V2/V3 snapshot 注入 pendingDependencies 都严格拒绝，不能悄悄改 wire；Brain 和 Host enforced 都拒绝无依赖 WAIT，shadow 保持既有兼容行为。

**更正此前报告的概括：不是所有模式都对 WAIT 执行拒绝。** 当前 evaluator 在两个版本都记录 NO_PENDING_DEPENDENCY，但 V2/shadow 的 enforce 不抛异常；AdvisorPolicyEngine 仍把旧 WAIT 视为 READ_ONLY_INTERNAL，ProposalActionExecutor 返回空操作。只有 enforced/V3 提供相应拒绝。没有更改该冻结兼容行为，也没有把 shadow 的 no-op 当真实依赖等待。

调用链检查得到更具体的实现障碍：CapabilityInvocationRepository.findRecentCompleted 排除 RUNNING；AgentInputSnapshotBuilder 只投影这些完成结果。CapabilityRuntime 对重复 RUNNING 返回 IN_PROGRESS，防止重复适配器副作用，不代表外部异步作业已注册完成通知。ContinuationCoordinator 只把已持久化 SUCCEEDED/FAILED 当新观察，RUNNING/IN_PROGRESS 不使其成为可续跑事实；重启 orphan 收敛也不会重放模型/外部动作。

因此不能把 ledger 的 RUNNING 直接映射成可恢复 pendingDependencies。下一生产实现至少要先固定权威依赖 ID/项目与 run/revision/授权、完成或失败事件的持久归属及去重、快照版本化、执行前状态复核，以及完成后如何创建唯一 continuation。完成、无事可做、用户选择与真实外部等待的协议语义也必须区分。本轮没有加无来源的新字段、无消费者的 DTO 或假完成事件；真正 WAIT/唤醒功能仍未交付，七项负例回归不能替代它。

## V98/V99 尚需的真实证据

V98 新增 nullable CHAR(64)，V99 对非 created/completed/failed 的所有行赋 legacy-unbound，依然覆盖已有有效活动指纹；本轮没有改变历史 SQL/checksum。审批路径通常是 completed origin + AWAITING_APPROVAL 事件，不应按“awaiting_approval 数据库状态”粗略计算。持久化仓储仍原子比较/设置并拒绝 completed/failed；完整旧配置并不存储，摘要不能恢复凭据快照。

必须在获准的新隔离库证明：空库及 V97/V98 升级；八种真实状态 × NULL/有效摘要/哨兵；completed origin 的待审批和 acceptance 后继；行锁/首次绑定/终态/V99 的竞态；失败迁移事务及重启重试；混合二进制与 drain；成功版本是否由 Flyway 历史跳过。旧 V99 测试只覆盖 NULL model_called/created 且会建库/删库，仍未运行。不能原地改 V99 来假定解决已部署目标，因为旧有效指纹可能已经被覆盖，且部署历史未知。

## 本轮实际执行

完整日志、失败/最终 JUnit、保护快照及 verification.json 保存在 `E:/project/spec-agent/docs/acceptance-evidence/risk-followup-20261021/`。Java 两套有重叠，不相加计算覆盖率。

| 命令/证据 | 实际状态 |
| --- | --- |
| 独立 ModelTargetBindingContractTest 修复前/后 | FAIL 2/3 → PASS；binding-red.log、binding-green.log；最终扩充为 4 项 |
| Host/Brain 引用反例修复前/后 | Host FAIL 1/2、Brain FAIL 10/11 → PASS；对应 red/green 日志与保全 XML |
| 独立 ActionEligibilityCharacterizationTest 修复前/后 | FAIL 2/24 → PASS 24/24；required-schema-red/green.log |
| backend/gradlew.bat -p backend/independent clean test verificationScope --offline --console=plain | PASS 102，0 failed/errors/skipped；61 生产、34 测试支持源码 |
| backend 下 gradlew.bat testOffline bootJar --offline --console=plain，命令进程清空 OPENCODE_ZEN_LOCAL | PASS 81，0 failed/errors/skipped；完整源码编译与 jar 重建，未启动应用 |
| 现有 venv Python -m pytest agent-brain/tests tools/tests -q，PYTHONPATH 指向隔离源码、禁用外部插件自动加载 | PASS 485；没有安装依赖/访问数据库/真实 Provider |
| 历史 evidence_audit、p3b_decision_replay | PASS：既有证据一致性；11/11 outcome、11/11 action、0 payload mismatch；合成冻结契约 fixture，不是新模型质量 |
| 独立 dependencyInsight --dependency opencode --configuration testRuntimeClasspath --offline | PASS，无匹配 Zen 依赖 |
| git diff --check、verify_followup.py | PASS，汇总真实 XML/日志、旧 SQL 和 Prompt 未变、既有授权清单哈希未变 |
| 应用启动、数据库连接/审计/迁移/集成、真实跨进程/E2E、新模型请求 | NOT RUN，未授权/本轮边界 |
| 本分支远端 CI | NOT RUN，未推送；没有借用 PR #19 的历史绿色作为本轮资格 |

C03 保持 REJECT，生产 Prompt、候选开关和历史模型产物不改。审计清单仍只有待确认的最小事项：事故实例的客户端/服务端映射、唯一 schema、已有专用只读账户及有效权限、明确查询编号/窗口/对象和安全凭据方式。未做探测连接、SQL、权限变更或迁移。

## Git 保护与交付

本轮所有改动在现有风险 worktree。原目录 dev/2ff6f07、main/74b9815、CI 分支/400ac62 保持不动；既有删除、uv.lock、事故报告、历史评测与证据保留。前一轮未跟踪的 database-audit-authorization-checklist.md 单独核对哈希，不纳入本轮提交。仅将本轮自有修复、回归和本目录报告/验证脚本汇为一个本地提交，不推送；最终 SHA/状态及逐文件保护结果见外部证据 final-git-state.json、protected-files-check.json。
