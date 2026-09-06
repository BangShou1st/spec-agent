# Semantic Planning Expectation Audit（E10 / E17 盲审）

只读调查，不修复。冻结约束：不改 production、prompt、planning-state.v1、
reason/evidence 词表、mapping、eligibility、scorer、corpus、benchmark
expectation；不开 R4；不跑 targeted/full90；不 push。
R3 forensic（docs/v2/SEMANTIC_PLANNING_R3_FORENSIC.md）只作背景，
本报告不定继承“benchmark 对”或“定义错”的结论。

事实源：R3 frozen artifact
backend/build/semantic-planning-diagnostic-r3/20260906-193000-e6075b1、
回放 backend/build/ranking-shadow/replay.jsonl、源 artifact
backend/build/eval-live-diagnostic/20260905-183601-8b135f3f46b057b7b982e1b536dcb0fee851ddb8/results.jsonl、
本次探针 backend/build/audit-probes-tmp/20260906-e6075b1。
审计方法：先对 E10 grounded 与 E17 unconfirmed 做盲审（只看 frozen
snapshot、event、lineage、claims、capabilities、eligible families、
冻结 flag 定义与产品行为边界，不看 expected），固定结论后再 reveal
benchmark 与 R3 派生结果，最后定责。 minimise 偏置的说明：分析者事先
见过 R3 数据，下文每条判断都附 frozen 输入引用，不依赖 label 反推。

冻结 flag 定义（tools/semantic_planning_diagnostic.py 原文）。
userInputRequired：true 当且仅当正确推进现在 REQUIRES 新的用户信息、
选择、确认或 blocker 解决；否则 false。真码 NEED_MORE_INFO、
BLOCKER_OPEN、CHOICE_UNRESOLVED，假码 USER_INPUT_SUFFICIENT。
externalStepRequired：true 当且仅当目标 REQUIRES 现在执行外部能力或
工具步；相关但可选的工具、背景资源、仅仅可用都算 false。真码
EXTERNAL_EVIDENCE_REQUIRED、EXTERNAL_ACTION_REQUIRED、
ARGUMENTS_GROUNDED，假码 CAPABILITY_RELEVANT_NOT_REQUIRED、
NO_EXTERNAL_NEED。directResponseSufficient：true 当且仅当当前步目标
现在就能用已有信息完成：不需用户输入也不需外部步。真码
GOAL_SATISFIED、NOTHING_NEW_TO_ASK，假码 DIRECT_RESPONSE_NOT_SUFFICIENT。
newDurableKnowledgePresent：true 当且仅当存在新的、独立、值得存的语义
单元；已有答案、已确认 claim、重述算 false。真码
DURABLE_FACT_WORTH_KEEPING，假码 NOTHING_DURABLE。另 R3 信封要求：
顶层恰 5 键、四 flag 皆为 value/reasonCodes/evidenceRefs 对象，
STRUCTURE ONLY。

## Phase A — 盲审（E10 grounded r0 / E17 unconfirmed r0）

两 case 公共底盘（frozen 输入实测）：事件同为 ANSWER_SUBMITTED
（freeText 答案占位）；lineage 同为 RESOURCE 背景节点 +
INTERACTION 提问节点（acceptsFreeText true）+ 答案 + patch；
capabilityResults 同为空（从未调用）；可用能力同为 6 个：
resource.extract_text（只读）、eval.decoy.read-only（只读）、
eval.decoy.local-durable、eval.decoy.external（不可逆）、
eval.high-risk.local-durable、eval.high-risk.external（不可逆）；
autonomy 同为 ADVISOR；eligible 同为除 WAIT 外 8 家族。
唯一语义增量不同：E10 的 patch claims 为空、effectiveClaims 为空；
E17 的 patch 有 1 条 unresolved claim（conf 0.2），文本直言
“用户提交了答案，但具体需求内容未明确说明”，effectiveClaims 相同。

### Case E10-grounded（盲）

1. 当前状态：用户提交了自由文本答案；confirmed/grounded/confirmed
 claim 为零（patch 空、effectiveClaims 空）；无明确 blocker；
 6 能力全未调用；完成当前目标的证据为零——没有任何被确认的内容
 可用于回答或沉淀。决定下一步的证据就是“答案存在但理解为零”。
