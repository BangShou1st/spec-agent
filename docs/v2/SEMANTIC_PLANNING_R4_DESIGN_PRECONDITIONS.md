# Semantic Planning R4 Design Preconditions

Phase A (Benchmark Oracle Audit) 已完成。本文件整理 R4 必须解决的内部设计问题，
但不实现。不改 production、不改 prompt、不启动 R4 diagnostic、不跑 targeted/full90、
不 push。

前置事实：
- R3 verdict = DIAGNOSTIC_REJECTED（语义行为不达标，传输与信封干净）
- Phase A 已确认 E17 benchmark 期望错误（应改为 REQUEST_USER_INPUT）
- E10 的问题在 flag 定义，不在 benchmark
- E07-resolved 的问题在多标签 + WAIT 无 mapping 通道
- 模型存在 stochastic instability，但本项目不修模型
- R3 历史结果不得重算或篡改

## 1. userInputRequired

### 1.1 当前问题

定义："true iff correct progress now REQUIRES new user information, choice,
confirmation, or blocker resolution"

问题：定义中的"correct progress"没有指称。什么算"correct progress"？

- E10 grounded：如果"correct progress"是"确认收到答案"，那用户已经答了，
  不需要再问 → u=false, d=true。
- E10 grounded：如果"correct progress"是"理解用户意图并产生有意义的回应"，
  那零理解下必须追问 → u=true, d=false。

模型系统性地选了前者（6/6 RESPOND），benchmark 期望后者。
这不是模型答错了，是定义给了模型两条路。

### 1.2 R4 必须解决的问题

1. 明确"当前目标"的指称来源。
   - 选项 A：目标由 Runtime 在 snapshot 中显式声明（如 goalStatement 字段）
   - 选项 B：目标从 lineage + event + claims 推导（隐式）
   - 选项 C：目标由 prompt 规则定义（如"在 ANSWER_SUBMITTED 事件下，
     目标是理解答案内容并产生有意义的下一步"）

2. 明确什么信息缺口才要求再次问用户。
   - 当前定义的"REQUIRES new user information"太宽泛——几乎任何不确定
     都可以被解释为需要用户信息。
   - R4 需要区分：
     a) 意图缺口（不知道用户要什么）→ u=true
     b) 确认缺口（知道大致方向但需要确认）→ u=true 或 false（取决于风险）
     c) 执行缺口（知道要什么但缺参数）→ u=false（参数属于 external）
     d) 内容缺口（有信息但不够丰富）→ u=false（可能 d=true）

3. 明确 u 与 d 的互斥关系。
   - 当前定义允许 u=true 和 d=true 同时成立（"需要用户信息但也能直接答"）。
   - 这在语义上矛盾：如果能直接答，为什么还需要用户信息？
   - R4 应考虑：u 和 d 是否应该互斥？或者 d 的"当前步目标"是否应该
     排除"需要用户信息才能推进"的情况？

### 1.3 最小解决标准

R4 的 userInputRequired 定义必须：
- 给出"当前目标"的明确指称或推导规则
- 区分意图缺口、确认缺口、执行缺口、内容缺口
- 明确与 directResponseSufficient 的关系

## 2. directResponseSufficient

### 2.1 当前问题

定义："true iff the current step goal can be completed right now with existing
information: no user input and no external step needed"

问题 1："current step goal"在输入中无指称（同 1.1）。

问题 2："completed"的含义不清。
- E10 grounded：用户提交了答案但 effectiveClaims 为空。
  "完成"是"确认收到"还是"理解并回应"？
  如果是前者，d=true（有答案就确认）。
  如果是后者，d=false（零理解无法回应）。

问题 3：d=true 的真码 GOAL_SATISFIED 在空 claims 下被模型滥用。
- R3 中 E10 grounded 6/6 d=true，reason code 多为 GOAL_SATISFIED。
- 在 effectiveClaims 为空的输入上宣称"目标已达成"是推理污点。
- 但定义没有禁止这种解释——这是定义的洞。

### 2.2 R4 必须解决的问题

1. 不允许把"收到用户答案"等同于"目标已完成"。
   - "收到"是事件事实，不是语义完成。
   - d=true 必须要求存在足够 grounded/understood 信息产生实质响应。
   - "实质响应"的定义：响应内容必须引用或依赖于至少一条 confirmed 或
     grounded claim，不能是空话。

