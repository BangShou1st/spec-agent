# Semantic Planning R3 Forensic Investigation

唯一事实源：backend/build/semantic-planning-diagnostic-r3/20260906-193000-e6075b1
中的 manifest.json、results.jsonl（270 reps）、summary.json。
本报告只调查、不修复；不改 production、prompt、schema、词表、mapping、
eligibility、权重、corpus；不启动 R4；不跑 targeted/full90；不 push。
Benchmark expectation 在本报告中只作为对照，不重新解释。

R3 lineage：SEMANTIC_PLANNING_DIAGNOSTIC_R3，parent 为 R2
DIAGNOSTIC_REJECTED（prompt 信封）。prompt 为 R1 基线 f8a9cac7 原样延长，
R3 哈希 d76a7628，差异分类 OUTPUT_SCHEMA_CLARIFICATION（显式嵌套示例 +
STRUCTURE ONLY 声明），无语义规则改动。另有一个诊断 harness 修正：
先取 choices[0].message.content 再做严格校验（R1/R2 直接校验信封，
内层正确也永远过不了）。

## 0. Verdict 定位

transport preflight 5/5 PASS（HTTP、JSON 解析、严格 schema 全过），
provider 失败 0，schema 失败 0，ineligible 0。
门控：B+ missed-5 纠正 0/5（要求至少 3/5），C critical-8 回归 6/8
（要求 0），C 全正确回归 13/27（允许至多 2），派生稳定性 45.56%
（要求至少 68.8%），全态精确一致 3.33%。
verdict = DIAGNOSTIC_REJECTED。

与 R2 的本质区别：R2 否掉的是 prompt 信封（270/270 形状失败，
语义层从未被求值）；R3 信封与传输全干净，270/270 进入语义求值，
这是第一次有资格在语义层面做判断。本轮 REJECTED 否掉的是当前
4-flag 定义 + 冻结映射在语义行为上不达标，不否认传输与格式。

270 reps 结果分布：REQUEST_USER_INPUT 106，RESPOND_TO_USER 70，
NO_WINNER 45，PLANNING_AMBIGUOUS 35，CREATE_NODE 14，
INVOKE_CAPABILITY 0。external_step_required 为真 0/270。
## 1. 四个 flag：accuracy、stability、FP/FN

口径声明：下表把“期望为真”定义为映射家族在该 case 的 expected 内
（user->REQUEST、external->INVOKE、direct->RESPOND、durable->CREATE；
E22 期望只有 WAIT，四个 flag 期望全假）。E07-resolved 类多标签
（expected 含 RESPOND/WAIT/REQUEST）在此口径下天然吃亏，
见第 3 节单独说明，不混入 FP/FN 归因。

- user_input_required：真 107/270（39.6%）。TP 75，FP 32，FN 144，
  TN 19，accuracy 0.348。翻转率 12.22%。FN 重灾区是 E10 系
  （grounded / grounded-decoy 三个 eval repetition 抽查 reps 全为假）
  与 E01/E19/E25 中期望 REQUEST 但 u 为假的各 case；FP 集中在
  E17 unconfirmed 系（期望 INVOKE，模型稳定判 u 为真，见第 2 节）
  与 E22（legitimate wait，15 reps 判 u 为真）。
- external_step_required：真 0/270。TP 0，FP 0，FN 33，TN 237，
  accuracy 0.878（全靠 TN 撑起）。翻转率 0%。33 个 FN reps 即全部
  期望 INVOKE 的 reps（E17 unconfirmed / unconfirmed-decoy，
  11 identities x 3）。模型在任何输入下都不认为“现在必须执行外部步”。
- direct_response_sufficient：真 104/270（38.5%）。TP 14，FP 90，
  FN 16，TN 150，accuracy 0.607。翻转率 38.89%，四个 flag 中最不稳。
  FP 即“ benchmark 认为还不能直接答，模型认为能”：E10 grounded 系
  几乎整齐 d 为真（B+ E10 grounded r0/r1 三个 planning reps 全 RESPOND），
  E01/E19/E25 回归的多数 RESPOND 亦属此类。
