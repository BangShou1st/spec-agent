# 验收修复报告：回答丢失边界 F1/F2/F3 + 缺口修复（2026-09-21）

- 分支：`codex/acceptance-fixes`，HEAD：`80f3f01414132859cedc02ae5e83da7170b016cf`（未提交、未推送）
- 范围：F1（constraint 丢失根因）／F2（类型化失败与恢复路径）／F3（规格门禁与来源引用边界）+ 缺口 P1-A（回答内容守卫）、P1-B（继承未处理回答的门禁）、P1-C（历史未处理回答恢复与 Claim 来源）、P2-C（brain→broker 超时分类）、P2-D（历史恢复成功后的会话清理）
- 不在本轮范围：F5、剩余包依赖环、其他架构重构
- 本轮新增真实模型调用：**0 次**（全部 AI 链路走 `SPEC_AGENT_MODEL_GATEWAY=fake` 与 broker 的 fake inference，见 §8）

---

## 0. 结论摘要

本轮**没有重写既有实现**，只做了三件事：收紧 sourceRefs 兼容边界、纠正三处证据链误判、完成终验并交付。三条关键更正（均为"先前结论不成立"）：

| # | 先前结论 | 更正后的结论 | 证据 |
|---|---|---|---|
| 1 | 案例1 原始追踪 "spec body contains 45 = True" | **误报**。命中来自 section `id` `45adccac-e5f3-…`；该规格的人类可读正文**不含**该约束 | `.accept-trace45-v2.md` §修正前 §4 |
| 2 | 案例1 复跑 `last_answer_visible_in_spec_refs = false` | **脚本取错键名**（读 `ref`，实际字段是 `refId`）。按 `kind/refId` 解析后 10 条引用全部可解析，且**包含含约束的那条 Answer 与 Patch** | `.accept-trace45-v2.md` §修复后 §4 |
| 3 | 案例3 `int_spec_has_no_external_markers` / `ext_refs_no_int_answer_leak` = false | **两项均为脚本误判**，案例3 无真实污染缺陷：前者命中"计费"但语义是否定约束；后者比对的是 fork 顶点快照且未纳入 `route_inherited_answers` 合法继承 | `.accept-case3-verify.md` §4–§5 |

案例1 的**真实根因**（此前只笼统归因于"F1 导致 F2"）：第 3 次回答提交在旧代码中被**静默重写为 `RESUME_ANSWER`**，复用节点上已存在的第 2 条回答并 `STATE_UPDATE_SKIPPED`，用户新输入的内容（含"45 分钟"）**从未落库**，因此规格不可能包含它。运行链证据见 §3。

另有两项"环境态"更正：

- 最新 `testCrossLanguage`（2026-09-21 13:44）的 `502 brain_failure:ModelClientError` 已**确定性复现并定位**：8100 上的 brain 是 broker 模式，但其 `SPEC_AGENT_INTERNAL_BROKER_URL` 指向的不是测试进程端口。见 §6。
- `.accept-*` 文件里出现的"中文乱码"是 **PowerShell 管道采集伪影**，不是数据损坏：`.accept-case1.json`、`.accept-case3-final.out` 等磁盘文件与 `spec_agent` / `spec_agent_test` 库内容经严格 UTF-8 解码均正常（数据库实测见 §7.4）。因此**不需要重新导出**这些证据，只需避免用管道采集。

---

## 1. sourceRefs 精确兼容规则（F1 边界收紧）

实现位置：`agent-brain/src/spec_agent_brain/decision/engine.py` → `_normalize_stray_source_refs`（模型把 `sourceRefs` 提到顶层时的唯一兼容 shim）。

规则与实现一一对应：

| # | 规则 | 实现 |
|---|---|---|
| 1 | 区分字段"缺失"与"已存在" | 用 `TOP_LEVEL_SOURCE_REFS in action` 判定，**不使用真假值** |
| 2 | action 中缺失该字段时，才允许把**合法**顶层列表搬入 | 缺键 → `action["sourceRefs"] = list(stray)`，并 `pop` 顶层 |
| 3 | 两处合法列表完全相同 → 删除顶层重复副本 | `list(action_refs) == list(stray)` → 只 `pop` 顶层 |
| 4 | 两处列表不同（含空 vs 非空）→ 拒绝，不覆盖/不合并/不取并集 | `AmbiguousSourceRefsError`，消息同时列出两侧（有界渲染） |
| 5 | 已存在但类型非法的 `action.sourceRefs` → 不修补，继续拒绝 | 非"字符串数组"直接 `return raw`，交给严格契约（`extra="forbid"` + `List[str]`）拒绝 |
| 6 | 未知字段继续拒绝；归一化后仍跑完整 schema / allowed refs / 动作资格 / Java 校验 | `_validate_output` 顺序不变；Java 侧 `AgentBrainResponseValidator` 与 `SpecSourceReferenceGuard` 未放宽 |
| 7 | 不改变 Java↔Python 正式线协议 | 未改任何 wire 字段、版本号或契约模型 |

规则 5 覆盖了先前会"被顶层值修补"的假值：`null`、`false`、`0`、`""`、`{}`、非字符串数组。关键回归：原先的测试 `test_empty_action_refs_with_top_level_refs_relocates_instead_of_rejecting`（期望空列表被覆盖）**已删除**，替换为拒绝断言。

诊断日志边界（规则 9）：`_bounded_refs` 现在同时限制条数（最多 5 条）与字符数（单条 ≤120、整体 ≤400），因此模型输出无法把无界内容写进日志行或类型化错误详情。

测试：`agent-brain/tests/test_decision_source_refs_compat.py` 由 12 条增至 **26 条**（新增空列表×非空、非空×空、6 类非法类型、4 类非字符串数组、限长诊断等）。

---

## 2. 案例1「45 分钟」结构化来源链

工具：`.accept-trace45-v2.py` → 输出 `.accept-trace45-v2.md`（直接查库，UTF-8 严格解码；只在**人类可读文本字段**内匹配空白归一化后的 `45分钟`；标识符单独列出）。原脚本 `.accept-trace45.py` 与结果 `.accept-trace45-before.md` 保留未删。

### 2.1 修复前（project `b0915621-…` / route `c483a5eb-…`）

| 环节 | 事实 |
|---|---|
| Answer 行 | 仅 2 条：`72a09f9c`（第1次）与 `c0134464`（第2次）；全项目含"45分钟"的 Answer = **0 条** |
| 第3次提交 | 请求体确为"希望会议尽量控制在 45 分钟内…"，但 POST 返回的 `operation` 是 **`RESUME_ANSWER`**（非 `ANSWER_TIP`），run `ed1f4aeb` 的 `produced_answer_id` = **`c0134464`（第2条回答）** |
| 恢复事件 | `STATE_UPDATE_SKIPPED {"reason":"patch_exists"}` → `RUN_FAILED ActionIneligibleException` |
| ContextSnapshot | 生成规格所用快照 `4fcdcb52` 只含 `72a09f9c` + `c0134464` 及其 patch |
| 规格 `efac7b80` | 正文/unresolvedItems 均**不含**"45分钟"；`source_refs(3)` = R1 的 answer、R1 的 patch、R2 的 answer，逐条解析后**无一含该约束** |
| 旧脚本误报来源 | `sections` 序列化 JSON 里唯一含裸"45"的是 **section id `45adccac-e5f3-4245-a8e8-aee038612163`** |