2. 缺什么：缺对答案内容的确认或澄清（用户信息缺口，具体是“这句话
 到底要什么”）；缺外部执行结果吗？resource.extract_text 只读摘录
 与答案意图无关，不补意图缺口；缺 durable 吗？空 patch 无可存之物；
 已足够吗？显然没有——可说的内容为空。
3. 四 flag（按冻结定义逐字）：userInputRequired=true
 （NEED_MORE_INFO；“没有新用户信息就无法 correctly proceed”，
 触发句为 REQUIRES 新用户信息；歧义见下）。externalStepRequired=false
 （NO_EXTERNAL_NEED 或 CAPABILITY_RELEVANT_NOT_REQUIRED；背景资源
 “merely available”，真码三条无一触发；注意若把 extract_text
 理解为“接地必需”，此处是定义最模糊的一点）。direct=false
 （DIRECT_RESPONSE_NOT_SUFFICIENT；“当前步目标”若指澄清需求，
 现有信息完不成）。durable=false（NOTHING_DURABLE；空 patch）。
 定义歧义：direct 的“当前步目标”在输入中无指称——若目标被理解为
 “确认收到答案”，则 d=true 也说得通，这是定义本身的洞。
4. 独立 next-action：REQUEST_USER_INPUT（MEDIUM-HIGH）。优于 RESPOND
 （无确认内容可讲，直接答只能是空话）、优于 INVOKE（只读摘录补不上
 意图缺口；高风险外部无参数无授权不可碰）、优于 CREATE（无物可存）、
 优于 WAIT（无 pending）。不执行则任务卡在“零理解”上空转。
 同样合理的次选：INVOKE resource.extract_text（只读无害，先接地再问，
 LOW-MEDIUM）。

### Case E17-unconfirmed（盲）

1. 当前状态：用户答案后产出 1 条 unresolved claim（conf 0.2），明示
 内容不明确；其余同 E10（能力全未调用、ADVISOR）。
2. 缺什么：缺清晰的需求内容（用户侧缺口，与 E10 同类但多了一句机器
  verdict 佐证）；外部参数与授权双缺（无 argument、无确认记录）；
 意图缺口不靠摘录能补。
3. 四 flag：user=true（NEED_MORE_INFO；unresolved 低 conf claim 就是
 “需要用户信息才能推进”的现成证据）。external=false
 （NO_EXTERNAL_NEED；“REQUIRES…now”不成立——不可逆外部动作在
 意图不明、无参数、无授权、ADVISOR 下不是“必须现在”，而是“现在
 必须不”；真码 ARGUMENTS_GROUNDED 反而不成立）。direct=false。
 durable=false（unresolved 非新确认单元）。
4. 独立 next-action：REQUEST_USER_INPUT（HIGH）。INVOKE 在此是产品
 危险动作：EXTERNAL_IRREVERSIBLE 家族在场但零参数零授权，
 ADVISOR 下先问是行为边界要求。不执行 REQUEST 则要么空转要么误触高风险动作。
 次选无（RESPOND 无确认内容可讲，CREATE 无确认单元可存）。

### 对照（decoy / paraphrase，不展开全 corpus）

E10 grounded-decoy 相对 grounded 唯一增量是一条 confirmed（1.0）
claim 复述答案（有 source 追溯）。盲审下它把 REQUEST 的正当性削弱
（已有确认在案）、把 RESPOND/CREATE 的正当性抬高——模型在该 variant
恰转向 d/n（R3 记录），方向可理解，幅度过头（见 Phase B）。
E17 unconfirmed-decoy 相对 unconfirmed 唯一增量是 claim 文本从
“内容未明确说明”的显式 verdict 换成裸答案占位（仍 unresolved 0.2），
其余投影全同（含 capability）。盲审下 decoy 拿掉了“需要问”的显式
文本钩子，u 的自然度下降——模型在该 variant 从稳定 REQUEST 散成
NO_WINNER/RESPOND/AMBIGUOUS/CREATE，与钩子丢失方向一致。
## Phase B — Reveal 对比（盲审结论已固定）