2. 明确 d=true 的必要条件。
   - 选项 A：至少一条 confirmed claim 且无 unresolved blocker
   - 选项 B：至少一条有效 claim（confirmed/grounded/assumed 且 conf ≥ 阈值）
   - 选项 C：goalStatement 被标记为已完成（需要 Runtime 支持）

3. 明确 d 与 u 的互斥规则。
   - 如果 u=true（需要用户信息），d 必须为 false。
   - 理由：需要用户信息意味着当前信息不足，不能同时说"足够直接答"。

### 2.3 最小解决标准

R4 的 directResponseSufficient 定义必须：
- 禁止在零 grounded 信息下判 true
- 明确"完成"需要什么级别的信息充分性
- 与 userInputRequired 形成一致的互斥关系

## 3. externalStepRequired

### 3.1 当前问题

定义："true iff the goal REQUIRES executing an external capability or tool step
now. A relevant-but-optional tool, a background resource, or merely available
capabilities mean false."

问题 1："REQUIRES...now"的阈值不清。
- E17：意图不明 + 零参数 + 零授权 → 模型判 e=false（正确）
- External-positive 探针：参数齐全 + 授权明确 + 不调即卡 → e=true（2/3）
- 但定义没有明确"什么条件下才算 REQUIRES now"

问题 2：不区分 read-only evidence gathering vs irreversible side effect。
- resource.extract_text (readOnly=true) 和 eval.high-risk.external
  (EXTERNAL_IRREVERSIBLE) 都在同一 INVOKE_CAPABILITY family 下。
- 但 read-only 的"REQUIRES now"阈值应该远低于 irreversible。

问题 3：不区分参数完整性。
- 零参数下的 INVOKE 是产品危险动作。
- 但定义没有要求参数必须齐全才能判 e=true。

问题 4：不区分授权状态。
- ADVISOR 下执行不可逆动作需要用户确认。
- 但定义没有要求授权记录才能判 e=true。

### 3.2 R4 必须解决的问题

1. 区分 read-only evidence gathering vs reversible side effect vs
   irreversible side effect。
   - 建议在 externalStepRequired 中增加子类型或在 planning-state 中增加
     capability risk level 字段。
   - read-only：低阈值，"可能有用"即可触发
   - reversible：中阈值，"大概率需要"才触发
   - irreversible：高阈值，"不执行就卡住 + 参数齐全 + 授权明确"才触发

2. 明确参数完整性要求。
   - e=true 必须要求调用所需参数在 snapshot 中已 grounded。
   - 零参数下 e 必须为 false。

3. 明确授权状态要求。
   - ADVISOR 下，irreversible 的 e=true 必须要求用户已确认授权。
   - 授权记录应在 snapshot 中可引用（如 authorization claim 或
     capability invocation history）。

4. 明确"不执行就无法继续"的判定。
   - "REQUIRES now"意味着没有替代路径。
   - 如果可以通过 REQUEST_USER_INPUT 推进，则 e=false。
   - 只有在"用户信息已齐全、只差外部执行结果"时，e 才可能为 true。

### 3.3 最小解决标准

R4 的 externalStepRequired 定义必须：
- 区分 read-only / reversible / irreversible 的触发阈值
- 要求参数完整性
- 要求授权状态（至少对 irreversible）
- 明确"不执行就无法继续"的判定规则

## 4. newDurableKnowledgePresent

### 4.1 当前问题

定义："true iff a new, standalone semantic unit worth persisting exists now.
Existing answers, confirmed claims, and re-summaries mean false."

问题 1："new"的阈值不清。
- R3 中 61/270 reps 判 n=true，但零 scenario 期望 CREATE。
- 模型对"新"的理解比 benchmark 宽松。
- 已有 patch/claim 的快照中，模型判"有值得存的新语义单元"——
  但这可能是对现有内容的重述或微调。

问题 2："standalone"的含义不清。
- 一条 claim 的补充说明算"standalone"吗？
- 一条 answer 的格式化版本算"standalone"吗？