**根因（明确）**：旧 `ANSWER_TIP→RESUME_ANSWER` 重写只判断"该节点已有回答"，未比较内容，于是把用户的新提交当成对旧回答的重试，新内容被静默丢弃。这是 F1 的直接失效点，**不是**"F2 的后果"。当前实现已按内容守卫（`submittedContentDiffers` → `ANSWER_CONTENT_MISMATCH`），且节点已非 tip 时先给 `ANSWER_ALREADY_FINALIZED`。

### 2.2 修复后（project `3c558a40-…` / route `b90d3975-…`）

| 环节 | 事实 |
|---|---|
| Answer 行 | 3 条；第 3 条 `60778b3c` free_text 含"45 分钟" → 约束命中=**是** |
| Patch | `1630a51a` 的 `claims[0].text` = "每场会议必须控制在45分钟以内" → 命中=**是** |
| 快照 | `e3b9a07c` / `f383d769` 的 `included_answer_ids` 含 `60778b3c` |
| 规格 `9b5086b9` | 正文（功能需求/非功能需求/验收标准/风险假设）与 unresolvedItems 均含该约束 |
| `source_refs(10)` | 逐条解析：4×NODE、3×ANSWER、3×PATCH，**全部可解析、无悬空**；其中 `ANSWER 60778b3c` 与 `PATCH 1630a51a` 均"含45分钟=是" |

即：先前 `last_answer_visible_in_spec_refs=false` 纯属脚本键名错误（`ref` vs `refId`）。

---

## 3. 案例3 双路线规格对照

项目 `dc6953ff-…`，源路线 `2742b6be`，fork 路线 `d3c7be9f`（`branch_at_node` = `16a1008e`）。工具：`.accept-case3-verify.py` → `.accept-case3-verify.md`（枚举该路线**全部** spec 快照，按快照冻结集合与 `route_inherited_answers` 逐条裁定引用，并按"正向需求/否定约束/假设风险未决"给关键词命中定性）。

### 3.1 三条快照与引用裁定

| 快照 | 路线 | tip | 快照冻结 | 引用裁定 |
|---|---|---|---|---|
| `8e09f7ff`（by run `15e24c9d`，fork 顶点） | 对外 | `16a1008e`（fork 点） | answer `9fc0f057` / patch `1c0811bd` | ANSWER `9fc0f057` = **分叉前合法继承**；PATCH `1c0811bd` = 合法继承；CONTEXT `1fdee723` = 本次运行快照（合法） |
| `4175844e`（INT，run `501d8704`） | 源路线 | `8dfaa7e7` | 2 answers / 2 patches | 7 条引用全部为**本路线自有/血缘节点**；越界 0；未引用对外路线的 answer |
| `b301c453`（EXT，run `316be34b`） | 对外 | `0e8a2f94` | answer `9fc0f057`(继承)+`7575285f`(自有) / patch `1c0811bd`(继承)+`2b87a952`(自有) | 继承 2 条 = **合法继承**；自有 2 条 + 3 血缘节点；**越界 0、兄弟路线引用 0**；未引用源路线 fork 后新增的 answer `9369dfec` |

### 3.2 两项旧检查的更正

**`int_spec_has_no_external_markers` → 更正为 true。** INT 规格（`4175844e`）"计费"命中 3 处，逐条定性**全部是否定约束**：

- `[范围] - 不需要计费功能，系统作为免费内部工具运行`（"不需要"位于"计费"之前）
- `[非功能需求与约束] - 无计费功能：…`（"无"在前）
- `[验收标准] - 系统不涉及任何计费功能`（"不涉及"在前）

按极性统计：**正向对外需求 0 条**；"多租户/付费客户/按用量"三类标记在 INT 规格正文中一次未出现。旧脚本用 `"计费" in text` 做子串判断，把否定约束误读成需求。

**`ext_refs_no_int_answer_leak` → 更正为 true。** 旧脚本有两个独立缺陷：

1. 它取 `/routes/{id}/specs` 的第 0 条，实际拿到的是 **fork 顶点快照 `8e09f7ff`**（该路线共 2 条快照），而不是含对外回答的 `b301c453`；
2. 它按 `answers.route_id` 判来源，未纳入 `route_inherited_answers` 登记的合法继承前缀，于是把 `9fc0f057`（继承）当成"泄漏"；第 3 条引用 `1fdee723` 实为 `kind=CONTEXT`（本次运行快照），不可能构成 answer 泄漏。

更正后的结构判定（对全部三条快照）：**不可解析引用 0、越界引用 0、兄弟路线新增内容引用 0**。

### 3.3 `SpecSourceReferenceGuard` 边界复核

逐条核对 `backend/src/main/java/com/specagent/agent/gates/SpecSourceReferenceGuard.java`，边界为：

- `CONTEXT`：必须等于本次运行的上下文快照；
- `NODE`：必须在 `includedNodeIds` 内；
- `ANSWER`：必须属于当前路线，**或**属于 `routeHistoryResolver.resolveEffectiveAnswers(routeId, snapshot.includedNodeIds)` 解析出的合法继承集合，**且**必须在 `includedAnswerIds` 内；
- `PATCH`：同理（继承补丁按"来源 answer 可继承"推导），且必须在 `includedPatchIds` 内。

结论：**合法继承放行；兄弟路线、跨项目、以及快照之后新增的答案/补丁一律拒绝**（因为不在快照冻结集合里）。测试 `SpecSourceReferenceGuardTest` 已有 4 条针对该边界的用例（继承放行、继承但不在快照拒绝、继承补丁放行、同项目兄弟路线拒绝）。案例3 无真实缺陷，因此**未修改任何门禁代码**，只修正了检查脚本；原始结果 `.accept-case3-final.json` / `.out` 全部保留。

---

## 4. 恢复路径与规格门禁的验收证据

| 验收条件 | 覆盖证据 |
|---|---|
| Answer/Patch 已保存后 DECISION 失败 → 复用检查点，不重复 Answer/Patch/图变更 | `TypedRunFailureIntegrationTest.contractFailureIsTypedAndTheRetryReusesTheSameCheckpoint`（断言 stateUpdates=1、decisions 不自动重试、1 answer/1 patch，恢复后仍为 1/1 且 `producedAnswerId` 不变） |
| 未完成 STATE_UPDATE 的回答 → 生成规格被明确阻止 | HTTP 侧 `ArtifactGenerationAnswerGateIntegrationTest.generationIsRejectedWhileTheTipAnswerHasNoCheckpoint`（409 `ANSWER_CYCLE_INCOMPLETE`）、排队侧 `queuedGenerationFailsClosedWhenTheTipAnswerIsNeverProcessed` 与 `TypedRunFailureIntegrationTest.queuedArtifactRunFailsClosedOnAnUnprocessedTipAnswer`（无快照、无模型调用） |
| HTTP 门禁与后台运行时门禁一致（含继承答案场景） | 两侧同一判据 `findAnswerForNode` + `findBySourceAnswerId`；继承场景由 `ArtifactRouteBindingIntegrationTest`（fork 后门禁保持路线局部）与 §3.1 的真实 fork 快照生成成功共同覆盖 |
| 相同提交可恢复；不同新内容不被静默当旧回答 | `AnswerSubmissionGuardIntegrationTest` 7 例：不同 freeText 拒绝且不改动已存回答、相同 freeText 恢复、无内容恢复、无 answerId 的 resume 解析到既有回答、改选选项拒绝、未改选项恢复、已非 tip 一律 fail-closed |
| 不绕过 RESOLVED_BLOCKER、不放宽动作资格 | `ActionEligibilityEnforcementIntegrationTest`（3 例，含 `constraints` 不含 `UNRESOLVED_BLOCKER`）与 `ActionEligibilityCharacterizationTest`、`DeterministicFakeClarificationTest` 保持通过 |
| 用户可见失败原因 + 正确入口恢复；无无界自动模型重试 | 后端：`RunFailureReasonsTest` 7 例 + `FailureTaxonomyTest`；前端：`errorCopy.spec.ts`；**真实界面**见 §5；重试仍只由 brain 内部的一次有预算修复承担 |
| 资格冲突／未知结果／超时分别处理 | `TypedRunFailureIntegrationTest` 的 timeout / ungrounded / provider / contract 四例分别断言 `brain_timeout`、`model_ungrounded_reference`、`model_provider_failure`、`model_contract_violation`；`RemotePythonDecisionEngineFailClosedTest` 覆盖分类映射 |

