# Semantic Planning Representation Investigation

日期：2026-09-06。基线分支 main（承接 4501be8）。
本轮只调查，不改 production behavior，不调 RankingWeights、RankingScores、priority 常量、family precedence，不给 selector 堆规则，不调用 provider，不跑 acceptance targeted 与 full90。
方法：复用两组 frozen live artifact 与影子回放行（90 behavioral-complete），逐例提取冻结 DECISION 快照的结构化状态，对照剧本期望做离线语义分解；结论不依赖 scenario ID，只用通用事实与期望对照。

# 1. 当前问题

ActionEligibility 的 deterministic enforcement 已验证，系统会判断哪些动作根本不能做。但 B+ 失败的 20/27（74.1%，排除 E22 后 20/24 即 83.3%）是期望与错误动作同在 eligible 集合内的 ranking 问题。
B-2 deterministic ranking 影子结果：B+ 纠正 15/20（75.0%），B+ 正确 case 回归 1/17（5.9%），Candidate C 正确 case 回归 8/27（29.6%），结论 SHADOW_REJECTED。
拒绝的不是 selector，而是 deterministic semantic facts：B-2 adapter 是互斥三选一分类器（缺用户信息、需外部步骤、可直接完成恰其一为真），
跨 arm 时同一结构签名对应相反正确答案，继续调权重或堆规则修不好。First fault 为 SEMANTIC REPRESENTATION INSUFFICIENT。
# 2. 13-case 分解（B+ 未修好 5 个）

表列：期望与实际、结构化状态、adapter 推断、错因、缺失信息。状态缩写：CONF 为 confirmed 结论数，UNRES 为 unresolved 结论数，RES 为 lineage 资源节点数，ANS 为已答问，CAP 为可用能力数，CR 为已完成工具结果数。

B1 E10/grounded/r2：期望 RUI，实际 RTU，影子 RTU。状态为 1 CONF、0 UNRES、1 RES、已答问、6 CAP、0 CR、ANSWER_SUBMITTED。
adapter 因无未决且无外部需求判直接完成。错因是确认加已答被当成任务结束，但剧本仍需追问。缺 USER_INFO_REQUIRED 与 GOAL_UNSATISFIED 的判断。

B2 E10/grounded-decoy/r0：期望 RUI，实际 IC，影子 RTU。状态为 1 CONF、0 UNRES、1 RES、已答问、6 CAP、0 CR。
adapter 判直接完成，模型却调了诱饵能力。错因除追问需求外，还有能力此刻根本不需要的判断缺失。缺 USER_INFO_REQUIRED 与 CAPABILITY_NOT_NEEDED_NOW。

B3 E10/grounded-decoy/r2：期望 RUI，实际 RTU，影子 RTU。状态同 B2（CONF 1.0）。adapter 判直接完成。缺 USER_INFO_REQUIRED，与 B1 同构。

B4 E17/unconfirmed/r2：期望 IC，实际 RUI，影子 RTU。状态为 1 CONF（0.8）、0 UNRES、1 RES、已答问、6 CAP（含高风险本地能力）、0 CR。
adapter 因无未决判直接完成，但任务要求提议高风险能力（走确认流程）。错因是外部步骤此刻必须的判断缺失，能力描述符里有该能力但 adapter 从不看是哪一个。缺 EXTERNAL_STEP_REQUIRED。

B5 E25/frozen-replay/r0：期望 RUI，实际 RTU，影子 RTU。状态为 1 CONF（0.5）、0 UNRES、1 RES、已答问、6 CAP、0 CR。与 B1 同构。缺 USER_INFO_REQUIRED。

小结：5 个里 adapter 全判直接完成，期望为 4 个 RUI 加 1 个 IC。共同缺失是确认加已答不等于目标已满足，以及外部步骤此刻是否必须的判断。
# 3. 13-case 分解（Candidate C 回归 8 个，模型原来都选对）

C1 E01/paraphrase/r1：期望与实际均为 RUI，影子 RTU。状态为 1 CONF（1.0）、0 UNRES、无资源、已答问、5 CAP、0 CR。
adapter 因无未决判直接完成，但用户输入仍被需要。缺 USER_INFO_REQUIRED。这是无资源版的目标未满足误判。