问题 3："worth persisting"的判定标准缺失。
- 什么值得存？什么不值得存？
- 定义说"existing answers, confirmed claims, and re-summaries mean false"，
  但"re-summary"的边界不清。

### 4.2 R4 必须解决的问题

1. 明确"新"的判定标准。
   - 必须是输入中不存在的信息（不能是现有 claim/answer 的重述）
   - 必须有独立的语义价值（不能是格式变化或措辞调整）

2. 明确"独立"的判定标准。
   - 可以脱离上下文理解（不需要引用其他 claim 就有意义）
   - 或者：在 Graph 中可以作为独立节点存在

3. 明确"值得持久化"的判定标准。
   - 对后续任务有参考价值
   - 不是临时状态或中间结果
   - 不与现有节点重复（normalized 去重）

4. 避免把现有 patch/claim/answer 重述当成新知识。
   - 在 prompt 中明确："rephrasing, reformatting, or summarizing existing
     content does not count as new durable knowledge"

### 4.3 最小解决标准

R4 的 newDurableKnowledgePresent 定义必须：
- 明确"新"必须是输入中不存在的信息
- 明确"独立"的判定标准
- 明确"值得持久化"的判定标准
- 明确排除重述、格式化、摘要

## 5. Evidence Vocabulary

### 5.1 当前问题

冻结 evidence ref 前缀：node:, answer:, patch:, context:, route:, claim:, capability:

问题：Expectation audit Phase F 探针发现，授权与参数住在 observation 字段中，
但 observation: 前缀不在冻结词表内。external-positive 探针中引用
observation:authorization 的 rep 被判 bad-evidence-ref。

### 5.2 R4 必须解决的问题

1. 审查是否需要合法支持 observation: 和 event: 前缀。
   - observation 字段包含当前事件的上下文（如授权记录、参数详情）
   - event 字段包含触发事件的信息
   - 这些都是合法的证据来源

2. 如果增加前缀，需同步更新 planning contract 的 EVIDENCE_REF_PREFIXES。

3. 考虑是否需要其他前缀（如 snapshot:, metadata:）。

### 5.3 最小解决标准

R4 的 evidence vocabulary 必须：
- 至少增加 observation: 前缀（如果 observation 字段继续作为输入）
- 考虑增加 event: 前缀
- 更新 planning contract 的验证规则

## 6. Sampling Configuration

### 6.1 当前问题

R3 harness 只定 max_tokens 800、json_object、stream false、DIRECT transport、
UA 与超时，未设 temperature/top_p/seed。提供方默认未知。

Expectation audit Phase F 重采样发现：
- E07-unresolved（R3 最稳定的 case）在重采样中三向散射
- E10 grounded 6/6 稳定判错（偏置独立存在）
- E17 unconfirmed 3x REQUEST 中散出一发 RESPOND

结论：采样参数未固定是 instability 的放大器。

### 6.2 R4 必须解决的问题

1. 确定 provider 支持的最稳定采样配置。
   - temperature=0？（如果支持）
   - top_p=1？
   - seed=固定值？（如果支持）

2. 冻结采样条件。
   - R4 diagnostic 必须使用固定采样参数
   - 参数记录在 manifest 中
   - 任何参数变更都是新的 lineage

3. 记录 provider 的采样行为。
   - 如果 provider 不支持 temperature=0，记录实际使用的默认值
   - 如果 seed 不支持，记录为"unsupported"

### 6.3 最小解决标准

R4 的 sampling configuration 必须：
- 尝试固定 temperature 和 seed
- 如果 provider 不支持，记录实际配置
- 在 manifest 中明确声明采样参数

## 7. Mapping

### 7.1 当前问题

当前 mapping (planning-mapping.v1) 只有 4 条规则：
  user→REQUEST, external→INVOKE, direct→RESPOND, durable→CREATE

问题 1：无 WAIT 通道。
- E07-resolved 和 E22 期望 WAIT，但 mapping 选不出。
- ACTION_ELIGIBILITY_ARCHITECTURE.md 已记录此限制。

问题 2：多候选时的决胜规则不足。
- 当前规则：d+n 同真 → AMBIGUOUS（自动 miss）
- n 单真但 CREATE 不在 eligible → NO_WINNER（自动 miss）
- 这两类共 38 reps，全部记负。

