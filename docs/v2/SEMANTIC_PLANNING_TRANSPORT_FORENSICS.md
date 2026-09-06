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

# 7. 分类与根因（已由第 10 章更新）

首次分类落在情况 C（双 429，账户仍受限，不跑诊断）叠加情况 D 的风味。DIRECT 探针后根因更新见第 10 章：配额耗尽说让位给出口代理污染说。
pacing 假设正式出局：它解释不了首请求 429，更解释不了静置 20 秒后的单发最小探针 429。UA、请求体、stream、response_format、HTTP 库全部洗清嫌疑。

# 8. 缺口与 R2 门

缺口当时有二：R1 精确路径不回传响应头（B 侧只有体）；用户正常流量的凭证指纹未比对（方法见第 1 章）。DIRECT 探针后新增结论见第 10 章。
是否允许建 R2：当时否（Retry-After 约 15 小时，属配额叙事下的判断）。DIRECT 证据出现后，R2 门更新见第 10 章。
Error evidence 修复保留为 R2 前置要求：错误体、Retry-After、限流头、provider request ID 必须持久化，不得再只存状态码。

# 9. Verdict

TRANSPORT_ROOT_CAUSE_IDENTIFIED。R1 的 INCONCLUSIVE 维持，但原因从未知 provider 失败收敛；planning 语义问题本身既未被证实也未被证伪，仍待新 lineage。
本轮零生产改动，临时取证脚本已删，证据 JSON 留在 build 目录（git 忽略），只提交本 addendum。

# 10. DIRECT 探针更新（同日 09:14 UTC，单发）

同 eval key、同 R1 请求构造，唯一变量为显式禁用环境代理直连远端：200，延迟约 3.9 秒，mimo 真实回包（附带 planning 形 JSON，仅作形状参考，非 benchmark 证据，不计门控）。
而 11 分钟前的同 key 代理请求（Probe B）为 429。本机 8 个代理变量全 SET，runner 的 urllib 默认走环境代理。
R1 根因更新为 transport proxy/egress contamination：270 次请求经由出口代理路径拿到 429，与配额、pacing、UA、请求体无关。429 体由代理自产还是经其转发无法从此处区分，但 differentiator 已证实（DIRECT 通、代理不通，11 分钟内）。
R2 设计（只设计不跑）：复用全部冻结项（prompt f8a9cac7、schema、reason codes、case set、映射、门控），transport 改为显式 DIRECT 空代理 opener，
保留 error body、Retry-After、限流头、request ID 持久化要求；新 lineage、新目录、新 manifest；先小批量固定间隔预检拿 200 再议 270，间隔届时按证据冻结。
