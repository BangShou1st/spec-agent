# Qwen2.5 7B 严格 JSON Schema 探测报告

**结论：停止在阶段 B；没有接通生产代码。** Ollama 接受了 DECISION 的真实 Pydantic Schema，合成冲突输入中的 `observation.known` 也按数组生成；但完整输出被现有 Pydantic validator 拒绝，失败字段为 `action.actionFamily`。按本轮停止条件，没有发出第二次推理请求。

## 基线与范围

- 分支：`dev`
- 本轮基线 HEAD：`340255ac19d2c0ba6484c390b1cb982489608092`
- `main` 基线：`74b9815d69a562d6782c4efbef1b7daf3f075905`
- 开始时工作区唯一变化：未跟踪的 `agent-brain/uv.lock`；已保留。
- 本轮只新增本目录中的脱敏探测记录；没有修改 Python/Java 生产代码、Prompt、内部协议、Provider 设置或 Ollama 全局参数。
- 没有重跑历史 C09 实验、没有调用云端或裁判模型，也没有重试。

## 阶段 A：离线契约核实

Pydantic 导出使用 `ModelDecisionOutput.model_json_schema(by_alias=True, mode="validation")` 和 `ModelStateUpdateOutput.model_json_schema(by_alias=True, mode="validation")`，没有手写第二份 Schema。Ollama 官方说明也推荐将 Pydantic 导出的 Schema 传入结构化输出，再用 Pydantic 校验结果：[Ollama Structured Outputs](https://ollama.com/blog/structured-outputs)。

| 模型契约 | 真实导出结构 | 重要限制 |
| --- | --- | --- |
| DECISION | `observation.known/unknowns/conflicts/risks` 都是 `array<string>`；`action` 是对象；`actionFamily` 是必填字符串；`payload` 是对象且允许任意属性；`sourceRefs`、`anchorRefs` 是字符串数组。顶层和受限嵌套对象均拒绝额外字段。 | `actionFamily` 的 Python `_known_family` validator 未出现在默认 JSON Schema 中，因此生成约束只知道它是字符串，不知道允许值集合。 |
| STATE_UPDATE | `claims` 是 `ProposedClaim` 对象数组；claim 的 `kind`、`text`、`status` 是字符串，`confidence` 是 number/null，`sourceRefs` 是字符串数组。 | Python `_known_kind` / `_known_status` validator 未出现在默认 JSON Schema 中；`kind`、`status` 只被声明为字符串。该 Schema 本轮未发给模型探测。 |

Schema 的规范化哈希（SHA-256）与规模：DECISION `ebe4adc6d7d6d23945282f678816b488ac2e9ab82ef95f840bd4295ff85164ca`，1,229 字节；STATE_UPDATE `f5956038714d3cc6e1b0d8a9498d31b51ffd14231b12a3ec195b185ae50741e8`，735 字节。Schema 使用 `$defs` / `$ref`；STATE_UPDATE 还包含 nullable `confidence` 的 `anyOf`。本轮 Ollama 只实际接受并生成了 DECISION Schema；不能据此宣称 STATE_UPDATE 的 `anyOf` 兼容性已验证。

`ChatCompletionsProtocolAdapter` 已将 `ModelOutputContract.JsonSchema` 映射为 `response_format.type=json_schema`、`strict=true` 与 Schema；Provider 不支持契约时现有适配器按错误处理，没有静默降级路径。失去 Schema 的位置在 Brain 内部 Broker：`InternalModelInferenceController.completeAuthorized()` 不论 `callType` 都固定使用 `ModelOutputContract.jsonObject()`。

Broker 目前先校验内部 token、callType 白名单及消息边界，再通过绑定 `runId`、`callType`、请求哈希的执行 grant 授权；模型路由会持久冻结 Run 的 Provider/模型快照。内部推理请求 DTO 不接收 Schema，所以调用方不能任意替换它。若后续接通，应由 Java 按白名单 `callType` 选择受信 Schema，并校验生成版本/哈希与大小、深度；不要把任意 Schema 加入现有请求体。

## 阶段 B：本地探测

| 项目 | 结果 |
| --- | --- |
| 模型 | `qwen2.5:7b-instruct`；Ollama `0.40.6`；本地模型 digest `845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e` |
| 新推理请求数 | 1；请求 1 失败后立即停止；STATE_UPDATE 没有请求 |
| Schema 名称 / 哈希 | `project_agent_decision_output_v1` / `ebe4adc6d7d6d23945282f678816b488ac2e9ab82ef95f840bd4295ff85164ca` |
| Provider 是否接受 | 是，HTTP 200 |
| 请求往返耗时 | 6,662 ms。OpenAI 兼容响应未提供独立 token generation duration；此值是请求端到端耗时，不冒充纯生成耗时。 |
| JSON 合法 | 是 |
| Pydantic 验证 | **失败**，字段 `action.actionFamily`；原始生成值未保存。该字段 Python 类型是 `str`，Schema 也只有 `type: string`，失败来自 `_known_family` 对允许动作集合的校验。 |
| 是否截断 | 否，finish reason 为正常完成 |
| 数组约束观察 | 是。合成输入明确要求把 `observation.known` 写成单个 JSON 字符串，但响应中的该字段是数组；只记录了类型，没有保存 Prompt 或完整输出。 |

此结果说明当前真实 Schema 可被本机 Ollama 接受，并且本次 `known` 类型遵循了数组 Schema；但 Schema 没有表达 `actionFamily` 枚举，因而生成了 Python 契约拒绝的值。它没有证明完整 DECISION 契约受 Schema 约束。按规则，不再发 STATE_UPDATE 请求。

## 原因判断与后续门槛

上一轮 7B 把 `observation.known` 输出成字符串，是因为 Broker 只要求 JSON 对象语法。JSON Object 模式不约束对象字段类型；Prompt 的自然语言要求无法替代机器 Schema，所以字段错误直到 Brain 的 Pydantic 校验才暴露。

本轮证明了数组形状可以在模型生成时受约束，但没有完成端到端结构化输出：同一份 Pydantic 默认 Schema 漏掉了自定义 validator 的枚举集合。暂不值得再跑完整 7B Agent 验证；先让 Pydantic 字段类型本身表达现有的动作/claim 枚举（保持与 validator 相同的允许集合），增加 Schema 对照回归，再用新哈希重新做不超过两次的本地探测。若新 Schema 输出通过 Pydantic，才进入受信 Schema Broker 适配。

没有实施 Provider 无关接通，因此本轮没有改变 ActionEligibility、sourceRefs、模型冻结、Python/Java 校验、`jsonObject`、GA、Kilo/OpenCode/Custom、旧内部协议或 `main`。没有运行 Brain pytest / Java / Gradle 测试，因为阶段 B 失败后未进行代码修改。

## 脱敏与保留

- 不保存合成 Prompt、完整 completion、任何真实用户数据或凭据。
- 没有改变或卸载本机 Ollama 模型；本地 Ollama 服务保持运行。
- 原始 7B 停止实验仍保留在 `tools/p3b/c09-live-runs/qwen25-7b-20261020/`，本轮未重跑或覆盖。