问题 3：mapping 不承载语义判断。
- mapping 只做 flag→family 的机械映射
- 不判断"哪个 flag 更重要"
- 但 benchmark 期望隐含了优先级（如 E10 期望 REQUEST 而非 RESPOND）

### 7.2 R4 必须解决的问题

1. 是否引入 WAIT 通道。
   - 需要 Runtime 提供 pending dependency 信号
   - 需要在 planning-state 中增加 WAIT 相关 flag 或字段
   - 或引入 runDisposition/completionState

2. 是否调整决胜规则。
   - 当前 AMBIGUOUS/NO_WINNER 记负不合理
   - 选项 A：引入优先级（如 u > e > d > n）
   - 选项 B：在 eligible 中按优先级选第一个真 flag
   - 选项 C：保持当前规则，但调整 benchmark 期望

3. 是否通过调权重或 precedence tree 掩盖 semantic 定义问题。
   - 明确禁止：mapping 调整不能替代 flag 定义的修正
   - mapping 只审查是否仍能承载新的 semantic state

### 7.3 最小解决标准

R4 的 mapping 必须：
- 审查是否需要 WAIT 通道（如果 R4 引入 WAIT 语义）
- 审查决胜规则是否需要调整
- 不能通过调权重掩盖 semantic 定义问题

## 8. R4 最小问题集合

按优先级排序：

1. **P0 — directResponseSufficient 定义修正**
   - 禁止零 grounded 信息下判 true
   - 明确"完成"需要什么级别的信息充分性
   - 与 userInputRequired 形成一致的互斥关系
   - 这是 E10 系统性失败的 root cause

2. **P0 — externalStepRequired 定义修正**
   - 区分 read-only / reversible / irreversible 的触发阈值
   - 要求参数完整性
   - 要求授权状态（至少对 irreversible）
   - 明确"不执行就无法继续"的判定规则
   - 这是 E17 系统性失败的 root cause（加上 benchmark 期望修正）

3. **P1 — userInputRequired 定义修正**
   - 明确"当前目标"的指称或推导规则
   - 区分意图缺口、确认缺口、执行缺口、内容缺口
   - 与 directResponseSufficient 形成一致关系

4. **P1 — newDurableKnowledgePresent 定义修正**
   - 明确"新"的判定标准
   - 排除重述、格式化、摘要

5. **P2 — Evidence Vocabulary 扩展**
   - 增加 observation: 前缀
   - 考虑增加 event: 前缀

6. **P2 — Sampling Configuration 冻结**
   - 固定 temperature/seed
   - 记录 provider 实际配置

7. **P3 — Mapping 审查**
   - 审查是否需要 WAIT 通道
   - 审查决胜规则
   - 不通过调权重掩盖 semantic 定义问题

## 9. 是否具备正式设计 R4 的条件

是，但有前提：

1. E17 的 benchmark 期望必须先修正（Phase A 已确认）。
   - 不修正则 R4 继续误杀对的模型。
   - 修正后 benchmark 的 oracle 错误已被清除。

2. 采样参数必须先确定。
   - R4 diagnostic 需要使用固定采样参数。
   - 如果 provider 不支持 temperature=0/seed，需记录实际配置。

3. R4 的设计范围必须明确。
   - 最小范围：修正 P0 问题（direct + external 定义）
   - 推荐范围：修正 P0+P1 问题（+ user + durable 定义）
   - 完整范围：修正 P0+P1+P2 问题（+ evidence + sampling）
   - mapping 调整为 P3，可在 R4 后续迭代中处理

4. R4 必须是新的 legal lineage。
   - prompt 变更 → 新 prompt hash
   - 定义变更 → 重述门控
   - 不得以 OUTPUT_SCHEMA_CLARIFICATION 名义悄悄改阈值

## 10. 禁止事项（R4 设计阶段）

- 不修改 production behavior
- 不修改现有 R3 artifacts
- 不重算 R3
- 不直接改 prompt（设计阶段只出文档）
- 不启动 R4 diagnostic
- 不跑 targeted / full90
- 不 push

可以增加：
- 只读调查脚本
- 设计文档
- 必要的统计工具
