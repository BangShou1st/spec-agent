# P2 收尾：OpenCode Zen 协议兼容与来源权限收口（设计 + 证据）

日期：2026-10-16。分支 `main`，基线 `520a8c728edaa54da3a37a82a2f50c119c4c34a2`。

本轮范围：在**不改动** Java/Python 职责边界、不新增基础设施、不改 Flyway、不加数据库字段、
不重写推理层的前提下，收口两件事：

1. F1 来源依赖账本的真实格式（`TOOL_COMPLETED.payload.resourceRefs`）；
2. OpenCode Zen 的**协议分类 / 免费判定 / 基址**判定，确保"前端可选 = 执行可用"。

---

## 1. F1 收口：账本格式（真实 broker 事件）

`GaCapabilityBroker` 写进 `TOOL_COMPLETED.payload.resourceRefs` 的是
`GlobalAssistantToolPresentation.projectResources` 产出的**结构化资源对象**
（与 `SOURCE` / `WEB_SOURCE` 卡片同列一个数组）：

```json
{"kind":"PROJECT","id":"<uuid>","label":"…","metadata":{…}}
```

而回给 Python 的 broker `sourceRefs` 是 `"project:<uuid>"` 字符串。两者**不是同一种形态**：

| SQL 取值方式 | 对象形态（真实 broker） | 字符串形态 |
| --- | --- | --- |
| `jsonb_array_elements_text` + `starts_with(ref,'project:')` | **不匹配**（元素文本是 `{"id": …, "kind": "PROJECT"}`） | 匹配 |
| `jsonb_array_elements` + `ref->>'kind'='PROJECT'` | 匹配 | 不匹配 |

实测（隔离 PostgreSQL 17，`jsonb_array_elements_text`）：

```
{"id": "11111111-…", "kind": "PROJECT"} | starts_with(v,'project:') = f
project:11111111-…                      | starts_with(v,'project:') = t
```

因此修复前的判定对**真实 broker 观察**失败开放（撤权后仍可读）。修复：
`GaSourceDependencyGuard` 的账本谓词改为 `jsonb_array_elements`，
按 `kind='PROJECT'` 取 `id`，并保留字符串形态兼容（历史行 + broker sourceRefs 风格）。

验收测试 `RevokedProjectObservationReuseIntegrationTest#realBrokerProjectObservationIsIsolatedOnceTheProjectRoleIsRevoked`
**走真实 `GaCapabilityBroker`** 产生观察后撤权：修复前该断言失败，修复后通过。

---

## 2. OpenCode Zen：与本地参考代理的差异（差异表）

参考实现：`D:\OpenCode-UA-Start\opencode-zen-proxy.py`（V2，非官方本地 OpenAI 兼容网关）。

| # | 事项 | 本地代理参考 | Spec Agent（修复前） | 结论 |
| --- | --- | --- | --- | --- |
| 1 | 上游基址 | `https://opencode.ai/inference/v1`（`ZEN_API_BASE`） | `https://opencode.ai/zen/v1` | 两者都真实可用（见 §3）；**默认不改**，用既有配置支持新地址 |
| 2 | 协议选择 | chat / responses / anthropic 三端点，异协议 → 422 | 只有 `/chat/completions` | 不实现多协议执行（越界）；改为**显式记录协议**并只提供 Chat 模型 |
| 3 | 模型目录 | `GET /models` + `models.opencode.ai/api.json`（价格、`provider.npm`） | 只看 id 后缀 `-free` | 采用官方目录元数据（成本 + npm + 下线状态） |
| 4 | 免费判定 | `cost.input==0 && cost.output==0`；付费模型即使名字带 `-free` 也不放行；缺元数据不默认免费 | 仅 `endswith("-free")` | 等价修正（见 §4） |
| 5 | 会话/请求 ID | `ses_*` / `msg_*` 形态伪造 + 内容哈希会话 | `OpenCodeZenSessionIds` + run 会话 | 保持既有实现；**不照搬**哈希/伪造 |
| 6 | SSE | 严格解析、`[DONE]`/截断/流内错误失败关闭、32 MiB 上限 | `parseStreaming` / `GaNativeSseDecoder` 已严格校验终态与流内错误 | 既有测试已覆盖，不改 |
| 7 | 工具校验 | 未声明工具拦截、名字归一化匹配 | `GaChatCompletionsAdapter.validateResponse` 只接受运行时 catalog 的工具名 | 既有实现更严格（拒绝而非改写），不改 |
| 8 | 401/403/429 | 原样透传上游错误体 | 映射为 AUTHENTICATION / RATE_LIMITED / SERVER_ERROR + 白名单诊断 | 既有测试已覆盖，不改 |
| 9 | 代理/超时 | 直连失败回退本地代理 `127.0.0.1:7897`；目录 12s / 推理 180s | `spec.agent.model.opencode.proxy`（默认 DIRECT）+ settings/production 两套超时 | 取得更严（不静默回退到第三方代理）；元数据沿用同一代理策略 |

不做的事（明确记录）：不复制 Python 代理、不使用 `Bearer public`、不伪造 CLI UA/会话、
不做流式缓存重播、不规避上游付费门禁。

---

## 3. 上游基址：实测证据与结论