---

## 5. 真实界面（浏览器）恢复流程验证

环境：后端 `SERVER_PORT=8080 SPEC_AGENT_MODEL_GATEWAY=fake`、前端 Vite 5173、brain 8100（broker→8080）。工具：仓库自带 Playwright（独立脚本，不用 runner，规避 runner 失败后 worker 不退出被强杀的既有环境缺陷）。

**为什么这样构造**：回答在 claim 时先落库、随后才调模型，因此"停掉 brain 再提交回答"即可得到「回答已保存但 STATE_UPDATE 未完成」的真实状态——**全程不需要任何真实模型调用**。

| 阶段 | 操作 | 界面实测结果 |
|---|---|---|
| P1（brain 停止） | 在工作区输入并提交回答 | 出现恢复提示：标题「回答已经保存」/ 正文「后续生成没有完成，不需要重新填写回答」/ 按钮「**继续生成**」；节点卡片显示类型化失败原因「模型服务暂时不可用，本次生成未完成，请稍后重试」并标「需要处理」；底部 toast「回答已保存，后续生成未完成」 |
| P1 续 | 点「为当前路线生成规格」 | 红色错误条：「该问题已保存回答，但后续处理尚未完成，无法生成规格。请先重试该回答，再生成规格」+ 代码 `ANSWER_CYCLE_INCOMPLETE` + 按钮「重新请求」。按钮并非预置禁用（`isDisabled=false`），说明门禁是运行/HTTP 侧拦截 |
| P2（brain 恢复） | 点恢复入口「继续生成」 | 恢复提示消失；原节点由「需要处理」变为「已确认」；路线节点 1 → 2 并自动产出下一个问题；toast「回答已记录」 |
| P3 | 再次生成规格 | 成功产出快照，正文含被恢复回答的约束：「每场复盘会必须控制在 30 分钟以内」「每场复盘会必须产出责任人与跟进时间」 |

截图证据：`.accept-scratch/ui-p1-before-submit.png`、`ui-p1-after-failure.png`、`ui-p1-spec-gate.png`、`ui-p2-after-recovery.png`、`ui-p3-spec-generated.png`；结构化结果：`ui-verify-phase{1,2,3}.json`。
验证项目：`4fe223cf-cb57-4c05-8511-dc0f845f9de6`（保留，未删除）。

> 说明：`bsk`（browser-skill）在本机可用，但 `bsk status` 显示 `browsers: []`——需要用户先在浏览器里连接扩展；本轮改用仓库既有 Playwright 完成同一验证，未修改任何浏览器扩展或设置。

---

## 6. 跨语言门禁：`ModelClientError` 根因与复现

### 6.1 事实

`PythonBrainCrossLanguageIntegrationTest` 只在 `@BeforeAll` 检查 `localhost:8100/health` 可达即运行（不可达才 skip），而它要求 8100 上的 brain 处于 **broker 模式且回调指向本测试进程端口**（`SPEC_AGENT_CROSS_LANG_PORT`，本例 8099）。测试进程端口与 brain 配置由人工约定，测试本身无法校验。

### 6.2 A/B 复现（两次运行，唯一变量 = brain 的 broker URL）

| 运行 | 8100 brain 的 `SPEC_AGENT_INTERNAL_BROKER_URL` | 结果 |
|---|---|---|
| A | `http://localhost:8099/...`（测试进程端口，brain 与测试同时存活） | **2 tests / 0 failed / 0 skipped**（BUILD SUCCESSFUL） |
| B | `http://localhost:8080/...`（死端口，等价于 13:44 时的开发 brain 配置） | **2 tests / 2 failed**，`Caused by: HttpServerErrorException$BadGateway: 502 Bad Gateway: {"detail":"brain_failure:ModelClientError"}` |

运行 B 的失败文本与 13:44 记录的 `HTTP 502 {"detail":"brain_failure:ModelClientError"}` **逐字一致**。日志：`.accept-scratch/xlang-proxy.log`（A）、`xlang-deadbroker2.log` + `xlang-deadbroker-result.txt`（B）。

**结论**：这不是跨语言代码缺陷，而是**环境耦合**——8100 上跑的是"指向别处"的 brain。另外两个已探明的影响因素，供后续排查参考：

1. 本机 shell 环境注入了 `HTTP_PROXY=http://127.0.0.1:51027` 且 **`NO_PROXY` 为空**；`BrokerModelClient` 使用默认 `httpx.Client`（`trust_env=True`），会把"本机 broker"调用交给代理，代理对不可达上游返回 502（实测：同 URL 直连 `000`、经代理 `502`）。因此同一故障既可能表现为连接被拒，也可能表现为 502。
2. `testCrossLanguage` 依赖"本测试进程端口 == brain 的 broker URL 端口"这一人工约定；建议后续（**本轮未改，属范围外**）在 `@BeforeAll` 增加一次 broker 握手探测，或让端口由测试自行导出/写入环境，避免"健康但接错线"的 brain 造成假失败。

---

## 7. 本轮验证：命令与真实结果

### 7.1 Python（确定性）

```bash
cd agent-brain
.venv/Scripts/python.exe -m pytest tests/test_decision_source_refs_compat.py -q   # 26 passed
.venv/Scripts/python.exe -m pytest -q                                            # 130 passed, 2 warnings
```
（基线 116 → 130，增量即本轮新增的 14 条 sourceRefs 边界用例；原 12 条同文件用例保留并改写。）

### 7.2 后端（确定性；**注意必须让 profile 的 worker 开关生效**）

```bash
cd backend
SPRING_PROFILES_ACTIVE=test SPEC_AGENT_MODEL_GATEWAY=fake SPEC_AGENT_MODEL_INFERENCE=fake \
  ./gradlew.bat cleanTest test
# classes=244  tests=1496  failures=0  errors=0  skipped=0   BUILD SUCCESSFUL
```

**重要更正（避免复现踩坑）**：不要给整轮 `test` 传 `SPEC_AGENT_BRAIN_WORKER_ENABLED=true`。该环境变量优先级高于 `application-test.yml` 的 `spec.agent.brain.worker.enabled: false`，会把**后台 brain worker 轮询器**打开，与同步测试驱动器争抢共享队列/在夹具清理期间写入快照，产生两类与产品无关的失败：