- new_durable_knowledge_present：真 61/270（22.6%）。TP 0，FP 61，
  FN 0，TN 209，accuracy 0.774。翻转率 32.22%。没有一个 case 期望
  CREATE，但模型在 E01/E07-resolved/E10-decoy/E17-r2/E19/E25 各处
  判 n 为真，其中 14 次落为 CREATE_NODE 输出（全部 eligible 含 CREATE），
  34 次与 d 同真落为 AMBIGUOUS，4 次 n 单真但 eligible 无 CREATE
  落为 NO_WINNER（第 3 节）。

小结：u 的 FN（144）与 d 的 FP（90）、n 的 FP（61）是同一系统偏置的
三面：给定 lineage 里已有 answer/patch，模型倾向“可答、可存”，
benchmark 倾向“再问一轮”。e 的 33 FN 则是另一极端：涉及外部能力时
模型倾向“还没到必须动手”。两种偏置方向相反，不是一个旋钮能同时拧回的。
## 2. E17-like vs E10-like 逐 repetition 对照

E10 系（期望 REQUEST_USER_INPUT，抽查 B+/C grounded 与 grounded-decoy
r0/r1/r2，planning rep0-2）：user 恒为假，external 恒为假。
典型三态只有 d/n 组合：RESPOND（u0d1n0，如 B+ E10 grounded r0/r1
三 reps 全同）、AMBIGUOUS（u0d1n1，如 B+ E10 grounded-decoy r0/r2）、
偶发 NO_WINNER（u0d0n0，如 B+ E10 grounded r2 rep1）。
E10 从未在任何 rep 判 u 为真。

E17 系（期望 INVOKE_CAPABILITY）：external 同样恒为假。
unconfirmed r0/r1（B+ 与 C 一致）：三 reps 稳定 u1d0n0，
输出 REQUEST_USER_INPUT（错家族但极稳）。unconfirmed r2 与
unconfirmed-decoy（两 arm）：混乱，同一 identity 内出现三种输出，
例如 C E17/unconfirmed/r2 rep0 AMBIGUOUS（u0d1n1）、rep1 CREATE_NODE
（u0d0n1）、rep2 RESPOND_TO_USER（u0d1n0）；B+ E17/unconfirmed/r2
rep0/1 AMBIGUOUS、rep2 CREATE_NODE。

First divergence：planning rep0 即已分叉，无需等到多数阶段。
E10-like 的失败点是 user_input_required 的系统性 FN（输入有 grounded
answer 即判“无需再问”）；E17-like 的失败点是 external_step_required
的系统性 FN（输入有高风险外部能力也判“无需动手”），叠加
unconfirmed r0/r1 把 u 误判为真。两者共享祖先证据但分支私有证据
（确认态 claim vs grounded answer、高风险外部能力）未能把 u/e
推向相反方向，反而推反（E10 的 u 假、E17 的 u 真）。
E07-unresolved 系（期望 REQUEST）是唯一反例：36/36 reps 稳定
REQUEST_USER_INPUT，说明“未解决 claim + 无 grounded 答案”这条路
模型是会问用户的，问题出在 grounded/decoy 出现后的判断阈上。
## 3. AMBIGUOUS / NO_WINNER / CREATE_NODE 的 flag tuple 与输入回溯

270 reps 中三类共 94 reps，tuple 只有 5 种（e 恒为 0，
u 为 1 的仅 1 次）：

- NO_WINNER u0e0d0n0 x41：四 flag 全假。主体是 E17
  unconfirmed-decoy r0（B+ 三 reps 全同）、C E01 paraphrase 系与
  E10 grounded r1 类。输入回溯：模型四问全否（USER_INPUT_SUFFICIENT、
  NO_EXTERNAL_NEED、DIRECT_RESPONSE_NOT_SUFFICIENT、NOTHING_DURABLE），
  eligible 家族再多也无候选。期望 REQUEST 的 case 里这是 u FN 的直接后果。