真实端点（本机直连，只读 `GET /models`）：

| 地址 | 结果 |
| --- | --- |
| `https://opencode.ai/zen/v1/models` | HTTP 200，`{"object":"list","data":[{"id":"big-pickle",…}, …]}`（87 个模型） |
| `https://opencode.ai/inference/v1/models` | HTTP 200，同一份列表 |
| `https://models.opencode.ai/api.json` → `opencode.api` | `https://opencode.ai/inference/v1`（官方目录声明的规范基址） |

结论：
- **不替换**已存在的生产默认基址 `https://opencode.ai/zen/v1`（它仍受支持）；
- `https://opencode.ai/inference/v1` 通过既有配置
  `spec.agent.model.opencode.base-url` 使用（传输层原样拼接 `<base>/models`、
  `<base>/chat/completions`）；
- 三种地址（`/zen/v1`、`/inference/v1`、第三方本地 `/v1`）语义不同，测试
  `HttpOpenCodeZenTransportTest#configuredBaseUrlIsUsedVerbatimForEveryPathWhileTheDefaultStaysUnchanged`
  分别断言其路径拼接，且默认值未被替换。

---

## 4. 免费与协议判定：真实元数据事实

`https://models.opencode.ai/api.json` 的 `opencode` 提供商（124 个模型）：

| 模型 | `cost.input/output` | `provider.npm`（缺省 = 提供商级 `@ai-sdk/openai-compatible`） | 判定 |
| --- | --- | --- | --- |
| `big-pickle` | 0 / 0 | 缺省 | 免费 · Chat（**无 `-free` 后缀**，旧规则会漏掉） |
| `cat-jam-free` 等 | 0 / 0 | 缺省 | 免费 · Chat |
| `muse-spark-1.3-contributor-free` | 0 / 0 | `@ai-sdk/openai` | 免费但 **Responses**（旧规则会把它当 Chat 发出去 → 必然失败） |
| `muse-spark-1.2-contributor-free` | 0 / 0 | `@ai-sdk/openai` | `status=deprecated` → 下线，排除 |
| `claude-opus-5` | 5 / 25 | `@ai-sdk/anthropic` | 付费 · Anthropic |
| `jev-1.13-free`、`test` | 元数据未登记 | — | 未验证 → 不进选择列表 |

实现（新文件 + 改动）：

- `OpenCodeModelProtocol`：`provider.npm` → CHAT / RESPONSES / ANTHROPIC / OTHER / UNKNOWN；
- `OpenCodeZenModelMetadataSource` + `OpenCodeZenMetadataCatalog`：拉取官方目录（可配置 URL、
  TTL 缓存、16 MiB 上限、失败保留上一次成功视图并节流重试）；
- `OpenCodeModelCatalog`：`isFreeModel` 用价格元数据（缺元数据退回 `-free` 后缀，绝不默认免费）、
  `chatExecutable`/`listAvailableModels`/`listFreeModels` 只含协议已验证的 Chat 模型；
- `ProviderModelCatalogService.discoverOpenCode`、`OpenCodeSettingsService.currentCandidateModels`：
  选择列表只来自已验证模型，保存/切换模型因此不可能选中异协议模型。

失效边界（如实记录）：
- 元数据不可用时退回既有 `-free` 后缀规则（协议无法判定，保持既有可用列表），
  **不假设任何模型免费**；此时不新增协议过滤（避免误伤正在工作的模型）；
- 本轮**未**在推理热路径加协议断言：`GaExecutionPreparation` 仍可能为一个
  **本轮之前已保存**的异协议模型发 `/chat/completions`。补齐它需要在热路径上引入
  元数据读取与新的失败语义（控制面终态化），属独立的一小步设计，未在本轮硬做。

---

## 5. 测试与验证

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| F1 真实 broker 账本 | `testNonLive --tests *RevokedProjectObservationReuseIntegrationTest` | 5/5 通过（修复前 1 失败） |
| 跨语言夹具 | `SPEC_AGENT_GA_CROSS_LANGUAGE_TEST=true testNonLive --tests *GaExecutionCoordinatorIntegrationTest` | 通过（修复前 `@AfterEach` 读已删线程失败） |
| 模型目录/协议/元数据 | `--tests *model.provider.* *model.inference.* *modelsettings.*` | 239 通过 |
| 全量 | `testNonLive`（隔离 PostgreSQL `127.0.0.1:5546/spec_agent_test`） | 见提交报告 |

测试档离线化：`application-test.yml` 把 `spec.agent.model.opencode.metadata-url` 指向
本机保留端口（连接必然失败 → 视图不可用 → 退回 `-free` 规则），需要真实元数据事实的
测试用 `@DynamicPropertySource` 指向本机 stub（`OpenCodeModelProtocolGateIntegrationTest`），
全量测试**不访问公网**。

---

## 6. 停止条件核对

未新增 Redis/Kafka/独立模型网关；未复制本地 Python 代理；未改 Java/Python 职责边界；
未新增/修改 Flyway 迁移；未新增数据库字段；未改 F1/F2 授权与事务语义；
未改执行锁顺序、预算、租约、重放与恢复语义；未扩展到 OpenRouter/Embedding；
未为三协议重写推理层。