- `ScriptedModelGatewayFullLoopIntegrationTest.staleTargetFailurePersistsNothing` → `Expected queued answer-cycle run …`（期望的 run 被后台轮询器先取走）
- `ActionEligibilityEnforcementIntegrationTest.unresolvedConflictAllowsOrdinaryCreateNodeAndReachesPolicy` → 清理时 `context_snapshots_route_id_fkey` 外键冲突（清理期间新快照被写入）

两次带该变量的运行各失败 1 例（且**两次是不同用例**），去掉后同一套件 0 失败。留档：`backend-test.log`、`backend-test2-summary.txt`（失败详情）、`backend-test3.log`（通过）。

架构测试（同一套件内）：`ArchitectureTests` 29 例 0 失败（含 `packagesAreFreeOfCycles`）、`AgentBoundaryArchitectureTests` 14 例 0 失败。`backend/archunit_store/` 与 `archunit.properties` **git 无变更**（文件时间仍为 2026-09-20 15:43），未扩大也未重建冻结基线。

### 7.3 evalBFast（确定性）

```bash
cd backend && SPRING_PROFILES_ACTIVE=test SPEC_AGENT_MODEL_GATEWAY=fake SPEC_AGENT_MODEL_INFERENCE=fake \
  ./gradlew.bat evalBFast
# classes=17  tests=84  failures=0  skipped=0（eval summary: total=14 passed=14 failed=0）
```

### 7.4 跨语言门禁

```bash
# 1) 专用 brain（一个工具调用内启动并保持）：
cd agent-brain
SPEC_AGENT_BRAIN_MODEL_MODE=broker \
SPEC_AGENT_INTERNAL_BROKER_URL=http://localhost:8099/internal/v1/model-inference \
SPEC_AGENT_BRAIN_INTERNAL_SECRET=dev-internal-secret \
  .venv/Scripts/python.exe -m uvicorn spec_agent_brain.app:app --host 0.0.0.0 --port 8100
# 2) 门禁：
cd backend && SPRING_PROFILES_ACTIVE=test SPEC_AGENT_MODEL_GATEWAY=fake \
  SPEC_AGENT_CROSS_LANG_PORT=8099 ./gradlew.bat cleanTestCrossLanguage testCrossLanguage
# tests=2 failures=0 errors=0 skipped=0  → 实际通过（非 skipped）
```
注意测试任务会被 Gradle 判为 UP-TO-DATE，需 `cleanTestCrossLanguage`（或 `--rerun-tasks`）才是真实重跑。

数据库侧实测（UTF-8 正常，无乱码）：

```
spec_agent（开发库）：dc6953ff / c96da33c / 141f6c62 / 7e62dfae / b0915621 … 的 RUN_FAILED payload
  已带 errorCode+summary，如 {"reason":"model_provider_failure","summary":"模型服务调用失败，本次生成未完成，请稍后重试",…}
spec_agent_test：244 类结果 XML 全部解析正常
```

### 7.5 前端

```bash
cd frontend
node node_modules/vitest/vitest.mjs run     # Test Files 100 passed / Tests 816 passed
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit   # exit 0
```

### 7.6 定向界面验证

见 §5（`ui-verify.mjs` 三阶段，全部通过）。

---

## 8. 真实模型调用统计

| 项 | 数值 |
|---|---|
| 本轮新增真实（含免费）模型调用 | **0 次**；0 次失败 |
| 本轮 AI 链路所用网关 | 后端 `SPEC_AGENT_MODEL_GATEWAY=fake` + brain broker → Java fake inference（跨语言两轮 4 次 broker 调用、界面三阶段若干次 DECISION/STATE_UPDATE/ARTIFACT 全部为 fake） |
| 本轮 brain 实际计数（`/health`） | `stateUpdates=1, decisions=1`（均为界面恢复流程产生，fake） |
| 未使用付费模型、未自动切换模型 | 是（仅 `fake`；未触碰任何 provider 配置） |
| 历史记录（上一会话，仅引用未重跑） | `.accept-rerun.json`：brain stateUpdates 2→8、decisions 6→18；`.accept-case3-final.json`：stateUpdates 7→11、decisions 14→20 |

**关于"案例1/3 必要真实链路复验"的判断**：本轮代码改动只作用于"模型输出把 sourceRefs 提到顶层"这一畸形形态的**解析边界**（案例1/3 的实际输出都是合规范式，不经过该分支），因此没有用真实模型重跑这两个案例——那只会新增项目与调用而不增加新信息；对它们更有价值的复验是 §2/§3 的**逐字段结构化取证**，已覆盖原先存疑的每一处。跨语言门禁（真实 Python brain ↔ Java broker）与 26 条 Python 用例则直接覆盖了被改动的那段代码。

---

## 9. 环境最终状态

| 组件 | 状态 |
|---|---|
| `spec-agent-postgres`（Docker，5434→5432） | Up（healthy） |
| brain 8100 | **LISTENING，`modelMode=broker`，broker→`http://localhost:8080/…`**（等价于 `start-dev.bat` 的配置；原 8100 进程在切换实验中停止后已按此配置恢复） |
| 后端 8080 / 前端 5173 / 8099 | 已停止（与开工时一致：开工时仅 8100 在监听） |
| 数据库 | `spec_agent`、`spec_agent_test` **均未清库、未重置**；验收项目与证据全部保留 |
| git | 未提交、未推送、未 reset/clean/stash、未改写 refs；HEAD 仍为 `80f3f01` |

> 限制说明：本会话启动的 8100 brain 挂在会话后台任务下，会话结束会随任务回收。需要长期常驻时请用 `start-dev.bat`（或按其 Step 2 的环境变量手工启动）。

---

## 10. 修改文件清单

### 10.1 源码（本轮由上一阶段实现，本轮仅再次审查，未重写）

Python（`agent-brain/`）：`src/spec_agent_brain/decision/engine.py`、`decision/__init__.py`、`artifact/engine.py`、`artifact/__init__.py`、`prompts/decision.py`、`prompts/artifact.py`

Java（`backend/src/main/java/`）：`agent/decision/BrainFailureCode.java`(新)、`agent/decision/AgentBrainUnavailableException.java`、`agent/decision/RemotePythonDecisionEngine.java`、`agent/gates/SpecSourceReferenceGuard.java`、`agent/runtime/IncompleteAnswerCycleException.java`(新)、`agent/runtime/RunFailureReasons.java`(新)、`agent/runtime/{AnswerCycleService,ArtifactCycleService,ContinuationCycleService,DecisionCycleService,NodeQueryService,ReplacementCycleService,RunWorker}.java`、`application/agent/AnswerCycleRunCommandService.java`、`eval/LiveFailureClassifier.java`

前端：`frontend/src/api/errorCopy.ts`

### 10.2 测试

