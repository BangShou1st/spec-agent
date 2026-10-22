# Qwen2.5 7B 本地 Agent 对照报告

日期：2026-10-20

实验：`c09-ambig-qwen25-7b-local-20261020-01`

场景：`P3B-C09-AMBIG` v2，冻结哈希 `6e2484e0a7547e2c`

## 结论

`qwen2.5:7b-instruct` 已成功下载到 `D:\Ollama\models`，并能在 RTX 4060 Laptop 8 GB GPU 上以 8192 上下文完整加载：Ollama 报告 `100% GPU`，llama.cpp 记录 `29/29` 层在 GPU，无 CPU 卸载、无 OOM。

真实 Agent 链路执行了 base 的 `STATE_UPDATE` 和 `DECISION` 两次请求。两次 Ollama 请求都成功返回，但 Python Brain 的 `ModelDecisionOutput` 校验在 `observation.known` 处失败（期望数组，实际字符串），因此没有生成 Java 可消费的决策、动作或交互 Payload。Harness 按协议立即停止，holdout 未运行；本轮共消耗 2/4 次请求。

这次样本不能证明 7B 更适合结构化决策。它在 8K 下的确能完整 GPU 运行，但决策输出没有通过生产契约；**不建议把 7B 设为日常开发 Custom 模型**。开发实例仍保持原来的 `qwen3.5:4b` 配置，全局 Provider 仍保持 KILO。4B 的两次追问主动作合规，但两次 options 都引入用户未提供的主题，仍有交互语义瑕疵。

## 4B 与 7B 对照

4B 数据来自历史文件 `tools/p3b/c09-live-runs/ambig-20261020/single-arm.json` 及当时的 Ollama runner 日志。它是历史基线；与本轮新鲜 7B 样本不是同期随机 A/B。

| 指标 | 历史 4B：`qwen3.5:4b` | 本轮 7B：`qwen2.5:7b-instruct` |
|---|---:|---:|
| 参数 / 量化 | 4.2B / Q4_K_M | 7.6B / Q4_K_M |
| Ollama 磁盘大小 | 3.3 GB（3,324,173,934 bytes） | 4.7 GB（4,683,087,332 bytes） |
| GPU 加载 | 历史证据为 100% GPU、无 CPU 卸载 | 100% GPU，29/29 层，无 CPU 卸载 |
| 运行时显存 | 历史观测约 3.4–6.0 GB | Ollama 约 5.0 GB；`nvidia-smi` 总占用 6,459/8,188 MiB |
| 有效上下文 / 截断 | 8,192；四次请求均未截断 | 8,192；两次请求均未截断 |
| STATE_UPDATE 契约 | 2/2 通过 | 1/1 通过；记录到 1 个 unresolved claim 后进入 DECISION |
| DECISION 契约 | 2/2 通过 | 0/1；Python `ModelDecisionOutput` schema 失败 |
| `REQUEST_USER_INPUT` 主动作 | 2/2 | 未测得；未形成模型动作 |
| 追问是否命中缺失的具体对象 | 2/2，问题文本均询问具体事项 | 未测得；没有可消费的追问文本 |
| options 是否引入无依据主题 | 2/2 有 | 未测得；Payload 未产生 |
| 本次正式运行超时 | 0 | 0；以契约失败停止 |
| 既往单次诊断超时 | 曾有 2 次 90 秒超时；不属于此 4B 正式基线 | 不适用 |
| 最慢 STATE_UPDATE（模型生成 / 阶段墙钟） | 59.81 秒 / 60.33 秒 | 2.94 秒 / 20.66 秒（仅一个 base 样本，含冷加载开销） |
| 完整变体耗时 | base 29.31 秒；holdout 77.05 秒，均完成 | base 27.60 秒后因契约失败；holdout 未运行 |
| 上游请求 | 4/4 成功 | 2/4 成功返回；因模型契约失败停止 |

单看 7B 的一个 STATE_UPDATE，其模型生成时间比 4B 历史样本短；但 7B 随后的 DECISION 未通过契约，且首个阶段墙钟包含模型冷加载。这不能视为端到端胜出。样本数量也不足以证明模型总体优劣。

## 4B 的交互内容

两次都是合法 `REQUEST_USER_INPUT`，`allowFreeAnswer=true`、`allowMultiSelect=false`，主问题均在询问缺失的具体事项，且没有要求用户重复项目范围。