- PLANNING_AMBIGUOUS u0e0d1n1 x34：d 与 n 同真且都 eligible。
  主体是 E10 grounded-decoy（B+ r0/r2、C r2）、E17 unconfirmed r2、
  E07-resolved 系。输入回溯：snapshot 含 grounded answer 或已确认 patch，
  外加 decoy/高风险能力在场；模型一面判 GOAL_SATISFIED（d 真）、
  一面判 DURABLE_FACT_WORTH_KEEPING（n 真），映射按冻结规则判模糊，
  而模糊永不在 expected 内，自动记 miss/regression。
- CREATE_NODE u0e0d0n1 x14：n 单真且 eligible 含 CREATE。分散在
  E01 shuffled r0、E07-resolved 各 variant、E10 grounded-decoy r0、
  E10 grounded r2、C E17 unconfirmed r2 / decoy r1、E19 large r2、
  E25 frozen-replay r0。无一 case 期望 CREATE。输入回溯：多为已有
  patch/claim 的快照，模型判“有值得存的新语义单元”。
- NO_WINNER u0e0d0n1（n 单真但 eligible 无 CREATE）x4：B+ E22
  legitimate r0 rep1/2、C E17 decoy r0 rep0、C E19 large-shuffled r0 rep0。
  这 4 次证明映射严格遵守 eligibility（n 真但 CREATE 不在 eligible
  内，不硬选，记 NO_WINNER）。
- PLANNING_AMBIGUOUS u1e0d0n1 x1：C E17 decoy r0 rep2（u 真 n 真）。
  全实验唯一 u 参与的模糊。

E22（期望 WAIT，不可映射，6 identities x 3 = 18 reps）：15 次 u 单真
落 REQUEST（legitimate wait 下模型仍要问用户），3 次 NO_WINNER。
门控按预注册排除 E22，此处只记录，不计分。
E07-resolved（多标签期望 RESPOND/WAIT/REQUEST，10 identities x 3 =
30 reps）：CREATE 5、AMBIGUOUS 10、NO_WINNER 3、REQUEST 7、RESPOND 5。
同一 identity 内常三种输出（如派生稳定性该场景 0/10），说明多标签
场景下单胜者映射与期望集合存在结构性错位，留待映射讨论，不改判口径。
## 4. reasonCodes / evidenceRefs 是否支持 boolean

boolean 与 rationale：270 reps x 4 flags = 1080 个 flag，
boolean-code 一致性检查 0 不一致。u 真只用 NEED_MORE_INFO（93）与
BLOCKER_OPEN（14），u 假只用 USER_INPUT_SUFFICIENT（163）；
e 假只用 NO_EXTERNAL_NEED（242）与 CAPABILITY_RELEVANT_NOT_REQUIRED
（28），e 真空缺；d 真只用 GOAL_SATISFIED（66）与 NOTHING_NEW_TO_ASK
（38），d 假只用 DIRECT_RESPONSE_NOT_SUFFICIENT（166）；
n 真只用 DURABLE_FACT_WORTH_KEEPING（61），n 假只用 NOTHING_DURABLE
（209）。从未使用的码：CHOICE_UNRESOLVED、全部外部真码
（EXTERNAL_EVIDENCE_REQUIRED / EXTERNAL_ACTION_REQUIRED /
ARGUMENTS_GROUNDED）。结论：模型完全理解各 flag 的“词表-布尔”对应，
失败在布尔取值本身，不在 rationale 搭配。BLOCKER_OPEN 的 14 次归属
与 CHOICE 类码零使用是后续校准探针的候选，不在本报告展开。

boolean 与 evidence：全量 1177 个 evidenceRefs，接地率 1167/1177 =
99.15%（ref 的冒号后 ID 出现在同一 model_input 投影的 JSON 内）。
10 个未接地 ref 全部指向同一 answer ID（answer:c3a5e7d1-…），
集中在 E17/unconfirmed/r1（B+ 与 C 的 rep0-2），属小而实的接地缺口，
相对 270 次的系统性布尔错误可忽略。结论：证据接地基本成立，
失败不是“引了不存在的东西”，而是“引着存在的东西判错了布尔”。

