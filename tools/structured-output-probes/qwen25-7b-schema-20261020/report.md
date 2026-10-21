# Qwen 2.5 7B Pydantic JSON Schema 探测

日期：2026-10-20

状态：**STOPPED_AFTER_DECISION_PROBE**

## 范围

- 模型：`qwen2.5:7b-instruct`
- Ollama：`0.40.6`
- 请求：`POST http://127.0.0.1:11434/v1/chat/completions`
- 输入：一次不含私人资料的合成场景
- 新增推理请求：`1 / 2`；未调用云端、裁判模型、Broker 或数据库
- 既有 `tools/p3b/c09-live-runs/qwen25-7b-20261020/` 和
  `tools/structured-output-probes/qwen25-7b-20261020/` 未改动

## DECISION 结果

- Schema 来源：`ModelDecisionOutput.model_json_schema(by_alias=False, mode="validation")`
- Schema 名称：`project_decision_v1`
- 规范化 JSON SHA-256：`53d8be636cf878627df517b7670d06bf488ce8c20dde993479ac8ea392f7edb7`
- Provider 接受请求：是，HTTP `200`
- 生成耗时：`8401 ms`
- JSON 合法：是
- Pydantic 校验：否
- 首个失败字段：`action.action_family`（自定义 validator 抛出 `value_error`）
- 完成原因：`stop`；截断：否

完整 prompt 和模型输出均未保存。运行器只保留上述结果摘要和首个失败字段。

## 结论

Pydantic 可以直接导出 DECISION JSON Schema。生成的 Schema 将
`observation.known`、`unknowns`、`conflicts`、`risks` 声明为字符串数组，且此完整
DECISION Schema 被本机 Ollama 接受。但 `ModelDecisionAction.action_family` 的 Python
自定义 validator 没有反映为 Schema 枚举，因此本次模型输出虽是合法 JSON，仍被
Pydantic 拒绝。该结果不能证明模型输出整体满足 Python 契约，也不能证明
`observation.known` 的本次值通过了校验。

按停止规则，不发送 STATE_UPDATE 探测，不实施 Broker/生产适配，不运行测试。本轮
未修改生产代码、Prompt、Pydantic 模型、Provider 配置或数据库。此报告不覆盖、也不
重写此前 7B Agent 实验的结果。
