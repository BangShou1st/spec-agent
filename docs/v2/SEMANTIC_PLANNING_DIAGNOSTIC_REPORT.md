# Semantic Planning Diagnostic Report

实验性质：DIAGNOSTIC ONLY。Verdict 为 INCONCLUSIVE：270 次调用全部遭遇 provider 429，有效行为覆盖为零，任何行为结论都无证据支撑，本报告不做行为断言，只记录预注册、取证与下一步。

# 1. Pre-registration

artifact 为 backend/build/semantic-planning-diagnostic/20260906-171500-bb6b7c2，manifest 在首个 POST 之前写入，内容为：90 行回放全集去重后 90 unique、join 缺失 0、重复 3 次、
provider 为 opencode-zen、endpoint 为 https openai 兼容 zen v1、模型 mimo-v2.5-free、UA 为 opencode 1.18.21、prompt 哈希 f8a9cac7、schema 为 planning-state.v1、
映射版本 planning-mapping.v1、超时 120 秒、max_tokens 800、response_format 为 json_object、stream 关闭、步调 0.5 秒、无重试。
门控冻结为 B+ 未修好纠正过半（3/5）、C 关键 8 零回归、C 全正确不超 2/27、ineligible 为零、schema 失败为零、派生稳定性参照 68.8%。
多数规则冻结：单 case 三次中至少两次相同导出结果才算多数，否则无多数（既不算纠正也不算回归，只计不稳定）；回归指有多数且不在期望内；纠正指多数在期望内。

# 2. Provenance

契约提交 a703843（planning-state.v1 严格 schema 与 13 项契约测试），runner 提交 bb6b7c2（冻结 prompt 与映射），聚合修复提交 ae14e8b（见第 16 章）。
manifest 记录 head 为 bb6b7c2，运行时 prompt 哈希与跑前固化的 f8a9cac7 一致，冻结面完好。引用源为两组 frozen live artifact 与 90 行影子回放，不新增 live 数据采集。

# 3. Provider 与模型

opencode-zen 的 zen v1 completion 接口，模型 mimo-v2.5-free，UA 与生产传输层一致。凭证走进程环境（用户指定的桌面 key 文件读入运行进程内存，不落地、不打印），
模型名校验通过（必须等于 mimo-v2.5-free）。请求前先做 settings 面钥匙检查（GET models），通过后才写 manifest。brain 全程未接触 key。

# 4. Schema

planning-state.v1：四个标志各含布尔值、非空有限 reasonCodes、非空 evidenceRefs；未知版本、未知字段、跨标志 reasonCode、坏引用、空数组、额外自由文本一律判 SCHEMA_FAILURE。
evidence 只认 node、answer、patch、context、route、claim、capability 七种前缀，其中 claim 与 capability 为诊断简报授权的新增，生产引用词汇未动。
# 5. Case coverage

90 行全集去重后 90 unique，与两源 artifact join 缺失 0，共执行 270 次调用（90 乘 3），无补跑、无删减。E22 三行保留在运行与透明统计中，门控按预注册排除（其期望含无标志可映射的 WAIT）。
270 条原始记录逐条保存在 results.jsonl，含 outcome、http 状态、延迟与身份，无一丢失。

# 6. 重点 13 case（仅身份，无行为数据）

B+ 未修好 5 个为 E10 grounded r2、E10 grounded-decoy r0 与 r2、E17 unconfirmed r2、E25 frozen-replay r0；C 关键 8 个为 E01 paraphrase r1、E07 unresolved-paraphrase r0、
E10 grounded r0 与 r1、E10 grounded-decoy r2、E25 frozen-replay r2、E25 stale-relation-set r0 与 r2。三次调用全部 429，无一返回可用 planning state，纠正与回归均不可度量。

# 7. E17 对 E10

存在性验证无数据：两组的三次调用同样全部 429，planning 层能否稳定区分共享祖先证据必须与分支私有证据，本轮无法回答，方案 B 既未被证实也未被证伪。

# 8. B+ missed-5 correction

门控要求过半（3/5），实际有效多数为 0/5（分母行为缺失）。状态为 unevaluable，不是 0 分，是缺考。

# 9. Candidate C critical-8 preservation

门控要求零回归，实际不可度量。8 个对照的 planning 行为未知，未发生可观测的破坏，也未得到保护证据。

# 10. Candidate C full correct preservation

门控要求不超 2/27，实际不可度量，27 个对照全部缺行为数据。
# 11. Per-flag stability

四个标志各自的三次翻转率、整态精确匹配率均无数据（零有效回放）。 assessment 方差与选择方差的分离度量同样缺考，不输出数字，拒绝用零填充。

# 12. Derived-action stability

派生 family 稳定性无数据，与 68.8% 参照无从比较，不做达标断言。

# 13. Schema reliability

schema 失败计数为零、ineligible 为零、ambiguous 与 no-winner 为零，但全部是空集上的零：没有任何一次模型响应进入校验，校验器本身经 13 项契约测试证明有效，可靠性结论只能是未验证。
provider 失败 270/270，http 状态清一色 429，延迟 858 到 2125 毫秒、均值约 980 毫秒，属快拒而非超时。传输层错误为零，凭证检查通过， endpoint 可达。

# 14. Ambiguous 与 no-winner

零记录，原因同上：从未到达映射层。映射本身（单候选胜出、多候选记模糊、零候选记无胜者、交集强制 eligible）未经 live 检验，逻辑经代码审查与契约测试覆盖。

# 15. Verdict

INCONCLUSIVE。充分性规则触发：provider 失败率 100%，远超 10% 的充分性线，有效覆盖为零。五个行为门控全部处于 unevaluable，不是通过也不是行为性失败；
含 E22 与排除 E22 的口径结论相同（同样无数据）。行为失败不得写成 inconclusive 的反方向在本轮不适用，因为根本没有行为信号。
# 16. Root cause 与下一步

根因分两层。第一层是 provider 面：270 次 POST 全部 429，钥匙检查的 GET models 却通过，说明凭证有效、endpoint 可达，completion 通道被限流。
步调 0.5 秒约每分钟二三十次，超出免费档限流的可能性最大，属 runner 自身 pacing 设计冒进，判定为大概率自致限流；但错误体未持久化（只记了状态码），配额耗尽与限流无法 definitively 区分，这是本轮工具层面的记录缺陷。
第二层是工具面： live 跑完写出 270 条后 summary 落成 null，原因是汇总块掉进死代码区。已在 ae14e8b 修复并离线重算（零新增 provider 调用），prompt 哈希前后一致为 f8a9cac7，冻结面字节无损。
下一步不是重跑：按纪律，任何参数改动（例如把步调放宽到 6 秒以上、持久化错误体）都必须开新 diagnostic lineage、新 manifest、新目录，由下一轮决定。
本轮不跑 targeted 与 full90，不接 DecisionEngine，不改生产 prompt 与 eligibility，不 push 与 merge。
本轮生产行为零改动：新增仅为契约、runner 与本报告；旧 frozen snapshot 与 artifact 原样引用。
