# C09 Live A/B 运行报告 — 2026-10-19

## 判定

**INCONCLUSIVE。** 本轮实际运行了 A 臂的 base 和 holdout，各 2 次上游模型请求，共 4 次；B 臂没有运行。因而这不是一组完整的 A/B，也没有证据证明候选 Prompt 改善了 Project Agent 的追问行为。

本报告按冻结规则保留未决结果。自动裁判 `_decide_c09()` 因缺少 B 返回 `INCONCLUSIVE`，没有修改 oracle、阈值或候选标签。

## 冻结 Provider 与协议

- Provider：Kilo AI Gateway；Model：`cohere/north-mini-code:free`。
- 首次推理前对实际 Gateway 的 `/models` 发起只读目录查询，HTTP 200，精确模型 ID 在列，input/output 价格均为 0。隔离库中的 Kilo key 为 `NULL`；项目现有免费模型策略强制匿名凭据。Kilo 官方文档说明 `:free` 模型支持匿名访问且免费请求不计费：[认证说明](https://kilo.ai/docs/gateway/authentication)、[使用与计费](https://kilo.ai/docs/gateway/usage-and-billing)。
- Java 使用 OpenAI-compatible Chat Completions 协议，目标为 `https://ai-gateway.kilo.ai/api/v1`。配置中 fallback 列表为 0；未发生重试、Provider 切换或 Model 切换。
- OpenCode 的 `dsv4.1free` alias **未确认**。本地配置中看到正式 ID `deepseek-v4.1-flash-free`，但没有找到该 alias 的映射；没有读取 OpenCode auth 文件或 API Key，因此没有调用 OpenCode。
- A 使用未启用候选规则的生产 Prompt，DECISION system Prompt SHA-256：`7417cbe5b2fc1c2146b7eae54cd0a9fd9f3d47ce63d42bf7`。B 候选 Prompt SHA-256：`2078dd4e3da07184efdad1a3d6e9997a19d52a27d6330211d15d35b2835db38d`。两臂 STATE_UPDATE Prompt 相同，SHA-256：`d8490e85d7a408fdb549cd7e0ac1cfd8e64ebd36ad0910d17d72015105351d77`。
- 预算在冻结协议中固定为总计 8 次、每臂最多 4 次、judge 0 次、retry 0 次、fallback 0 次。历史 C08 停止状态没有导入。

## 隔离环境

- 新建 Docker 容器 `spec-agent-p3b-c09live-20261019`，只绑定 `127.0.0.1:5436`；专用 Live 数据库：`spec_agent_p3b_live_c09_20261019_01`。
- `vector` 扩展版本 0.8.6。现有 Flyway 自动迁移成功，迁移到 V99。
- Java 侧服务器身份经 `EvalLiveIsolatedDatabase` 校验为 `spec_agent@172.17.0.2/32:5432/spec_agent_p3b_live_c09_20261019_01`。种子操作只针对该新库。用户开发库 `spec_agent` 没有连接或修改。
- 独立 Python Brain 在 `127.0.0.1:8123` 以 `broker` 模式健康运行；Java Host/Internal Broker 使用端口 18082。实际收到 2 次 STATE_UPDATE 和 2 次 DECISION，4 次 Java Broker 推理均被运行事件记录为成功；这证明了 Java Host → Python Brain → Java Broker → Kilo 的调用路径。
- ActionEligibility 为 `ENFORCED`，与生产默认一致。生产 Prompt 默认集合、模型绑定、工具授权及数据库安全代码没有更改。

## 实际动作与自动语义评价

冻结场景要求检索项目资料，`memory.search` 可用，`scope=PROJECT` 已给出，必填 `query` 缺失。正确行为是仅向用户索取检索词/query，不重复询问 scope，不编造参数，不调用缺参工具。

| Arm / 场景 | 上游请求 | 模型选择 / 实际动作 | 追问文本 | 自动评价 | 能力调用 |
|---|---:|---|---|---|---:|
| A / base | 2 | 未测量；Run 失败 | 无 | `UNRESOLVED`：`action_not_measured` | 0 |
| A / holdout | 2 | `RESPOND_TO_USER` | 无 | `INCORRECT`：动作不在冻结可接受集合，未请求 query | 0 |
| B / base | 0 | 未运行 | 无 | 未测量 | 0 |
| B / holdout | 0 | 未运行 | 无 | 未测量 | 0 |

A/base 的 DECISION 输出不是有效 JSON。Python Brain 在 `brain.model_output_parse` 产生 `MODEL_OUTPUT_INVALID_JSON`，并向 Java Host 返回一次 HTTP 502；所以该场景没有可评分的动作或问题。A/holdout 未重复询问 scope，也没有提出无关澄清或编造工具参数，但它直接响应而没有索取缺失的 query，因此未满足 C09 目标。两条都没有执行缺少参数的能力调用。A/holdout 的 source reference 审计为确定性通过；A/base 因没有动作而未测量。

## 停止条件偏差

冻结协议要求遇到 HTTP 502 立即停止。现有 Java Harness 没有在 `MODEL_PROTOCOL_VIOLATION` / 无效模型输出后停止，因此在 A/base 的 Brain 502 后又运行了 A/holdout，额外发送 2 次请求。这是本轮记录的协议偏差；总请求仍为 4/8，没有触发 Provider fallback、retry 或切换。发现该行为后，没有启动 B，也没有重跑 A。

我已修正 Harness，使 `NON_JSON_MODEL_OUTPUT`、`MODEL_PROTOCOL_VIOLATION` 和 `INVALID_ACTION_PAYLOAD` 在下一场景前停止，并让报告明确记录该停止状态。此修复不改变生产逻辑，也没有用于继续本轮实验。

Provider 请求事件中没有失败、429 或认证错误；没有 Kilo upstream 502 记录。观察到的 502 是 Brain 对模型无效 JSON 的契约错误响应。Uvicorn 另有 `Unsupported upgrade request` 警告，没有对应的 STATE_UPDATE / DECISION 请求失败。所有 4 次已发送的模型请求均计入预算，judge 调用为 0。

## 回归结果

- `tools/tests`：160 passed。
- `agent-brain/tests`：253 passed。
- `P3BSemanticABCrossLanguageTest` 与 `P3BLiveDatabaseGuardTest`：10 passed。
- `evalBFast`：134 passed，0 failed/error/skipped。
- `testNonLive`：2,197 passed，0 failed/error，36 skipped。
- Java 回归使用本轮新建的独立库 `spec_agent_test_c09_20261019_01`，同样有 `vector` 0.8.6；未使用历史污染测试库。

## 产物与下一阶段

- 冻结协议：[protocol.json](protocol.json)
- A 臂原始脱敏报告：[arm-a.json](arm-a.json)；请求明细：[arm-a.json.attempts.jsonl](arm-a.json.attempts.jsonl)
- 既有裁判输出：[c09-semantic-decision.json](c09-semantic-decision.json)
- 冻结协议 SHA-256：`9989a8ca50c510f7f0e7cfb619a1f3fd29e9323f5ca31c3dd28f261c53511d2e`
- A 臂报告 SHA-256：`82cecf5c758fe4d6dbd9d457c7d60bcbce5ab4c1208ebfcb78982f2a40d5f4f5`

候选规则没有进入生产 Prompt，生产默认 Prompt 保持原样。由于 B 臂未执行，不能据此进入 C03/C08 行为优化，也不能判断该候选规则是否改善了追问质量；需要另行冻结一份新协议后再做有效比较。
