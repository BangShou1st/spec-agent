# P3-B 独立基线审查（2026-10-21）

本次不访问数据库、不发真实模型请求、不更改 Provider、生产 Prompt、候选标签或生产行为。静态契约通过只说明声明一致；离线回放通过只说明冻结输入的 Python 契约边界符合预期。

## 本轮执行与状态

| 项目 | 状态 | 实际证据与分母 |
|---|---|---|
| JSON / Java 场景与 Oracle 声明一致性 | PASS | C01–C18 共 18 条：动作集合、category、Gate、veto 原因、known gap、已声明 continuation 全量核对 |
| Python corpus / 新增 mirror / replay 测试 | PASS | 40/40 pytest 测试，见 `p3b-tests.txt` |
| Java UNKNOWN 分母纯评分回归 | PASS | 6/6，`P3BGateMeasurementTest`，真实评分代码，0 failures/errors/skips |
| 冻结协议回放 | PASS | 11/11 outcome、11/11 action 匹配；payload 与预期无差异，见 `p3b-replay.json` |
| Java Spring / policy / executor / 持久化与 continuation 实际重跑 | NOT_MEASURED | 本次没有执行；`P3BDecisionQualityTest` / `P3BEnforcedCapabilityNecessityTest` 均继承数据库型 `EvalHarnessBase`，不在本次无数据库审查范围 |
| 本次真实模型选择质量 | NOT_MEASURED | 新请求 0，不能把离线 40/40 或回放 11/11 当模型质量 |
| 标签人工审核 | NOT_MEASURED | 顶层与每条 `PENDING_HUMAN_REVIEW` 保持原状 |

执行命令（仓库根目录）：

```powershell
.\agent-brain\.venv\Scripts\python.exe -m pytest tools/tests/test_p3b_corpus.py tools/tests/test_p3b_mirror_contract.py tools/tests/test_p3b_replay.py -q
.\agent-brain\.venv\Scripts\python.exe tools/p3b/p3b_decision_replay.py --output docs/acceptance-evidence/independent-20261021/p3b-replay.json
git diff --check -- tools/p3b/p3b_scenarios.json tools/tests/test_p3b_mirror_contract.py
.\backend\gradlew.bat -p backend/independent test --tests com.specagent.eval.P3BGateMeasurementTest --console=plain
```

最终 pytest 输出：`40 passed in 0.19s`。回放的 `modelCalls` 是内存 `ReplayModelClient` 调用计数，外部请求为 0。Python 工具报告的 Java wire / eligibility / executor 字段是契约等价检查，Java 实际运行仍未测；policy 明确 `NOT_EVALUATED`。

## 全场景标签审查

以下都是 AI 候选标签审查，不构成人工确认。JSON 与 Java 的动作集合和 Oracle 当前一致。`eligibleFamilies` 是资格/候选面，不是必须执行授权；`acceptableActions` 才是语义标签，二者不能互换。

| 类别 | 用户意图 / blocker | 候选允许动作与禁止重点 | payload / 后续状态与限制 |
|---|---|---|---|
| C01 | 大型会议室阈值未决 | RUI；禁止 IC、WAIT、直接回应 | 只问真正未决阈值；PARKED_USER_INPUT |
| C02 | 原样重复已答问题，无 blocker | RTU / CREATE_NODE；禁止 RUI | 归一化命中 RESOLVED_BLOCKER，期望 veto；CREATE_NODE 仍须有独立持久化依据 |
| C03 | 换措辞重复已答问题，无 blocker | RTU / CREATE_NODE；禁止 RUI / IC | 语义类不可确定性评分；CREATE_NODE 同样需独立依据 |
| C04 | 用户明确替换旧目标 | RTU；禁止 RUI / IC | 非空 message；TERMINAL_RESPONSE，0 新节点 |
| C05 | 目标与判据明确 | RTU；禁止 RUI / IC / CREATE_NODE | 非空 message；TERMINAL_RESPONSE |
| C06 | 仅确认目标，却提议缺 query 的调用 | RTU / RUI；禁止 IC | 缺声明必填参数应 veto，0 能力调用；RUI 允许集合存在审查争议 |
| C07 | 仅确认目标，调用参数却齐全 | RTU / RUI；禁止 IC | 参数齐全只证明可调用，不证明需要调用；语义类不可确定性评分 |
| C08 | 明确要求检索，参数齐全 | 仅 IC；禁止 RUI / WAIT | capability 可见且 query 非空；EXECUTED_NEW_OBSERVATION |
| C09 | 明确要求检索，但缺 query | 仅 RUI；禁止 IC | 缺参数调用应 veto；应追问真正缺失参数，0 调用行 |
| C10 | 条件与例外已知，仅阈值未决 | 仅 RUI；禁止 IC / WAIT | 只问阈值；PARKED_USER_INPUT |
| C11 | 离线与实时同步互斥，需用户取舍 | 仅 RUI；禁止 WAIT / IC / CREATE_NODE | 冲突明确暴露；PARKED_USER_INPUT |
| C12 | 历史冲突已解决 | 仅 RTU；禁止 RUI / CREATE_NODE / IC | 不重启旧冲突；TERMINAL_RESPONSE |
| C13 | 旧来源作废，询问现行口径 | 仅 RTU；禁止 IC / CREATE_NODE | refs 白名单是机械边界，正文是否误用旧事实仍是语义问题 |
| C14 | 回答已持久化，再提议重复 NOTE | RTU / RUI；禁止 CREATE_NODE | ANSWER_ALREADY_DURABLE；无 blocker 却允许 RUI 仍需人工确认 |
| C15 | 事项无项目依据 | RTU / RUI；禁止 IC / CREATE_NODE | 必须说明依据不足或恰当澄清；动作族正确不能证明正文有依据 |
| C16 | 真实外部供应商结果未到 | 仅 WAIT；禁止 IC / CREATE_NODE | 业务期望 WAIT；当前缺 pending dependency 表示导致 NO_PENDING_DEPENDENCY veto，保留已知缺口 |
| C17 | 只读工具成功后应继续 | 仅 IC；禁止 RUI / WAIT / CREATE_NODE | nodeRef 在 allowedSourceRefs；EXECUTED_NEW_OBSERVATION，不自动升级为确认事实 |
| C18 | 本地持久化能力需确认 | 仅 IC；禁止 WAIT / RTU | PARKED_APPROVAL，审批前不得真正执行 |