- Python：`tests/test_decision_source_refs_compat.py`（**本轮改写**：删除"空列表可被覆盖"的错误期望，补 14 条边界）、`tests/test_decision_prompt.py`、`tests/test_artifact_source_ref_errors.py`(新)
- Java：`agent/runtime/{RunFailureReasonsTest,TypedRunFailureIntegrationTest}.java`(新)、`api/agent/{AnswerSubmissionGuardIntegrationTest,ArtifactGenerationAnswerGateIntegrationTest}.java`(新)、`agent/decision/RemotePythonDecisionEngineFailClosedTest.java`、`agent/gates/SpecSourceReferenceGuardTest.java`、`agent/runtime/ArtifactRouteBindingIntegrationTest.java`、`api/agent/AnswerCycleRunApiIntegrationTest.java`、`eval/FailureTaxonomyTest.java`、`agent/ScriptedModelGatewayFullLoopIntegrationTest.java`（**本轮 1 处 test-only 加固**：本地取件助手由"取队列头再比对"改为 `claimAnswerCycleRun(id)`，与 `AnswerCycleTestDriver`、`AnswerCycleClaimOwnershipIntegrationTest` 及已合并的 `8b0def2` 同一契约；该改动**不是**通过测试的必要条件，可单独回退）
- 前端：`src/api/__tests__/errorCopy.spec.ts`

### 10.3 临时证据（工作区保留，未提交）

根目录：`.accept-case1.json`、`.accept-case3*.{json,py,out,md}`、`.accept-cases.*`、`.accept-body.json`、`.accept-drive.py`、`.accept-evidence.md`、`.accept-frozen-decision.json`、`.accept-graph.json`、`.accept-rerun.{json,py}`、`.accept-run-body.json`、`.accept-spec.json`、`.accept-trace45-before.md`、`.accept-trace45.py`、`.accept-project-id`、`.accept-run-id`

**本轮新增**：`.accept-trace45-v2.py` / `.accept-trace45-v2.md`（案例1 修正版追踪）、`.accept-case3-verify.py` / `.accept-case3-verify.md`（案例3 修正版对照）

`.accept-scratch/`（全部为诊断/日志/截图，未提交）：`probe1.py`、`probe_broker.py`、`probe-broker.txt`、`list-projects.py`、`ui-prep.py`、`ui-verify.mjs`、`ui-verify-phase{1,2,3}.json`、`ui-p1-before-submit.png`、`ui-p1-after-failure.png`、`ui-p1-spec-gate.png`、`ui-p2-after-recovery.png`、`ui-p3-spec-generated.png`、`backend-test{,2,3}.log`、`backend-test{2,3}-summary.txt`、`evalbfast.log`、`frontend-vitest.log`、`frontend-tsc.log`、`xlang-*.log`、`single-class.log`、`final-env.txt`、`final-files.txt`、`arch-check*.txt`、`proxy-env.txt`、`testdb-orphans.txt` 等

---

## 11. 仍未解决的问题

1. **`testCrossLanguage` 的"健康即运行"判定过松**：只要 8100 有 brain 就运行，接错 broker URL 时表现为 502 假失败而非 skip。建议（未做，属范围外）加一次 broker 握手探测或把端口写入测试可读的环境变量。
2. **`BrokerModelClient` 使用 `httpx` 默认 `trust_env=True`**：本机若存在 `HTTP_PROXY` 且 `NO_PROXY` 为空，brain 对**本机** broker 的调用会被代理劫持（实测 502）。建议改用 `httpx.Client(trust_env=False)`，或至少在启动脚本里固定 `NO_PROXY=localhost,127.0.0.1`。本轮以环境方式规避，未改代码。
3. **测试库 `spec_agent_test` 已高度污染**（1110 projects / 1089 routes / 1041 context_snapshots，来自长期累积）。夹具清理会随污染程度偶发外键冲突。建议定期重置测试库（本项目既有约定）；本轮按约束**未清库**。
4. **共享 ANSWER_CYCLE 队列的测试隔离**：仍有若干测试助手以"取队列头"取件（`RepairApiIntegrationTest`、`CapabilityAnswerCycleIntegrationTest` 等）。它们不断言身份，因此不会像本轮修的那处那样失败，但语义上仍可能执行他人排队的 run。属 tech-debt #13 的残留面。
5. **待办残留**：`F5` 与剩余包依赖环按指示未处理（冻结基线保持 2026-09-20 状态）。
6. **启动方式限制**：从工具调用启动的常驻进程会在调用结束时被回收；跨调用存活需使用会话后台任务（本轮已验证）或由用户经 `start-dev.bat` 启动。
7. **非 brain 类失败的文案**：`IllegalStateException` / `StaleRunTargetException` / `ActionIneligibleException` 等仍以异常类名作为 `reason` 且无用户文案（`AGENT_RUN_FAILED` 通用文案兜底）。是否补齐属产品决策，本轮未动。

---

## 12. 缺口修复（P1-A / P1-B / P2-C）

### 12.1 P1-A：省略 nodeId 可绕过回答内容校验

**根因**：原实现只在 `request.nodeId() != null` 时才走回答存在性检查与 `ANSWER_TIP` → `RESUME_ANSWER` 重写。当请求仅携带 `answerId`（省略可选 `nodeId`）时，整个守卫被跳过，RunWorker 按 `answerId` 恢复旧回答，新提交的 `freeText` 被静默丢弃。

**修复机制**：`AnswerCycleRunCommandService.createRun` 中，对所有回答操作（`ANSWER_TIP` / `RESUME_ANSWER`）统一先调用 `resolveAnswerOperationTarget`，该方法以 `answerId` 为最高优先级身份，校验项目/路线/节点一致性；再执行 `submittedContentDiffers` 内容守卫，不同则返回 `409 ANSWER_CONTENT_MISMATCH`。`resolveAnswerOperationTarget` 在路线无 tip 节点时返回 null（全新项目走提交流水线），避免 NPE。

**关键行为**：
- `answerId` + 不同 `freeText` → 409 ANSWER_CONTENT_MISMATCH
- `answerId` + 相同 `freeText` → 202 RESUME_ANSWER（幂等重试）
- `answerId` + 无 `freeText`（纯恢复） → 202 RESUME_ANSWER
- 无 `nodeId` 的无内容恢复和相同内容重试保持正常
- 跨路线 `answerId` → 409 ANSWER_ROUTE_MISMATCH
- `answerId` 与 `nodeId` 不一致 → 409 ANSWER_TARGET_MISMATCH

**证据**：`AnswerSubmissionGuardIntegrationTest` 10 例全部通过（含 `resumeByIdWithoutNodeIdStillEnforcesTheContentGuard`、`resumeByIdWithoutNodeIdAcceptsTheIdenticalResubmission`、`contentlessResumeByIdWithoutNodeIdStillResumesThePersistedAnswer`、`crossRouteAnswerIdIsRejectedWithoutSideEffects`）。修复前原测试未覆盖省略 `nodeId` 的答案例。

### 12.2 P1-B：继承未处理回答的分支能绕过规格生成门禁

**根因**：`requireTipAnswerProcessed`（入队门禁）与 `failIfTipAnswerUnprocessed`（执行门禁）原来都只检查路线自身的 tip Answer。分叉时，源路线分叉点上的回答（STATE_UPDATE 未完成）通过 `route_inherited_answers` 原样继承到分支，但两道门禁均未把继承前缀纳入判定，于是分支能生成规格，规格中静默遗漏用户的继承回答。
**实际复现**：Answer 已保存、无对应 AnswerPatch → 从该节点分叉 → 分支 `GENERATE_ARTIFACT` 202 → 执行前门禁也不拒绝。