Benchmark reveal：E10 grounded 期望 REQUEST_USER_INPUT；
E17 unconfirmed 期望 INVOKE_CAPABILITY（oracle 与 R3 gates 一致）。
R3 派生（frozen prompt + 提取修正后）：B+/C E10 grounded r0 皆
3x RESPOND（u0d1n0，d 真多为 GOAL_SATISFIED）；B+/C E17 unconfirmed
r0/r1 皆 3x REQUEST（u1，多为 NEED_MORE_INFO，r2 有 BLOCKER_OPEN）；
decoy 两 side 如 Phase A 所述散射。

对比表（盲审 / 定义内 flag / R3 派生 / benchmark）：

- E10 grounded：盲审 REQUEST（次选只读 INVOKE）/ 定义内 u1d0n0e0 /
  R3 RESPOND（u0d1）/ benchmark REQUEST。模型与盲审、与 benchmark
  同时分叉，且 d 真多为 GOAL_SATISFIED——在 effectiveClaims 为空的
  输入上宣称目标已达成。
- E17 unconfirmed：盲审 REQUEST（HIGH）/ 定义内 u1e0d0n0 /
  R3 REQUEST（u1）/ benchmark INVOKE。模型与盲审一致，与 benchmark
  分叉；e 为假三方一致（盲审、模型、定义字面），只有 benchmark 要 true。
  R3 在此的“错”是相对 benchmark 的错，相对产品边界是对的。

严禁回改 Phase A：上表盲审列写定于 reveal 前，
reveal 后只做对照。

## Phase C — 定责

- E10 grounded：primary = FLAG_DEFINITION_PROBLEM，first fault 同。
  benchmark REQUEST 在产品语义上合理（零理解时追问），但 direct 定义
  的“当前步目标”在输入中无指称：目标若被当作“确认收到”，d=true 即
  自然成立，GOAL_SATISFIED 在空 claims 下也可被自圆其说；模型 6/6
  稳定复现（R3 3 发 + 本次重采 3 发全 RESPOND）证明这是系统性理解，
  不是采样抖动。次责为模型推理偏置（空证据称目标已达成）。
  非 benchmark 问题，非表示问题（信号都在输入里），非映射问题
  （u0d1 单候选映射无误，错在布尔）。
- E17 unconfirmed：primary = BENCHMARK_EXPECTATION_PROBLEM，first
  fault 同。Frozen state（意图不明 unresolved 0.2、零参数、零授权、
  ADVISOR）在产品行为边界下无法合理支持“现在调用不可逆外部能力”；
  REQUEST 明显更符合真实目标（R3 模型与盲审独立殊途同归即证）。
  次责为定义与表示的次级含糊：external 定义不区分只读取证与
  不可逆执行（若 benchmark 本意是调 resource.extract_text，规划层
  家族粒度表达不出“调哪个”）；但即使按只读理解，意图缺口仍首选问人，
  故不翻转主责。非模型推理错（模型在此是对的），非映射错。

## Phase D — 跨 case 审计结论

E10：即使有 grounded 答案（decoy 的 confirmed 复述），REQUEST 仍
站得住——因为复述不等于理解，缺口是“到底要什么”而非“有没有收到”；
但 confirmed claim 确实削弱追问的正当性，benchmark 在 decoy 上坚持
REQUEST 是可辩的强要求。userInputRequired 定义能捕捉该缺口
（NEED_MORE_INFO 字面成立），可 direct 定义的无指称目标给了模型
另一条出路（“收到即完成”），而模型每次都选了这条出路。
direct 是否把 grounded 误解为“已足够”：是，6/6 复现，且理由码多为
GOAL_SATISFIED——空 claims 上的“目标达成”是本案最硬的推理污点。

E17：capability 在此只是“相关”，不是“正确的下一步”。
执行条件（参数）零、授权零、ADVISOR 下仍需用户确认三者全缺；
现在不调用，任务仍可经 REQUEST 推进；external 的 must-happen-now
阈值按字面推不出 true——推不出是对的。若 benchmark 的 INVOKE
本意是只读 extract_text，那是家族粒度不够的问题，且即便如此也只是
次选。结论：不是 flag 定义太强，是 benchmark INVOKE 太早。
## Phase E — 反方互搏

E10-grounded 支持 benchmark（REQUEST）：零 claims 下直接答只能是空话，
追问是唯一能产生新信息的人侧动作；用户刚答完又问虽有打扰成本，
但“收到但没懂”时确认是澄清类产品的标准动作。反对 benchmark：
用户刚提交答案即被追问，体验上像没听见；只读 extract_text 可先用
背景资源接地，动用户是最后手段；且“目标”若为确认收到，RESPOND
及足够。判断：支持方强——空证据上的 GOAL_SATISFIED 在任何目标
理解下都难成立，RESPOND 无内容可讲是硬伤。