C2 E07/unresolved-paraphrase/r0：期望与实际均为 RUI，影子 RTU。状态为 1 个 conflict 且状态为 CONF（0.8）、已答问、无资源。
adapter 只看状态不看种类，已确认即当完成。但已确认的冲突仍需用户输入，确认不等于解决。缺 CHOICE_RESOLVED 与 MATERIAL_BLOCKER 的判断；冲突种类字段明明在快照里，adapter 从不读。

C3 E10/grounded/r0、C4 E10/grounded/r1、C5 E10/grounded-decoy/r2：期望与实际均为 RUI，影子全判 IC。
状态均为 1 UNRES（置信 0.1 到 0.3）、1 RES、已答问、6 CAP、0 CR。adapter 的外部代理（单资源加能力可用加未决加低置信）开火，但该场景定义就是资源只当背景、绝不劫持决策。
缺 CAPABILITY_RELEVANT_BUT_NOT_REQUIRED_NOW：能力相关但此刻不需要。模型在三行里都正确按住没调，adapter 把相关当成必须。

C6 E25/frozen-replay/r2、C7 E25/stale-relation-set/r0、C8 E25/stale-relation-set/r2：期望与实际均为 RUI，影子全判 IC。
状态为 1 UNRES（0.3 到 0.5）、1 RES、已答问、6 CAP、0 CR，与 C3 组同构。缺同样的此刻是否必须的判断。

# 4. 同签名反答案：确定性函数不可分的证明

E17/unconfirmed/r0（B+，未决 0.2、1 资源、6 能力）：adapter 判 IC，正确。E10/grounded/r0（C，未决 0.2、1 资源、6 能力）：adapter 判 IC，错误，正确是 RUI。
两行的通用事实签名完全相同（未决种类同为 other、置信同档、lineage 同为资源加已答问、能力集合完全相同、事件同为 ANSWER_SUBMITTED），正确答案相反。
任何只读这些事实的确定性函数都不可能同时判对两行，唯一的区分信息在文本语义里（该任务的资源是背景还是行动项、高风险能力是否在计划内）。这就是 Type D 的存在性证明，不是权重问题。
同理，E10-B+ 行（确认加已答问判 RUI 正确）与 E07-resolved 行（确认加已答问判 RTU 正确）同形异答，确认不等于完成。
# 5. 缺失语义信息分类（只保留被证据支持的 7 个维度）

保留 7 个维度：USER_INFO_REQUIRED、GOAL_SATISFIED、EXTERNAL_STEP_REQUIRED、CAPABILITY_RELEVANT、DIRECT_RESPONSE_SUFFICIENT、MATERIAL_BLOCKER、CHOICE_RESOLVED。
砍掉 NEW_DURABLE_KNOWLEDGE 与反面（两组 live 从未选中 CREATE_NODE）、PENDING_DEPENDENCY 与反面（仅 E22，已排除）、UNRESOLVED_CHOICE 独立维度（并入 blocker 与解决态）、DIRECT_RESPONSE_INSUFFICIENT 独立维度（它是前三个维度的反面）。
各维度的证据：USER_INFO_REQUIRED 见 B1、B2、B3、B5、C1，反面 SUFFICIENT 见 E07-resolved 的 RTU 正确；GOAL_SATISFIED 见全部确认态 case（确认不等于满足）；EXTERNAL_STEP_REQUIRED 见 B4 为真、C3 到 C8 为假；CAPABILITY_RELEVANT 见 C3 到 C8（相关但此刻不需要）；DIRECT_RESPONSE_SUFFICIENT 在 13 个里全为假、在 E07-resolved 里为真；MATERIAL_BLOCKER 与 CHOICE_RESOLVED 见 C2（已确认的冲突仍是真 blocker，标确认不等于解决）。

# 6. Type A/B/C/D 占比