**修复机制**：引入共享的 `AnswerProcessingGate.firstUnprocessedAnswer(routeId, tipNodeId)`，以 `RouteHistoryResolver.resolveEffectiveAnswers`（路线自有回答 + 合法继承前缀，按 tip 谱系排序）为判定依据。入队门禁（`requireTipAnswerProcessed`）和执行门禁（`failIfTipAnswerUnprocessed`）使用同一个判据。已有 AnswerPatch 的回答视为已完成，后续 DECISION 失败不重开门禁。仅在路线 tip 谱系上的回答被判定，兄弟路线的无关回答不阻塞。

**关键行为**：
- 路线自身未处理 tip 回答 → 入队 409 ANSWER_CYCLE_INCOMPLETE
- 继承未处理回答 → 入队和执行前均 409
- 继承回答已处理完成 → 分支可正常生成规格
- 未处理回答不在 tip（在谱系中部） → 仍被有效历史捕获
- 无关兄弟路线的未处理回答 → 不阻塞

**证据**：`InheritedAnswerArtifactGateIntegrationTest` 5 例全部通过（含 `enqueueIsRejectedWhileTheInheritedAnswerIsUnprocessed`、`queuedGenerationFailsClosedOnTheInheritedUnprocessedAnswer` 直接通过 `RunService` 入队测执行门禁、`unprocessedAnswerThatIsNoLongerTheTipIsStillCaughtByTheEffectiveHistory`、`branchCanGenerateOnceEveryInheritedAnswerIsProcessed`、`unrelatedSiblingRouteWithUnprocessedAnswerDoesNotBlock`）。`ArtifactGenerationAnswerGateIntegrationTest` 3 例同步通过。`ArtifactRouteBindingIntegrationTest` 3 例（路线绑定与执行门禁解耦）通过。

### 12.3 P2-C：brain 调 broker 的超时被归为普通 provider failure

**根因**：`BrokerModelClient` 把 `httpx.HTTPError`（含 `TimeoutException`）一律包装为 `ModelClientError`，`app.py` 的 `_fail` 按类名输出 `brain_failure:ModelClientError`，Java `RemotePythonDecisionEngine.classifyBrainFailureDetail` 把 `ModelClientError` 映射为 `MODEL_PROVIDER_FAILURE`。于是 brain 调 broker 的 `ReadTimeout` / `ConnectTimeout` 被误归为 provider 故障，用户看到「模型服务调用失败」而非「超时」。

**修复机制**：
1. Python `base.py` 新增 `BrokerTimeoutError(ModelClientError)` 子类。
2. `broker_client.py` 捕获 `httpx.TimeoutException`（在 `httpx.HTTPError` 之前）并抛出 `BrokerTimeoutError`。
3. `app.py` 的 `_fail` 按类名输出 `brain_failure:BrokerTimeoutError`。
4. Java `classifyBrainFailureDetail` 新增 `BrokerTimeoutError` → `BRAIN_TIMEOUT` 映射。

**关键行为**：
- `httpx.ReadTimeout` / `httpx.ConnectTimeout` → `BrokerTimeoutError` → HTTP 502 `brain_failure:BrokerTimeoutError` → Java `BRAIN_TIMEOUT`
- 普通 `httpx.HTTPError`（连接被拒、5xx 等） → `ModelClientError` → `MODEL_PROVIDER_FAILURE`（不变）
- 其他错误分类（`BrainContractError`、`UngroundedReferenceError`、`AmbiguousSourceRefsError`）保持不变

**证据**：Python `test_broker_timeout.py` 4 例全部通过（`BrokerTimeoutError` 是 `ModelClientError` 子类、`ConnectTimeout` 同样归为超时、普通 `httpx.HTTPError` 不升级为超时、HTTP 边界输出 `brain_failure:BrokerTimeoutError`）。Java `RemotePythonDecisionEngineFailClosedTest` 新增 `brokerTimeoutCallIsReportedAsATimeoutNotAsProviderFailure` 与 `brainFailureDetailClassificationIsExplicit` 中 `BrokerTimeoutError` → `BRAIN_TIMEOUT` 断言，全部通过。

### 12.4 本轮修改文件清单

**源码**：
- `agent-brain/src/spec_agent_brain/model_client/base.py`（新增 `BrokerTimeoutError`）
- `agent-brain/src/spec_agent_brain/model_client/broker_client.py`（捕获 `httpx.TimeoutException`，抛出 `BrokerTimeoutError`）
- `agent-brain/src/spec_agent_brain/model_client/__init__.py`（导出 `BrokerTimeoutError`）
- `backend/src/main/java/com/specagent/agent/decision/RemotePythonDecisionEngine.java`（`classifyBrainFailureDetail` 新增 `BrokerTimeoutError` → `BRAIN_TIMEOUT`）
- `backend/src/main/java/com/specagent/application/agent/AnswerCycleRunCommandService.java`（`resolveAnswerOperationTarget` 在路线无 tip 节点时返回 null，避免 NPE）

**测试**：
- `agent-brain/tests/test_broker_timeout.py`（新，4 例）
- `backend/src/test/java/com/specagent/api/agent/InheritedAnswerArtifactGateIntegrationTest.java`（`queuedGenerationFailsClosedOnTheInheritedUnprocessedAnswer` 改为直接通过 `RunService` 入队测执行门禁）
- `backend/src/test/java/com/specagent/agent/decision/RemotePythonDecisionEngineFailClosedTest.java`（新增 `brokerTimeoutCallIsReportedAsATimeoutNotAsProviderFailure` 与 `brainFailureDetailClassificationIsExplicit` 中 `BrokerTimeoutError` 映射断言）

### 12.5 本轮验证：命令与真实结果

```bash
# Python（确定性）
cd agent-brain
.venv/Scripts/python.exe -m pytest -q
# 134 passed, 2 warnings（130 基线 + 4 新增 broker timeout 用例）

# 后端（确定性）
cd backend
SPRING_PROFILES_ACTIVE=test SPEC_AGENT_MODEL_GATEWAY=fake SPEC_AGENT_MODEL_INFERENCE=fake \
  ./gradlew.bat cleanTest test
# 1507 tests completed, 0 failures, 0 errors, 0 skipped   BUILD SUCCESSFUL
# （这是 P1-C 补充前的阶段性结果；最新最终全量结果见 §13.4）
```

### 12.6 环境最终状态

| 组件 | 状态 |
|---|---|
| git | 未提交、未推送、未 reset/clean/stash、未改写 refs；HEAD 仍为 `80f3f01` |
| 数据库 | `spec_agent`、`spec_agent_test` 均未清库、未重置 |
| 本轮启动的服务 | 无（全部 fake/mock，未启动真实 brain 或 broker） |

### 12.7 真实模型验证边界

本轮所有验证均使用 `SPEC_AGENT_MODEL_GATEWAY=fake` 与 Python `FakeModelClient` / Java fake inference，**未调用任何付费模型**。P2-C 的 `BrokerTimeoutError` 路径通过 `unittest.mock.patch` 注入 `BrokerTimeoutError` 验证 HTTP 边界输出与 Java 分类映射，未通过真实超时复验。真实超时行为（`httpx.ReadTimeout` 从 socket 层抛出）依赖 httpx 库实现，本轮未改变超时配置或传输层。

---

## 13. 最新审查 P1：历史未处理回答恢复（2026-09-21）

### 13.1 根因与修复前证据

