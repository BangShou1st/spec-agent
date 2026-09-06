# Semantic Planning Diagnostic Report R2

Verdict 为 DIAGNOSTIC_REJECTED：transport 完全正常（0 provider 失败，preflight 5/5，无熔断），但 270 次回包全部倒在形状解析，行为门控失败。
关键澄清在第 16 章：本轮否掉的是本次 prompt 形状说明，不是语义表示方案，也不是模型的语义分类能力——没有任何一个语义标志进入过求值。

# 1. Pre-registration

R2 lineage 为 SEMANTIC_PLANNING_DIAGNOSTIC_R2，parent 为 R1 INCONCLUSIVE（代理污染），artifact 为 backend/build/semantic-planning-diagnostic-r2/20260906-173000-71ec4ef，
manifest 在 preflight 之前写入。语义冻结逐项一致：prompt 哈希启动时断言等于 f8a9cac7（否则中止），schema 为 planning-state.v1，reason 词表、case 90 unique、3 次重复、
planning-mapping.v1、期望标签、E22 处理、行为门控、68.8% 稳定性参照、provider、模型、UA、max_tokens 800、json_object、不 stream，全部与 R1 相同。
唯一机制变化为显式 DIRECT 空代理传输。门控、多数规则（三分之二相同才算多数）、纠正与回归定义沿用 R1 manifest 原文。

# 2. Provenance

R2 runner 提交为 71ec4ef（复用 R1 模块的 prompt、解析、映射、汇总，只新增 DIRECT 传输、证据持久化、preflight 与熔断），manifest 记录 head 与之对应。
引用源仍是两组 frozen live artifact 与 90 行影子回放，不新增数据采集。预检 5 次使用手造最小合成输入（非 benchmark），不计门控、不计稳定性。

# 3. Provider 与传输

opencode-zen 的 zen v1 completion，模型 mimo-v2.5-free，UA 与生产一致。凭证走进程环境（桌面 key 文件读入内存，不落地不打印），模型名校验通过，钥匙检查通过。
全部 275 次调用（5 预检加 270 正式）走显式 DIRECT 空代理 opener，超时 120 秒，启动间隔 10 秒，串行，无重试。Preflight 5/5 为 200，主阶段 270/270 有 HTTP 响应（ schema 失败全是 200 回包解析失败，非传输失败），熔断器零触发。
# 4. Schema 与 shapes

planning-state.v1 要求四标志嵌套对象（value、非空 reasonCodes、非空 evidenceRefs），未知版本、未知字段、跨标志 reasonCode、坏引用、空数组一律判 SCHEMA_FAILURE。
270 个回包的结构是扁平的：四标志以布尔值出现在顶层，reasonCodes 与 evidenceRefs 被提到顶层成为重复键（JSON 解析只保留最后一组），与契约的嵌套形完全对不上。
prompt 原文只写了每个标志含 value、reasonCodes、evidenceRefs，没有给显式嵌套示例，模型按扁平理解稳定输出。这是一个 prompt 形状说明缺陷，不是对语义问题的回答。

# 5. Case coverage

90 行全集去重后 90 unique，与两源 join 缺失 0，执行 270/270，无补跑、无删减。6 个 unmappable 全是 E22（期望只有 WAIT，无标志可映射），门控按预注册排除，含与排除两种口径结论相同。

# 6. 重点 13 case

B+ 未修好 5 个（E10 grounded r2、E10 grounded-decoy r0 与 r2、E17 unconfirmed r2、E25 frozen-replay r0）三次全部为 invalid-schema，无多数有效家族。
C 关键 8 个（E01 paraphrase r1、E07 unresolved-paraphrase r0、E10 grounded r0 与 r1、E10 grounded-decoy r2、E25 frozen-replay r2、E25 stale-relation-set r0 与 r2）同样三次全败。
纠正与回归在行为层面均不可度量：没有任何一次回包进入过映射层。