## 5. First fault 判定（五选一）

- evidence grounding failure：否。99.15% 接地，10 个缺口集中单 ID，
  解释不了 0/5 纠正、13/27 回归与 45.56% 稳定性。
- mapping insufficiency：是放大器，不是第一故障。冻结映射把 d+n
  同真判 AMBIGUOUS（34 次）、把 n 单真但不可用判 NO_WINNER（4 次），
  这两类永不在 expected 内，自动记负。但即使给理想决胜规则，
  这些 case 也只能在 d/n 间二选一，期望 REQUEST/INVOKE 依然拿不到。
  E07-resolved 多标签场景另有映射与期望集合的结构错位，属实但非主因。
- representation insufficiency：次要。输入投影确实带有区分信号
  （E07-unresolved 36/36 稳定判对即证；evidence 99% 接地即证模型
  看到了分支私有证据），模型在信号存在时仍按 benchmark 判反，
  故表示层不是第一缺口。E07-resolved 的混乱有表示成分，但门控主体
  是单标签 case。
- model semantic instability：强放大器。同一输入三 reps 下 d 翻转
  38.89%、n 翻转 32.22%、全态精确一致仅 3/90（且全是 E01 三连
  NO_WINNER 的“稳定无为”），C E17/unconfirmed/r2 一 identity 内
  三种输出。但 E10 grounded r0/r1（稳定 RESPOND 错）、E17
  unconfirmed r0/r1（稳定 REQUEST 错家族）证明：即使掐掉采样噪声，
  系统性错误依然成立。不稳定解释散射，解释不了偏置。
- flag-definition ambiguity：第一故障。冻结定义的自然语言阈值
  （user “REQUIRES…/correctly”、external “REQUIRES…now 且
  relevant-but-optional 算假”、direct “right now”、durable
  “worth persisting”）被模型字面执行后，与 benchmark 期望系统性
  错位：外部步“还没到必须动手”→ e 在 33 个 INVOKE 期望 reps valo全假；
  grounded 答案在场→“可正确推进”→ E10 的 u 全假 d 骤真；
  patch/claim 在场→“值得存”→ n 在零期望下 61 次为真。
  rationale 与布尔 100% 自洽反证模型是“按定义稳定地判”，
  只是定义的操作化与 benchmark 不一致。两种偏置方向相反
  （该问的不问、该动的不动），单一阈值旋钮无法同时拧回，
  这正是 B-2 “不要再堆确定性 ranking 规则”教训在语义层的复现。

## 6. Root cause 与下一步（只建议，不执行）

Evidence-backed root cause：R3 在传输（0 失败）与信封（270/270
schema clean、preflight 5/5）排除后，语义层行为性失败。直接原因是
布尔层系统性错位（u FN 144、d FP 90、n FP 61、e FN 33 且 e 全零），
rationale 自洽、证据 99% 接地，故非格式、非词表、非接地问题；
第一故障为 flag 定义阈值与 benchmark 期望的操作化错位，
模型采样不稳定（d/n 高翻转）为放大器，冻结映射把共激活转为自动
miss 为转换器。E17/E10 的“同祖先、反答案”在 flag 层复现为
“u 反置 + e 沉默”。

R4 依据判断：以“再澄清一次 prompt 信封”为目标的 R4 没有依据
（信封已 5/5 + 270/270 证明修好）。任何 R4 都是语义变更 lineage，
须重注册、换 prompt 哈希、重述门控，不得以 OUTPUT_SCHEMA_CLARIFICATION
名义悄悄改阈值。建议的只读下一步（不执行）：对 E10 grounded 与
E17 unconfirmed 做小样本人工期望审计（是定义错还是期望错，先定责）；
做外部步校准探针（构造 plainly-必须动手的合成输入，看 e 是否曾为真）；
做同输入重采样稳定性探针并查提供方采样参数；查 BLOCKER_OPEN 14 次
归属与单 ID 证据缺口。审计结论出来前，不开 R4。

