# Semantic Planning Transport Forensics Addendum

日期：2026-09-06。本轮只查 provider transport，不跑 semantic diagnostic，不发 270，不改 planning prompt、schema、reason codes、case set、映射、门控与生产行为。
上一轮报告中的 pacing 猜测现正式降级：R1 首个 POST 即 429，单纯后续过快无法解释首请求（除非 key 事先已受限），而本轮拿到了直接证据，见第 7 章。

# 1. Credential parity（只比指纹，不碰明文）

桌面 key（R1 实际使用的 eval key）指纹为 78ea307ec0c4，长度 67；产品 key 指纹为 b42e1ef554d1，两者是不同凭证（分属不同用途是设计如此）。
进程环境与本地 secrets 文件里都没有 eval key，R1 用的是桌面文件。用户侧正常请求的凭证无法观测，给出比对方法：对该 key 取 SHA-256 前 12 hex，若与 78ea307ec0c4 不同，则正常流量与诊断流量本来就不共享配额。

# 2. UA 是否真正上 wire

用本地回环捕获走 R1 真实构造代码（仅把 endpoint 指向 127.0.0.1）：UA 已配置为 opencode/1.18.21 且客户端实际发出的一致，
path 为 /chat/completions，Content-Type 为 application/json，Authorization 以 Bearer 方案出现（值未记录）。urllib 不覆盖自定义 UA。
结论：R1 manifest 的 UA 不是纸面记录，wire 已证实。

# 3. 正常路径与诊断路径对照

生产 completion（JDK HttpClient）：POST zen v1 chat completions，Bearer，UA 一致，stream 为 true 且 SSE，无 response_format、无 max_tokens，system 加 user 消息。
仓库钥匙检查探针：同 URL 与鉴权，stream 为 false，response_format 为 json_object，max_tokens 为 256，单 user 消息。
R1 诊断：同 URL 与鉴权，stream 为 false，response_format 为 json_object，max_tokens 为 800，system 加 user 消息，Python urllib，超时 120 秒。
静态差异只剩 stream 与 response_format 与 max_tokens 与消息体与 HTTP 库，URL、鉴权方案、模型、UA 完全相同。只记录差异，不做因果猜测。

# 4. 离线请求指纹

三类形状的规范指纹（URL、排序后非秘密头、模型、stream、response_format、max_tokens、角色、体长度与哈希）已生成，未来可据此证明两条请求是否同一种东西。
生产形 112 字节，探针形 176 字节，R1 形 228 字节（均为最小载荷下的形状指纹，非 live 体）。
# 5. Probe A（已知好形状，eval 凭证，单发）

POST zen v1 chat completions，钥匙检查同形（单 user、max_tokens 256、json_object、不 stream），eval key，UA 一致。结果 429，延迟约 922 毫秒。
响应体原文为 FreeUsageLimitError 加 Rate limit exceeded. Please try again later.，附 Retry-After 为 53788（约 15 小时）、cf-ray 与 date 头，无传输层错误。
证据全文保存在 backend/build/transport-forensics/probe_evidence.json（无 secret，只有 key 指纹）。

# 6. Probe B（R1 精确传输，eval 凭证，单发合成最小输入）

复用 R1 提交版本的 post_completion（同 UA、同超时、同 response_format、同 max_tokens 800），输入为手造最小合成快照（非 benchmark case），与 A 间隔 20 秒。
结果同样 429，延迟约 968 毫秒，响应体与 A 逐字节相同（同为 FreeUsageLimitError）。R1 精确路径不回传响应头是已知缺口，头证据以 A 为准。

# 7. 分类与根因

按量表，本轮落在情况 C（双 429，账户仍受限，不跑诊断）叠加情况 D 的风味（若用户正常流量用的是另一凭证，则差异主因是凭证而非 pacing）。
根因：eval 凭证的免费配额已耗尽。证据链为单发最小探针同样 429（与 pacing 无关）加 provider 亲口 FreeUsageLimitError 加近 15 小时 Retry-After。
这同时解释 R1 首请求即 429：R1 启动时 key 已在限制态（9 月 5 到 6 日两组 live eval 在同一免费 key 上烧掉近 200 次模型调用）。
pacing 假设正式出局：它解释不了首请求 429，更解释不了静置 20 秒后的单发最小探针 429。UA、请求体、stream、response_format、HTTP 库全部洗清嫌疑。

# 8. 缺口与 R2 门

缺口有二：R1 精确路径不回传响应头（B 侧只有体）；用户正常流量的凭证指纹未比对（方法见第 1 章）。
是否允许建 R2：否。Retry-After 约 15 小时，在此之前任何重跑都是浪费；恢复后也必须先单发探针拿到 200，才有资格按新 lineage 设计预检（小批量固定间隔、无重试），间隔数字届时按传输证据冻结，现在不定 6 秒还是 12 秒。
Error evidence 修复保留为 R2 前置要求：错误体、Retry-After、限流头、provider request ID 必须持久化，不得再只存状态码。

# 9. Verdict

TRANSPORT_ROOT_CAUSE_IDENTIFIED。R1 的 INCONCLUSIVE 维持，但原因从未知 provider 失败收敛为已识别的免费配额耗尽；planning 语义问题本身既未被证实也未被证伪，仍待配额恢复后的新 lineage。
本轮零生产改动，临时取证脚本已删，证据 JSON 留在 build 目录（git 忽略），只提交本 addendum。
