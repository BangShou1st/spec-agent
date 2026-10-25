---
status: Runbook
authority: Current Authority
date: 2026-10-23
---

# 自定义 API 接入与免费验收

设置 → 模型 → ＋：填写显示名称、协议、Base URL、Key，选择或手动输入模型 ID，保存连接，测试连接，再设为当前 Provider。多个连接彼此独立；更换模型、地址、Key 或协议后重新测试。当前使用的连接需先切换再删除。

## OpenRouter 接入备忘

按 2026-10-23 官方文档核对，不作为内置提供商或生产默认值：

| 项目 | 配置 |
| --- | --- |
| API 协议 | CHAT_COMPLETIONS |
| Base URL | `https://openrouter.ai/api/v1` |
| 聊天请求 | `POST /chat/completions` |
| 模型发现 | `GET /models` |
| Key 限额查询 | `GET /key` |
| 鉴权 | `Authorization: Bearer <API_KEY>`，仅由 Java 安全宿主使用 |

来源：[官方 Quickstart](https://openrouter.ai/docs/quickstart)、[模型目录](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties)、[Key 与限额](https://openrouter.ai/docs/api_reference/limits)。

真实验收从当时目录选一个明确标价为零的固定模型 ID，检查其能力及实际额度。不要写死会下架的模型；不要以模型名称或 `:free` 字样替代当前价格证明。`openrouter/free` 是动态路由，不用于固定模型工具资格的可重复验收。免费仍可能受限流，429 不是永久不支持。

其他中转商同样按其实际公布的协议、版本根地址和 Key 添加。Magpie 等品牌不需要专属业务分支；只有不同协议或真实私有扩展才需要适配器开发。兼容聊天不等于兼容结构化输出、工具续接或流式。

## 通用实时资格 Harness

先通过应用 API 在专用隔离数据库配置连接，凭据仅由 Java 加密保存。`ModelConnectionLiveQualificationTest` 使用：

- `SPEC_AGENT_LIVE_CONNECTION_TEST=true`
- `SPEC_AGENT_LIVE_CONNECTION_ID=<UUID>`
- `SPEC_AGENT_LIVE_MODEL=<固定免费模型 ID>`
- `SPEC_AGENT_LIVE_MAX_REQUESTS=4`
- 标准 `SPEC_AGENT_TEST_DB_*` 隔离目标、服务端真实 endpoint 与显式 `-PdatabaseIntegration=true -PexternalServices=true`

调用前 GET 目录核实免费，随后最多四次底层模型请求，无自动重试。没有 Key、免费证据或额度时不运行，不改用付费模型。未执行或跳过的 live Harness 不能报告为通过。

目录完全不公布价格时（Gemini 的 OpenAI 兼容 `/models` 就是一例），零费用无法由元数据证明，必须由 owner 为该轮出具 `SPEC_AGENT_LIVE_COST_ATTESTATION` 声明，并把声明与请求硬上限一起记录在证据中。绝不由模型名称、`:free` 字样或历史价格推断免费。

Gemini 目录返回 `models/<id>` 前缀，而 Chat Completions 端点使用裸 id；目录只是发现元数据，选中模型保持裸 id 并照常验证。V2-Live 端到端验收由 opt-in 的 `ModelServiceV2LiveAcceptanceTest` 驱动（`SPEC_AGENT_MODEL_V2_LIVE=true`）。
