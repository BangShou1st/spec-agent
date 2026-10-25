---
status: Historical
authority: Historical Reference Only
---

# MS-CROSS 验收证据 — Model Service V2 跨协议独立审查、缺陷修复、统一验收与真实验证

- 日期: 2026-10-24
- 分支: `dev`(基线 `5e4976883097687080eb14b81feed49ca6d79236`,MS-C3 验收提交;与 `origin/dev` 同步 0/0)
- 最终结论: 见 verification.json(本文件第 8 节同步)

## 1. 执行范围

对 MS-C1(continuation sidecar)+ MS-C2(Responses 原生工具)+ MS-C3(Anthropic Messages)的组合进行:

1. 六面独立架构审查(连接配置 / 路由与协议适配 / continuation / 能力资格与执行安全 / 数据库迁移 / 测试架构),全部走生产代码路径。
2. 修复 3 项确认缺陷(1×P1、2×P2),全部带修复前后回归。
3. 建立跨协议复用的兼容性验收矩阵([compatibility-matrix.md](compatibility-matrix.md))。
4. 对 Chat Completions 协议(修复后的生产 probe 路径)执行真实验证:Gemini 免费层 4 次请求。
5. 全量回归(Java 全量 + 数据库集成 + Python brain + contracts + 前端)与远端 CI。

## 2. 架构结论

- **无跨阶段 P0/P1 组合缺陷**(修复 F-1 后)。三种协议真正复用同一套连接管理(UUID 行 + 加密凭据 + 修订号)、冻结绑定(`custom-provider:<uuid>:<revision>` / Run 指纹)、资格校验((org, UUID, revision, capability) 记录 + STRUCTURED 激活门禁)、工具授权(冻结 Catalog + recordedTool + CapabilityRuntime)与幂等账本(MODEL/TOOL reserve + 语义幂等键)。
- 协议差异被限制在传输/适配层,且失败关闭策略一致:任何不能保真重放的形状(Responses 混合输出、Anthropic tool_use 后 text、thinking+tool_use、截断、refusal、非 completed 终态)在释放可执行 ToolCall 之前拒绝;通用契约层统一强制 `finishReason ∈ {stop, tool_calls}`。
- MS-C1 sidecar 的授权链(epoch+lease+RUNNING+org+thread+provider/model/revision+source MODEL SUCCEEDED)与原子提交(失败即永不 SUCCEEDED)经代码级确认完整;Python 仅透传不透明 `gacr-` 引用。
- 新增遵循三种既有协议的 Custom 连接只需填 API Format/Base URL/Key/Model ID 并通过 validate(三能力探测走生产传输),零代码改动。

## 3. 修改内容

| 文件 | 变更 |
| --- | --- |
| `backend/.../provider/CompatibilityProbeService.java` | F-1:probeNativeTools 三协议统一走生产原生传输,删除 Chat Completions 旁路实现 |
| `backend/.../modelsettings/CustomProviderSettingsService.java` | F-2:极短密钥(≤4)不再原样进 maskedKey |
| `backend/.../provider/AnthropicMessagesProtocolAdapter.java` | F-4:多条 system 消息合并进顶层 system,不再降级为 user |
| `backend/.../provider/ModelProtocolHarnessTest.java` | F-1 回归断言(probe 请求 `stream:false`) |
| `backend/.../provider/AnthropicMessagesProtocolAdapterTest.java` | F-4 回归测试(多 system 合并) |
| `backend/.../modelsettings/MsCrossChatCompletionsProbeLiveTest.java` | 新增 opt-in 真实验证套件(external-service + database-integration 门禁) |

完整发现清单(含未修复的 P2/P3 记录)见 [findings.md](findings.md)。

## 4. 真实供应商验证

- **Anthropic Messages(官方 API)**:`LIVE_NOT_RUN` —— 无可用 Anthropic 凭据(桌面 key2.txt 经前缀与历史证据核实为 Gemini key)。按任务书标记 NOT_RUN,未发起任何请求。
- **Chat Completions + Gemini 免费层(OpenAI 兼容端点)**:`LIVE_VERIFIED`(本轮,4 次推理请求:probeChat 1 + probeCustom 1 + probeNativeTools 2;无重试)。验证对象是 **F-1 修复后的生产 probe 路径**:普通 UUID Custom 连接 → `ModelProvidersService.validate` → 三能力 VERIFIED 落库 → 删除级联清理。凭据仅进程内使用,未写入任何文件/日志/报告。
- Gemini 三能力 + 双 Agent 全链路的真实证据仍以 `model-service-v2-live-20261023`(12 次请求)为准,本轮不重复调用。
- Responses / Anthropic 的 live 状态维持 `LIVE_NOT_RUN`(离线 OFFLINE_VERIFIED);Zen/Kilo 在 V2 架构下无专门 live 复验证据(`NOT_RUN`)。

## 5. 用户 WIP 保留

用户既有工作区修改(`ModelSettingsIsolationIntegrationTest.java`、`docs/v2/evidence/SHARED_RAG_ACCEPTANCE_PRECHECK.json` 及全部 untracked RAG/验收材料)原样保留,未纳入本轮提交。

## 6. Git 与 CI

- 代码提交:`4a5876418b4ac3534d69d7ff92f9a12f43919f93`
- 证据提交:本目录(见 manifest.json 文件哈希)
- 推送:普通快进推送 `5e49768..d85a440` 至 `origin/dev`(不 force、不动 `main`、不建 PR),推送后本地与远端 0/0 同步。
- 远端 CI(推送后完整验证):Run https://github.com/BangShou1st/spec-agent/actions/runs/40109379689 — conclusion **success**,10/10 job 全绿(validation-mode、contracts-static、backend-independent、backend、eval-baseline、brain、cross-language、frontend、e2e、validation-summary)。
- 最终裁决:**`MODEL_SERVICE_V2_CROSS_PROTOCOL_PASS`**(工程与离线验收全过、修复路径有真实供应商证据);Anthropic 官方端点与 Responses 官方端点维持 `LIVE_NOT_RUN`,Zen/Kilo live 复验 `NOT_RUN`,均已在矩阵中如实标注,不宣称真实兼容性已认证。

## 7. 范围外(本轮明确不做)

Anthropic SSE 增量、extended thinking 续接、Server Tools、Computer Use、第四种协议适配器、模型设置 UI 重构、Zen/Kilo live 复验、历史明文凭据清理(数据维护决策)。

## 8. 测试数量(本地,真实执行记录)

| 套件 | 完成 | 失败 | 跳过 |
| --- | --- | --- | --- |
| Java `testOffline`(含修复定向回归) | BUILD SUCCESSFUL | 0 | 0 |
| Java `test` 全量 | 2655 | 0 | 41(既有门禁) |
| Java `testDatabaseIntegration` | 1005 | 0 | 41(既有门禁) |
| Python brain `pytest` | 344 | 0 | 0 |
| Python tools 契约 `pytest` | 239 | 0 | 0 |
| Frontend typecheck / unit / build | 987 通过 | 0 | 1(既有 vitest skip) |
| 真实验证 `MsCrossChatCompletionsProbeLiveTest` | 1 | 0 | 0(4 次真实请求) |

跳过项与 MS-C3 基线一致(41+41 为既有 live-provider/环境门禁用例);`test` 完成数 2655 = 基线 2654 + 本轮新增 1 条 system 合并测试。