- **base**：`purpose` 为“明确需检索的问题内容以便精准搜索历史讨论记录”；问题是“请问您当前需要处理具体哪项问题？”；options 为“已完成功能验证是否通过”和“需求规格是否已对齐”。两个选项都是用户没有提供的具体主题。
- **holdout**：`purpose` 为“为确定后续资料检索的精确目标，需明确用户所关注的特定项目事件或主题名称。”；问题是“您在本项目中想查找资料的具体是哪一件事或项目主题呢？”；options 为“某历史事件发生的时间与细节”和“项目关键决策或资源分配情况”。这两个主题也没有用户依据。

所以 4B 的结构契约虽通过，Payload 内容仍有语义瑕疵；不能把 `payload_validity=VALID` 等同于全部交互质量合格。

## 7B 的真实链路与停止原因

链路为 `Java Host → Python Brain → Java Internal Broker → CustomInferenceGateway → Ollama → qwen2.5:7b-instruct`。隔离库激活的是 CUSTOM，API 格式为 `CHAT_COMPLETIONS`，Base URL 为 `http://127.0.0.1:11434/v1`，API Key 为 NULL，ActionEligibility 为 `enforced`，规则集为 `none`。未调用 Kilo、OpenCode、OpenRouter、judge 或 Compatibility Probe；没有重试、fallback 或 Provider 切换。

| 变体 / 阶段 | 输入 tokens（请求 / 实际 prefill） | 输出 tokens | Ollama prefill / 生成 | Agent 阶段墙钟 | 8K 截断 | 结果 |
|---|---:|---:|---:|---:|---|---|
| base / STATE_UPDATE | 1,250 / 1,250 | 150 | 6.61 秒 / 2.94 秒 | 20.66 秒 | 否 | Python STATE_UPDATE schema 通过 |
| base / DECISION | 5,117 / 5,110 | 191 | 2.07 秒 / 3.94 秒 | 6.22 秒 | 否 | Python `ModelDecisionOutput` schema 失败 |

停止诊断：`MODEL_OUTPUT_SCHEMA_INVALID`，字段 `observation.known`，期望 `array`、实际 `string`；边界为 `PYTHON_BRAIN`，Java wire validator 和执行器均未进入。`model_selected_action`、ActionEligibility、sourceRefs 合规性和 Payload 均未测得；没有能力调用。provider 请求虽成功，但 Agent 决策契约失败，因此不能计为一次成功决策。

## 下载、运行与环境证据

- Ollama 0.40.6；模型 tag manifest digest：`845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e`。
- 模型 blob：`D:\Ollama\models\blobs\sha256-2bada8a7450677000f678be90653b85d364de7db25eb5ea54136ada5f3933730`，4,683,073,952 bytes；Modelfile `FROM` 与 Ollama GUI Models 设置均指向 D 盘。
- GUI `context_length=8192`；正在运行的 Ollama server 配置 `OLLAMA_CONTEXT_LENGTH=8192`。7B 模型自身最大上下文为 32,768，但本次实际使用 8,192。
- 现存 `qwen3.5:4b`、`qwen3-embedding:0.6b`、`nomic-embed-text:latest` 均仍在模型列表中，4B digest 未变化。
- 开始真实推理前离线门禁共 37 项通过、0 失败：自然语言场景 20，AMBIG 强制门禁 2，数据库隔离守卫 9，停止重跑守卫 6。
- 第一次 Gradle 调用因任务 `UP-TO-DATE` 未运行；核实 Brain 请求计数为零、无报告文件、Ollama 无加载模型后，才用 `--rerun-tasks` 实际执行。前一次未消耗请求预算。
- 正式运行生成的报告标记 `INCOMPLETE`，1/2 变体、2 次请求；在第一个模型契约失败后停止。

## 隔离、Git 与交付

真实 Live 评测只连接新建的一次性数据库 `spec_agent_p3b_live_qwen25_7b_20261020`（端口 5436）。37 项离线回归使用同一个临时 PostgreSQL 容器里的另一个独立库 `spec_agent_p3b_live_qwen25_offline_20261020`。开发库 `spec_agent` 和旧评测库均未触碰。临时 Python Brain、Java Host 和临时 PostgreSQL 容器已在收尾时清理；原 Ollama GUI/server 与所有模型文件保留。开发 Custom 设置和全局 Provider 没有改动。

工作分支为 `dev`。实验开始时 dev SHA 为 `f77bc4644ac66143648333c379dd2958b8fa026b`，main 为 `74b9815d69a562d6782c4efbef1b7daf3f075905`。预先存在的未跟踪 `agent-brain/uv.lock` 保留，未纳入本次证据提交。

本目录包含冻结协议、Harness JSON 报告、attempt sidecar 和经脱敏的模型运行证据。没有保存原始 prompt 或 completion 文本。
