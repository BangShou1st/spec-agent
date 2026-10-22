> [!NOTE]
> **Document Status**: `Historical` (历史归档)\
> **Authority**: 本文档为历史阶段性记录，仅供追溯与复盘使用。当前系统的实际行为与规约以 [docs/README.md](../../README.md) 及 [docs/v2/README.md](../../v2/README.md) 为准。

---

# P3-B C09 真实模型 A/B 验证决策（2026-10-19）

## 结论

- Live 执行状态：`BLOCKED`。
- 自动 A/B 决策：`INCONCLUSIVE`。
- 本轮新发模型请求：`0/8`；C09 的 A/B 没有启动。
- C09 候选规则尚未证明比默认 Prompt 更准确地询问缺失参数，证据不足以采用。生产默认 Prompt 保持不变。

## Git 基线

开始检查时 `dev` 与 `origin/dev` 均为 `9172d0ce14b299b70f6d98d4934d1f90bad25031`，工作区干净；`origin/main` 是 `origin/dev` 的祖先。本报告是本轮唯一新增交付，不改 `main`、Prompt、oracle 或历史 A/B 报告。

## Provider、Model 与环境

### OpenCode 优先路径

OpenCode 官方模型目录当前列出 DeepSeek V4.1 Flash Free 的准确 ID `deepseek-v4.1-flash-free`，并将其列为 OpenAI Chat Completions 模型；目录标注该免费供应限时提供。官方目录没有证明本机别名 `dsv4.1free` 的映射。[模型目录](https://dev.opencode.ai/v2/docs/console/models/) [Inference API](https://dev.opencode.ai/v2/docs/console/api/inference/)

本机别名映射及可用推理路由未验证：对 `spec_agent` 的只读检查未发现 `model_provider_settings`、`opencode_settings` 等模型设置表；`SPEC_AGENT_EVAL_*` 环境变量未配置，Brain broker `localhost:8100` 不可达。本轮没有调用 OpenCode 推理接口。

### 已冻结的 Kilo 路径

已有协议固定为 Kilo / `cohere/north-mini-code:free`。已有报告累计 44/64 次请求，C09 为 0 次；C08-B holdout 发生 `PROVIDER_UNAVAILABLE`，记录 1 次失败请求和 1 次 provider retry。C08-A base 另有 `MODEL_PROTOCOL_VIOLATION`。协议在 C08-B 失败后明确停止 C09，因此不能把 Kilo/Cohere 的历史结果当作 OpenCode/DeepSeek 结果，也不能用延迟补测绕过停止条件。

冻结协议所指的隔离库 `spec_agent_p3b_live_20261018` 当前不存在；评测 Brain 也不可达。当前 `spec_agent` 没有可确认的活动 Provider/Model 设置。检查只读完成，没有读取凭据字段、改动用户设置或触及 API Key。

## C09 A/B 记录

| 检查项 | A：默认 Prompt | B：默认 Prompt + C09 候选规则 |
| --- | --- | --- |
| base | 未执行；无原始动作 | 未执行；无原始动作 |
| holdout | 未执行；无原始动作 | 未执行；无原始动作 |
| STATE_UPDATE / DECISION | 未发送 | 未发送 |
| Prompt hash | 未冻结（无新实验启动） | 未冻结（无新实验启动） |
| Java Eligibility、执行、能力调用 | 无测量 | 无测量 |
| 自动语义裁判 `p3b-c09-rule-judge.v1` | 无样本 | 无样本 |

本轮模型请求数为 0，未调用裁判模型。现有 A/B 自动评估器重算后，C09 返回 `INCONCLUSIVE`，原因码指向“C08 B arm 命中预注册的 provider-failure 停止条件后，C09 未启动”。因此 C09 的结构、语义与实验完整性均为 `NOT_MEASURED`，不能据此判断任何一臂的行为优劣。

## 已有 C03/C08 评估对下一步的提示

自动重算现有冻结报告得到：C03 `REJECT`，C08 `INCONCLUSIVE`，C09 `INCONCLUSIVE`。C03 holdout 中 A 选择 `RESPOND_TO_USER`、B 选择 `REQUEST_USER_INPUT`，预注册检查将 B 判为重复追问回归；同时 C01/C02 的部分样本存在 DECISION hash 缺失、source-reference audit 未通过和 payload 校验问题，下一轮需先修正证据完整性并离线分析候选规则。C08 因协议错误及 provider failure/retry，尚不能形成可靠行为对照。

后续优化顺序：

1. **C03 重复追问：**先分析 holdout 回归，并补齐离线等价改写、已回答信息和真正缺参的反例覆盖；在证据和离线回归完整前不追加类似 Prompt 规则。
2. **C08 必要能力调用：**先解决固定模型路径上的 provider retry 与决策协议有效性，再评估必要调用是否被准确选择；保留 Java eligibility 和执行授权边界。

本轮没有启动 C03 或 C08 的 Live A/B。

## 回归测试

第一次 Java 尝试连接默认测试 PostgreSQL 时，`evalBFast` 的 134 项中有 44 项因服务器缺少 `vector` 扩展而失败，错误发生于 `V40__retrieval_runtime.sql`；该尝试没有进入 `testNonLive`。随后使用仅绑定 `127.0.0.1` 的临时 `pgvector/pg17` 测试库重跑两项任务。镜像自动分配了匿名数据卷；结束时删除该临时容器及其匿名卷。开发库 `spec_agent` 未被修改。

| 命令/任务 | 最终结果 |
| --- | --- |
| `uv run --project agent-brain --extra dev python -m pytest tools/tests -q` | 160 passed |
| `uv run --project agent-brain --extra dev python -m pytest agent-brain/tests -q` | 253 passed |
| `evalBFast --rerun-tasks`（临时隔离 pgvector 测试库） | 134 tests；0 failures；0 errors；0 skipped |
| `testNonLive --rerun-tasks`（临时隔离 pgvector 测试库） | 2197 tests；0 failures；0 errors；36 skipped |

Java 回归包含 `P3BSemanticABCrossLanguageTest`、P3-B 评测/归因、Brain 契约诊断和 ActionEligibility 测试。运行后测试 XML 已保留在 `backend/build/test-results/`；临时数据库在证据汇总后移除。

## 采用决定

C09 候选规则不推广；默认 Prompt、oracle、安全门禁和模型选择均保持原状。只有在未来独立冻结实验满足 Provider/Model 身份、隔离数据库、Brain 路由、请求计数和 prompt hash 条件后，才有资格重新评价真实模型行为。即使未来一次 C09 A/B 接受，也只代表固定样本上的受控改善。