判定标准：primary 为判对该 case 不可少的那一类；contributing 为能收窄候选但单独不够的类。
13 个 case 的 primary 全是 D：每个 case 都至少需要一个真正的语义判断（目标满足了吗、还需要用户输入吗、能力此刻必须吗、冲突真解决了还是只是标了确认）。
Type D primary 等于 13/13，A、B、C 的 primary 均为 0。这不是凑数，而是第 4 章的同签名反答案直接推论：通用事实相同而答案相反时，缺的一定是事实之外的判断。
contributing 分布：A 参与 3 个（B4 的能力描述符身份、C2 的冲突种类、C3 组的 supports 与副作用类，都在快照里但 adapter 不读）；
B 参与 2 个（B4 与 C3 组可用已完成工具结果为空推出外部步骤尚未执行，但推完还是不知道此刻要不要执行）；
C 参与 13 个（目标满足、任务继续、选项解决这类字段在契约里根本不存在，所有 case 的结构表达力都缺这一层）。
正式结论：deterministic semantic adapter 不够，需要受限的 AI 语义规划中间表示。注意这不是否定 deterministic 部分：eligibility、冻结、stale 与投影保持不动，缺的是两者之间的语义层。
# 7. Candidate C 回归的机制：语义被压扁

8 个回归里模型本来全对，adapter 改错 6 个 IC 加 2 个 RTU。被压扁的信息有两处。
第一处，确认不等于完成。E01 与 E07 行没有任何未决，adapter 判可直接回答；但 E01 仍需追问，E07 的已确认冲突仍需用户输入。
模型从文本里读出了任务没完，adapter 的布尔量里没有任务完没完这一维。
第二处，相关不等于必须。E10 与 E25 行有未决、低置信、单资源、能力可用，adapter 的外部代理开火；但 E10 的定义就是资源只当背景，E25 同构。
模型按住了没调，adapter 把相关当成必须。两处都是 adapter 把高维语义压成一两个布尔量后不可逆的信息丢失，正式记录为 deterministic adapter erased semantic nuance，不用新权重补。

# 8. B+ 剩下 5 个的机制：缺更高的任务态概念

对偶关系为 RUI 对 RTU 三组（B1、B3、B5）、RUI 对 IC 一组（B2，模型还错调了诱饵）、IC 对 RUI 一组（B4）。
adapter 五连判直接完成，说明当前结构化状态在确认加已答之后就没有任何可走的分支了：后面是继续问、调工具还是收尾，状态里没有表达位。
真正缺的是一个更高层概念，即当前任务还需要什么才能继续。权重调的是分支的倾向，缺的是分支本身。
# 9. 三种下一方案

方案 A 补 deterministic projection：把种类、描述符、已完成结果 emptiness 等读全，收紧外部代理。证据不支持为主方案：E17-r0 与 E10-r0 同签名反答案证明，再读全也分不开需要与不需要，收紧只会把误伤从一边赶到另一边。
只有 A、B 类占多数才推荐，实际 primary D 占 13/13，不推荐。
方案 B Structured Semantic Planning State：模型先不选动作，只输出窄任务态（用户输入要否、外部步骤要否、直接回答够否、有无新持久知识），每项带有限 reason code 与 evidenceRefs，禁 chain-of-thought；再由 planning state 加 eligibleFamilies 经现有 v1 契约展开成 family assessments，selector 不动。
方案 C 模型逐 family 做 assessment：每个 eligible family 各判一次 applicable 加理由加证据，再由 selector 选 winner。复用现有 v1 契约，但把同一个任务态问题问三遍，自洽无保证。

# 10. B 对 C 的比较

稳定性：B 单点输出，跨 family 一致性由构造保证；C 三处独立判断，同一任务态可同时判 RUI 适用又判 RTU 适用，无仲裁规则时会把矛盾推给分数。
可解释性：B 的 4 个标志就是解释；C 的解释散在三个 assessment 里，互相打架时不可读。
延迟与调用数：B 加一次窄调用，C 同样至少一次但输出三倍体量；token 成本 B 明显更低。
schema 复杂度：B 一个小对象，C 沿用 v1 但 assessment 侧 burden 全在模型。
调试难度：B 可逐标志对照期望，C 需先裁决三个判断谁错。
benchmark hardcode 风险：两者都不读 scenario，B 的标志是任务通用语义，C 的 per-family 写法更容易滑向针对某 family 的特例理由。综合推荐 B，C 仅在 B 的 4 标志被证伪不够用时再议。