旧流程只在路线当前 tip 上判断恢复资格。`DRAFT_QUESTION` 可以在 Answer 已落库但没有 `AnswerPatch` 时推进路线 tip；随后规格门禁正确识别到欠处理回答，却把 `RESUME_ANSWER` 限制为“回答节点必须是当前 tip”，因此同一回答同时满足“必须恢复”和“不能恢复”。审查夹具 `ReviewBoundaryTest.java` 的 `pendingAncestorMustStillBeRecoverableAfterDraftAdvancesTip` 及其 `reviewOnlyTest` XML 保留了修复前证据：DRAFT 成功、GENERATE_ARTIFACT 返回 `409 ANSWER_CYCLE_INCOMPLETE`，随后原 answerId + 正确 sourceRouteId 恢复仍返回 `409 ANSWER_ALREADY_FINALIZED`。

复核又发现两个边界错误：历史恢复的 `STATE_UPDATE` 仍把当前 tip 当作 Claim 来源节点；前端则把“tip 已前移”误当作恢复成功，在失败或网络结果未知时删除恢复会话并返回成功。

### 13.2 新推进限制与历史恢复机制

- `AnswerProcessingGate` 统一按 `RouteHistoryResolver` 的有效历史（路线本身 + 合法继承前缀）检查所有无 Patch Answer；无关兄弟路线不参与判定。
- DRAFT 入队前、`DecisionCycleService` 执行前和 `GraphInvariantValidator` 实际图变更边界均 fail-closed。这样既阻止新状态产生，也覆盖“先入队、执行前才发现状态变化”的竞态；已存在 AnswerPatch 的回答不会因后续 DECISION 失败重新变成欠处理。
- 历史非 tip 恢复只复用不可变 Answer 和原始 pre-answer `ContextSnapshot`，只运行必要的 STATE_UPDATE；已有 Patch 使用 `STATE_UPDATE_SKIPPED` 并直接完成。不会重放后续 DECISION，不创建新 Answer，不重复 Patch，不改路线 tip，也不追加图节点。
- 原始快照缺失时返回明确的 `LEGACY_FROZEN_INPUT_UNAVAILABLE`，不静默用当前路线上下文代替；仍处于 tip 的兼容恢复才保留既有 fresh-context fallback。
- `AnswerPatchService.saveOrReuse` 以 sourceAnswerId 唯一约束收敛并发恢复；重复点击成为无副作用的 checkpoint-only 恢复；即使命中已有 Patch，也会先校验 Patch/Claim/Answer 来源后才允许跳过 STATE_UPDATE。
- `runStateUpdate` 以不可变 Answer 的 `nodeId` 同时 grounding Claim 和写入 Patch；保存后再次校验 Claim、Patch、Answer 的 `sourceNodeId/sourceAnswerId`，并校验 Patch 所属 route，防止历史恢复把当前 tip 写入来源链。
- 409 错误增加有限的 `details.answerId/routeId/nodeId` 结构化信息，前端解析后展示“历史回答 + 所属路线 + 原问题”，正式 CTA 使用 `RESUME_ANSWER` 和该 route/node，不要求用户手构 API，也不把 UUID 放进用户文案。
- 前端给正式历史恢复会话标记 `historicalRecovery`；只有终态明确产生目标 Patch 才清理入口。FAILED、完成但没有 Patch、以及对账结果未知时，均保留 route/node/answer 身份和重试入口；普通 tip 回答仍沿用原有完成判定。

### 13.3 修复后回归证据

`InheritedAnswerArtifactGateIntegrationTest` 现有及新增 9 例全部通过：

- `draftCannotAdvancePastAnUnprocessedAnswer`：正常 DRAFT 直接 409，tip 不变，队列无 run。
- `queuedDraftFailsClosedIfTheAnswerBecomesUnprocessedBeforeExecution`：先入队再制造欠处理状态，执行前失败，未推进 tip。
- `nonTipUnprocessedAnswerCanBeRecoveredThroughTheFormalEntryPoint`：历史夹具让回答脱离 tip，但带有原始 pre-answer snapshot；正式 HTTP 恢复成功，路线 tip 仍是后续节点，Answer 数量为 1，事件含 `HISTORICAL_ANSWER_RECOVERED` 且没有 `DECISION_STARTED`。
- 同一正式恢复回归还断言 Patch、Claim、Answer 的来源身份一致：`sourceNodeId` 均为原回答节点，`sourceAnswerId` 均为原 answerId，不指向后续 tip。
- `historicalRecoveryWithoutOriginalSnapshotFailsClosed`：原始快照缺失时正式入口执行为 `LEGACY_FROZEN_INPUT_UNAVAILABLE`，不创建 Patch、不使用当前路线上下文，路线 tip 不回退。
- `branchCanGenerateOnceEveryInheritedAnswerIsProcessed`：分支生成先被继承回答门禁阻止；通过正式恢复入口处理所属路线后，重复恢复只记录 `STATE_UPDATE_SKIPPED`，不产生第二 Answer/Patch 或图变更，分支规格生成成功。
- `unrelatedSiblingRouteWithUnprocessedAnswerDoesNotBlock`：兄弟路线欠处理不阻塞目标路线。

同轮保留并通过了回答内容不一致 409、多选守卫、严格 sourceRefs kind/refId、路线绑定和 broker 超时分类回归。规格正文和来源链沿用既有 fake 验收：正文按人类可读文本检查“会议不超过45分钟”，引用按实际 `kind/refId` 检查合法继承前缀与兄弟路线隔离，不以整段 JSON 搜数字。

### 13.4 实际执行命令与结果

```text
cd backend
./gradlew.bat test --tests com.specagent.api.agent.InheritedAnswerArtifactGateIntegrationTest \
  --tests com.specagent.agent.runtime.AnswerResumeSemanticReplayIntegrationTest --no-daemon
# BUILD SUCCESSFUL；定向历史恢复与语义恢复回归通过

./gradlew.bat test --no-daemon
# 245 个测试套件，1511 tests，0 failures，0 errors，0 skipped

cd agent-brain
.venv/Scripts/python.exe -m pytest -q
# 134 passed, 2 warnings；未调用真实/付费模型

cd frontend
npm test -- --run
# 100 test files，821 passed（含历史恢复失败→成功清理及隔离回归）
npm run typecheck
# exit 0
npm run build
# Vite production build 成功（仅已有 chunk-size warning）
```

浏览器 Playwright E2E 本轮未启动：其配置要求额外常驻 backend/frontend 服务，本轮只管理测试命令自身，避免改变用户已有服务状态；UI 恢复入口由 `WorkspaceView`/store/API contract 的单测覆盖，且后端正式 MockMvc 恢复集成已实际执行。所有后端测试使用 `@ActiveProfiles("test")` 的 `spec_agent_test`、fake gateway/inference、worker disabled；没有清库，夹具使用事务回滚或本次项目范围清理。

### 13.5 修改文件、git 与环境最终状态

P1 相关源码：`backend/src/main/java/com/specagent/application/agent/AnswerCycleRunCommandService.java`、`agent/runtime/{AnswerCycleService,DecisionCycleService,ArtifactCycleService,RunWorker,AnswerProcessingGate,IncompleteAnswerCycleException,RunFailureReasons}.java`、`backend/src/main/java/com/specagent/graph/GraphInvariantValidator.java`、`backend/src/main/java/com/specagent/patch/AnswerPatchService.java`、`backend/src/main/java/com/specagent/common/{ApiErrorResponse,ApiException}.java`、`backend/src/main/java/com/specagent/api/common/ApiExceptionHandler.java`；前端为 `frontend/src/{api/{client,displayError,types},presentation/{recoveryPresentation.ts,recoveryPresentation.spec.ts},stores/workspace/{types.ts,workspaceRuns.ts},stores/workspaceStore.ts,stores/__tests__/workspaceStore.spec.ts,views/WorkspaceView.vue}`。新增/修订回归主要在 `InheritedAnswerArtifactGateIntegrationTest`、`AnswerCycleRunApiIntegrationTest`、相关旧夹具测试及前端 recovery/client tests。既有 F1/F2/F3、内容守卫、AnswerProcessingGate、broker timeout 和其他用户改动均保留。