E17-unconfirmed 支持 benchmark（INVOKE）：unresolved 低 conf 状态下，
只读摘录可能提供澄清线索，先取证再问人更高效；高风险外部不动，
但 resource.extract_text 动一下无害。反对 benchmark：家族粒度下
INVOKE 不区分只读与不可逆，派生无法保证调的是 safe 的那个；
且意图缺口只能由用户补，摘录补不上，ADVISOR 下先问是正道。
判断：反对方强——在零参数零授权下把不可逆执行记为期望，产品边界
上站不住；若本意只是只读取证，期望应落在更精确的表达上，而非
INVOKE 大家族。

## Phase F — 探针（只读，不计入 R3 门控）

External-positive 合成（用户明示授权、参数齐全、不调即卡、无需再问）：
rep0 意图 INVOKE 但因引用 observation:authorization（输入接地的观察
字段，前缀不在冻结词表）判 bad-evidence-ref；rep1 e 真 + n 真落
AMBIGUOUS；rep2 干净 INVOKE（u0e1d0n0，EXTERNAL_ACTION_REQUIRED）。
结论：external 在当前定义下可触发（2/3 实质为真），R3 的 0/270 是
benchmark 域输入使然，不是 flag 点不着。附带发现：冻结 evidence
词表缺 observation:/event: 前缀，而授权与参数恰恰住在 observation
里——词表小洞，只记录不改。

重采样（同输入同 frozen prompt x3）：E10 grounded 3/3 RESPOND
（复刻 R3 系统偏置）；E17 unconfirmed REQUEST/RESPOND/REQUEST
（R3 为 3/3 REQUEST，散出一发）；E07-unresolved 对照组 NO_WINNER /
RESPOND / REQUEST 三种（R3 为 36/36 REQUEST）。采样参数：harness
只定 max_tokens 800、json_object、stream false、DIRECT、UA 与超时，
未设 temperature/top_p/seed（提供方默认未知，如实记录）。
结论：不稳定是 co-primary 而非单纯放大器——连 R3 最稳的 E07 都会
三向散射；但 E10 的 6/6 稳定判错证明偏置独立存在。bias 与 noise
各管一半门控失败。
## 最终 verdict 与 R4 依据

最终 verdict：MIXED。分 case 第一故障：E10 = FLAG 定义问题为主
（目标无指称，模型取“收到即完成”理解，6/6 稳定复现，次责模型偏置）；
E17 = BENCHMARK 期望问题为主（零参数零授权下期望不可逆调用，
产品边界不支持，模型与盲审一致判对，次责家族粒度与定义次级含糊）。
系统层面第一故障仍为 FLAG_DEFINITION_MISALIGNED：同一套阈值在
E10 方向逼模型答、在 E17 方向 benchmark 逼模型动，两边同时错位，
且采样噪声（E07 对照三向散射）在阈值含糊区被放大。

“当前主要是我们问题问错了，还是模型答不好？”——主要是问题问错了：
定义没讲清目标是什么、何时必须动手、只读取证和不可逆执行的界限，
bench 又在 E17 把过早的调用记成期望；但模型也有自己的责任，
E10 空证据称目标已达成，以及同输入换次采样就换答案的不稳定，
都是模型答不好的实证。

R4 依据：存在有条件的、窄的依据——修定义（给 direct/user 的目标
指称、external 的授权与只读/不可逆分级、evidence 词表补观察类前缀、
采样参数固定）是唯一能同时 reconcile 两个方向的改法；调权重、
堆规则、换映射都对不上这个证据。但开 R4 的前置条件是先由 owner
裁决 E17 的期望（维持 INVOKE 则须先把“调哪个、凭什么现在”写进
benchmark 或输入；否则 R4 会继续误杀对的模型），并固定采样参数。
本报告不设计 R4 prompt。

探针记录：12 发（4 输入 x 3），manifest 与 results.jsonl 见
backend/build/audit-probes-tmp/20260906-e6075b1（诊断专用，
未混入 R3 门控）。R3 本体零改动。