# 11. E17、E19、E22

E17 是关键设计案例：IC、RUI、RTU 全 eligible 时，要的不是哪个 family 权重高，而是 externalStepRequired 为真、userInputRequired 为假、directResponseSufficient 为假。
这种表示能直接解释 B4 与 E17-r0 行，支持 semantic planning state。
E19 看稳定性：相同语义输入加相同 mask 下模型在 RUI、RTU、IC 间漂移，而表示指纹分组（B+ 43 行 6 组、C 46 行 7 组，无混合 winner）说明 adapter 端完全确定。
中间层的价值正在于此：assessment 变还是选择变，从此可分开度量，确定性 selector 下选择方差只来自 assessment 方差。
E22 继续排除，保持 KNOWN WAIT CONTRACT 与 REPRESENTATION MISMATCH 结论，不驱动新架构。
# 12. 推荐架构与表示

推荐方案 B，展开链为 planning state（模型窄输出）加 eligibleFamilies（确定性）经现有 agent-ranking.v1 契约展开成 assessments，再进现有 selector。
映射规则先行草案：RUI 适用当且仅当 userInputRequired；IC 适用当且仅当 externalStepRequired（具体调哪个、是否授权仍归 eligibility 与 Policy，不管辖）；
RTU 适用当且仅当 directResponseSufficient；CREATE_NODE 适用当且仅当 newDurableKnowledgePresent；其余 family 维持现有处理。映射本身是确定性的，可单元测试。
表示 schema 草案（字段名待冻结，语义先行）：
    planningStateV1： userInputRequired、externalStepRequired、directResponseSufficient、newDurableKnowledgePresent 四项；
    每项含 value 布尔、reasonCodes 有限闭合集、evidenceRefs（沿用现有 node、answer、patch、context、route 引用词汇）；
    禁 chain-of-thought，禁自由文本理由，禁 scenario 相关引用。
reason code 起点（只增不改，冻结后再审）：用户信息侧 NEED_MORE_INFO、BLOCKER_OPEN、CHOICE_UNRESOLVED；外部侧 EXTERNAL_EVIDENCE_REQUIRED、ARGUMENTS_GROUNDED、NO_EXTERNAL_NEED；
完成侧 GOAL_SATISFIED、NOTHING_NEW_TO_ASK；知识侧 DURABLE_FACT_WORTH_KEEPING、NOTHING_DURABLE。每码必须有证据引用，无证据的码非法。

# 13. 测试策略与诊断预注册

测试策略分三层：离线重放层用 90 行 frozen 行复测纠正率与回归率；稳定性层测同一输入三次的 assessment 翻转率与选择翻转率，两者分开记；
影子层在 gate 通过前不接生产链。沿用影子门槛：5 个未修好纠正过半、C 正确回归不超一成、无 winner 与 ineligible winner 为零。
诊断预注册（跑之前先冻结）：case 集为 90 行回放加 13 个重点加 15 个 B+ 已纠正对照；重复 3 次；schema 为 planningStateV1 草案冻结版；
模型与 provider 固定为 opencode-zen 的 mimo-v2.5-free（与两组 artifact 同源）；数值门为纠正率过半且 C 回归不超一成且 assessment 翻转率 separately 报告；成功标准跑后不改。

# 14. Verdict 与实施决定

VERDICT 等于 B，即 STRUCTURED_SEMANTIC_PLANNING。需要小型 diagnostic 等于 YES（须先完成第 13 章的预注册）。现在可以 implementation 等于 NO。
在 diagnostic gate 通过之前：不接 Decision 链，不改 prompt 与 eligibility，不跑 acceptance targeted 与 full90，不 push 与 merge。
本轮未改动任何生产代码、测试与配置，只新增本报告。旧 frozen snapshot 与 artifact 全部原样引用，结论可复现。