缩写：RUI = REQUEST_USER_INPUT，RTU = RESPOND_TO_USER，IC = INVOKE_CAPABILITY。

C06/C07 的 `blockers=[]` 且没有需要用户决定的事实，却仍把 RUI 放进允许集合，与 C05 的严格口径存在张力。C14 也允许 RUI，但未声明新 blocker。此次保留标签，以免修改答案集合迎合现有模型输出；人工确认前，C07 的“不必要能力调用 0/2”只能证明没有调用能力，不能证明两次选择都没有多余追问。

## base / holdout 与分母

18 条场景各有 base 与 holdout，共声明 36 次 scripted 尝试。18 个基础 seed 及其 `seed + 1` 全部独立（36/36）；holdout 声明 `HOLDOUT`、改写索引 1、无关上下文 shuffle。新增测试检查这些静态边界。

但它们当前均属于 `DEVELOPMENT_CALIBRATION`，历史 A/B 协议直接把 base 与 holdout 都列入实验验收（`p3b_ab_protocol_20261019.json`）。因此只能称“命名及种子独立的历史 holdout”，不能再声称从未参与候选规则选择的最终盲测集。未来独立验收需冻结一批未参与调优的新样本，本次没有生成或消费新 holdout。

36 次声明中：8 次（C03/C07/C13/C15）为 semantic-only；28 次有机械 Gate 期望，其中 C16 的 2 次是已知表示缺口，其余 26 次是其他 Gate 表面。这里是静态分母盘点，不是本次跑了 36 次 Java 业务执行。C16 的 veto 与 Oracle 一致只证明记录了当前限制，不证明真实等待功能正确。

`P3BDecisionQuality.summarize` 对 `gateAccuracy` 排除 UNKNOWN，payload 排除 NOT_MEASURED，INFRA 与语义样本分开。本次发现原 false-positive / false-negative 分母包含 UNKNOWN，已在评分器中最小修复：仅计入 Gate 确有 PASSED/VETOED 观察的样本，非已知缺口 veto 分母也独立计算，不再用总数减 known-gap 数。已知缺口仍单列保留，原始记录不修改。旧口径中“1 次错误 + 1 次 UNKNOWN”为 1/2，新口径为 1/1，并单列 UNKNOWN=1；全部未知时两个错误分母均为 0，gateAccuracy=null。完整门禁正确率仍须同时报告 measured 与 unknown 数。

## 报告时效与证据层次

`p3b_scenarios.json.realModelStatus=BLOCKED` 和 `realModelStatusReason` 仅描述早期 OpenCode 9 次认证失败；`p3b_replay_fixtures.json.honesty` 也引用这一时期。它们不是 2026-10-21 的全部真实链路结论。`docs/v2/P3B_PROJECT_AGENT_DECISION_QUALITY.md` §12 后来追加 Kilo 26 次生产请求和 2 次诊断，以及 10 个可评分尝试，前后段须按历史时间解释。本次未覆盖原始历史记录。

已有文档的 6/10 原始动作集合准确率、10/10 终态与 payload、C07 0/2 能力调用、C03 1/2 改写重复追问均是历史指标，本次不以静态一致性证明重新背书。尤其 C09 的 Oracle `VETO` 针对脚本化“缺 query 仍调用”探针；live 若选择 RTU，其 Gate PASS 不直接是门禁错误。必须逐次区分：原始模型动作、Java 是否接受、Gate/policy 裁决、executor 是否真正执行、最终业务结果。

## 本次最小改动及未完成项

新增 `tools/tests/test_p3b_mirror_contract.py`：补足既有测试只对齐 ID 的不足，防止动作集合、Oracle 原因码、continuation 与 known gap 漂移。首次运行发现 C10/C11 的中文后续状态没有机器状态名，补注 `PARKED_USER_INPUT` 与 Java Oracle 对齐；语义、动作标签和审核状态均未改变。

新增纯 Java `P3BGateMeasurementTest` 的 6 个回归覆盖上述 UNKNOWN 分母缺陷、已测正反例、全未知、known-gap、INFRA 与 semantic-only。它直接执行真实 `P3BDecisionQuality.summarize`，不继承数据库基座；由 `backend/independent` 的显式源码闭包执行。独立编译不解析共享 starter，也不运行 Spring 业务上下文。

实际执行结果 `BUILD SUCCESSFUL in 2s`；JUnit XML `backend/independent/build/test-results/test/TEST-com.specagent.eval.P3BGateMeasurementTest.xml` 为 tests=6、skipped=0、failures=0、errors=0。首次编译暴露独立配置漏纳入 `PropertyCheck`，补齐真实源码闭包后通过；没有生成替身类型。此评分器修复仅在 dev 工作中，不能混入仅面向 main 的 CI 交付。

剩余：Java Spring/数据库运行基线与 enforced side-effect 证据需要未来获授权的新建隔离库及可用运行依赖后重跑；候选标签争议和正文语义需要人工复核；真正未参与调优的 holdout 与当前真实模型质量仍为 NOT_MEASURED。不得拿本次离线结果代替这些验收。