# 7. E17 对 E10

存在性验证本轮无数据：两组的三次调用同样全部形状失败，planning 层能否稳定区分共享祖先证据必须与分支私有证据，仍是开放问题，方案 B 既未被证实也未被证伪。
顺带观察（不计分）：扁平回包里的布尔值与 reasonCode 看起来语义合理（例如首包判 userInputRequired 为真配 NEED_MORE_INFO），但这只是形状之外的阅读印象，不能作为模型语义能力的证据。
# 8. B+ missed-5 correction：0/5

门控要求过半，实际有效纠正为零（5 个多数全是 SCHEMA_FAILURE）。这是门控失败，不是 0 分行为，是缺考叠加形状失败。

# 9. Candidate C critical-8 preservation：8/8（按规则记回归）

8 个对照的多数结果全不在期望内，按冻结回归定义记 8/8。必须同时记录：回归的是机制无有效输出，不是机制选错家族——它从未做出过家族选择。
把这 8/8 读成语义表示不行的证据是错误的，读成 prompt 形状不行的证据才是对的。

# 10. Candidate C full correct preservation：27/27（同上）

27 个对照全部多数为 SCHEMA_FAILURE，同第 9 章的阅读纪律：门控数字失败，语义结论缺席。

# 11. Per-flag stability：无有效数据

四个标志各自的三次翻转率无法计算（零有效回放）。不输出数字，不用 1.0 或 0 填充，不把缺数据当不稳定证据。

# 12. Derived-action stability：名义 1.0，实际无意义

派生结果 270 次完全一致（全是同一失败），数值稳定性为 1.0，但这是失败模式的稳定，不是选择的稳定，不得作为达标信号。68.8% 参照无从比较。
# 13. Schema reliability

270 次回包全部为 invalid-schema 子类：JSON 本体可解析，但顶层多出重复键且四标志不是对象。unknown reason code、坏引用、空字段等更细子类零记录（从未走到那一步）。
ineligible、ambiguous、no-winner 全零（从未到达映射层）。provider 失败零，传输熔断零触发。校验器本身经 13 项契约测试，问题不在校验器。

# 14. R1 对 R2 对照

传输从污染变为显式 DIRECT（preflight 5/5、主阶段零传输失败）；语义冻结逐项相等（prompt 哈希同为 f8a9cac7）；provider 成功率从 0/270 变为 270/270 有 HTTP 响应；
schema 有效从 0 变为 0（失败面从传输失败转为形状失败）；纠正、回归、稳定性、E17 对 E10 在两轮均无行为数据。R2 把问题从传输层推进到了 prompt 形状层，这是唯一的实质进展。

# 15. Verdict：DIAGNOSTIC_REJECTED

transport 覆盖充分且行为门控失败，符合 REJECTED 定义。但按第 26 条纪律做 forensic 而不是改 prompt 重跑：错的是哪个标志？答：没有标志被求值过。
哪些 case 分不开？答：全部 90 个都倒在解析，与 case 无关。representation 不够还是模型不稳定？答：本轮证据回答不了，失败点在信封不在信纸。
因此 REJECTED 否掉的是本次 prompt 形状说明（缺显式嵌套示例），不否掉语义规划中间层方向，更不证明确定性方案够用（同签名反答案的证明依然成立）。

# 16. 下一步（本轮只写计划，不执行）

下一 lineage 如需重测，最小改动是 prompt 加显式嵌套 JSON 示例（这是 prompt 变更，必须新 lineage、新 manifest、新 prompt 哈希，原 f8a9cac7  lineage 封存）。
其余冻结项（schema、词表、case、映射、门控）可原样继承。是否开新 lineage 由下一轮决定，本轮停止：不接 DecisionEngine，不跑 targeted 与 full90，不 push 与 merge。
本轮生产行为零改动：新增仅为 R2 runner 与本报告；旧 frozen snapshot 与 artifact 原样引用。