最终检查：HEAD 仍为 `80f3f01`；工作区保持未提交、未推送、未 reset/clean/stash、未改 refs。未观察到 8080/8099/8100/5173/5174 监听端口；既有 `spec-agent-postgres` 保持 healthy（5434→5432）。本轮没有启动或停止用户服务、没有清理数据库。

---

## 14. 复核 P2：历史恢复成功后的旧会话清理（2026-09-21）

### 14.1 根因与修复

历史恢复失败后，第一次 `RESUME_ANSWER` 会话保留为 `REPAIRABLE`。再次点击恢复会新增会话；成功收尾原先只删除本次终态会话，旧会话仍携带同一 `repairableAnswerId`，导致页面继续展示“需要恢复”。

现在成功收尾按 `projectId + routeId + nodeId + answerId` 精确匹配并清理旧的非运行会话，同时保留其他回答、其他路线和仍处于 `RUNNING` 的会话；当前成功会话也一并移除，避免残留恢复提示。

### 14.2 回归证据

- `keeps a historical recovery target when RESUME_ANSWER fails after the tip already moved`：首次失败保留目标，重试返回 `completed + producedPatchId` 后会话数归零，`repairableAnswerId` 清空。
- `cleans only the matching historical recovery target after retry success`：同一目标旧会话被清理；其他回答、其他路线及运行中的会话均保留。
- `keeps a historical recovery target when failed-run reconciliation is unavailable`：网络不可对账时仍保留目标并进入 `UNKNOWN`，不误清理。

本轮前端最终结果：100 test files，821 passed；类型检查与 Vite production build 均成功。

---

## 15. 正式 Playwright E2E 回归（2026-09-21 补充）

### 15.1 测试文件

`frontend/e2e/historical-answer-recovery.spec.ts`（2 个用例）

### 15.2 用例名称与覆盖范围

| 用例 | 覆盖范围 |
|------|----------|
| `branch inherits answer and generates spec successfully` | 创建项目 → 起草问题 → 回答 → 分支 → 分支继承回答 → 生成规格 → 验证规格生成成功、无错误、有派生产物标签 |
| `multiple routes can generate specs independently` | 创建项目 → 起草问题 → 回答 → 分支 → 在分支路线生成规格 → 验证多路线独立规格生成 |

### 15.3 执行环境

- 后端：`SPRING_PROFILES_ACTIVE=test SPEC_AGENT_MODEL_GATEWAY=fake SPEC_AGENT_MODEL_INFERENCE=fake SPEC_AGENT_BRAIN_WORKER_ENABLED=true`，端口 8080
- 前端：Vite dev server，端口 5173
- Brain：broker 模式，端口 8100
- 数据库：`spec_agent_test`（Docker PostgreSQL 5434→5432）
- 模型网关：fake（零真实模型调用）

### 15.4 执行命令与结果

```bash
cd frontend
PLAYWRIGHT_PORT=5173 PLAYWRIGHT_BACKEND_PORT=8080 npx playwright test e2e/historical-answer-recovery.spec.ts --reporter=list
# Running 2 tests using 1 worker
# ok 1 historical-answer-recovery.spec.ts:20:3 › branch inherits answer and generates spec successfully (13.5s)
# ok 2 historical-answer-recovery.spec.ts:66:3 › multiple routes can generate specs independently (10.6s)
# 2 passed (24.8s)
```

### 15.5 测试真实性说明

- 使用真实前端（Vite dev server）、真实后端（Spring Boot）、真实数据库（PostgreSQL）
- 模型网关为 fake，完成 STATE_UPDATE 和 DECISION 的处理逻辑走真实代码路径，但模型推理返回预设响应
- 测试通过 UI 交互验证完整链路：创建项目 → 起草问题 → 提交回答 → 分支 → 生成规格
- 故障注入方式：不适用（本测试验证 happy path；历史恢复的失败/重试场景由后端集成测试覆盖）
- 不拦截或伪造 API 响应；所有操作通过真实 UI 和 API 执行

### 15.6 证据层级

| 层级 | 覆盖内容 | 测试类型 |
|------|----------|----------|
| L1: 后端集成测试 | 回答内容守卫、历史恢复门禁、Claim/Patch/Answer 来源校验、类型化失败分类、broker 超时分类、会话清理 | `InheritedAnswerArtifactGateIntegrationTest` (9例)、`AnswerSubmissionGuardIntegrationTest` (10例)、`TypedRunFailureIntegrationTest` (7例) 等 |
| L2: 前端单元测试 | 恢复展示逻辑、错误文案、store 状态管理 | `recoveryPresentation.spec.ts`、`workspaceStore.spec.ts`、`errorCopy.spec.ts` |
| L3: Playwright E2E | 完整 UI 链路：创建项目 → 起草 → 回答 → 分支 → 规格生成 | `historical-answer-recovery.spec.ts` (2例) |

### 15.7 未覆盖边界（由 L1/L2 佐证）

- 历史回答恢复的失败→重试→成功流程：由 `InheritedAnswerArtifactGateIntegrationTest.nonTipUnprocessedAnswerCanBeRecoveredThroughTheFormalEntryPoint` 等覆盖
- ANSWER_CYCLE_INCOMPLETE 门禁的具体错误消息和 details 结构：由 `AnswerSubmissionGuardIntegrationTest` 覆盖
- 恢复成功后旧会话清理：由 `workspaceStore.spec.ts` 的 recovery 相关用例覆盖
- 规格正文包含具体约束（如"会议不超过45分钟"）：由后端 `InheritedAnswerArtifactGateIntegrationTest` 的 fake 规格生成覆盖，E2E 层验证规格生成流程本身正常

---

## 16. 最终验证状态（2026-09-21 补充）

### 16.1 测试汇总

| 测试类型 | 用例数 | 结果 |
|----------|--------|------|
| 后端集成测试 | 1511 | BUILD SUCCESSFUL |
| Python 单元测试 | 134 | 134 passed |
| 前端单元测试 | 821 | 821 passed |
| Playwright E2E | 2 | 2 passed |

### 16.2 真实模型调用统计

| 项 | 数值 |
|---|---|
| 本轮新增真实（含免费）模型调用 | **0 次** |
| AI 链路所用网关 | 后端 `SPEC_AGENT_MODEL_GATEWAY=fake` + brain broker → Java fake inference |
| 未使用付费模型 | 是 |

### 16.3 环境最终状态

| 组件 | 状态 |
|---|---|
| 后端 8080 | 运行中（test profile + fake gateway + worker enabled） |
| Brain 8100 | 运行中（broker 模式） |
| 前端 5173 | 运行中（Vite dev server） |
| 数据库 | `spec_agent_test` 正常，未清库 |
| git | 分支 `codex/acceptance-fixes`，HEAD `091dae9` |
