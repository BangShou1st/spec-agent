> [!NOTE]
> **Document Status**: `Historical` (历史归档)\
> **Authority**: 本文档为历史阶段性记录，仅供追溯与复盘使用。当前系统的实际行为与规约以 [docs/README.md](../../README.md) 及 [docs/v2/README.md](../../v2/README.md) 为准。

---

# Gemini 免费 API 接入、配额管理与 C03 验证执行报告

**执行日期**：2026-10-20  
**分支**：`dev`（HEAD: `73ce7c2`）  
**执行目标**：安全接入桌面 `key2.txt` 的 Gemini 免费 API，验证模型目录与 Custom API 兼容性，评估配额与选择策略，受控验证 C03 重复追问修复。

---

## 一、核心交付与最终判定结论

| 评测维度 | 判定结果 | 说明 |
|---|---|---|
| **API Key 安全读取** | **成功** | 内存安全读取，无任何泄露，GET `/models` 官方目录返回 200 OK |
| **官方模型目录确认** | **成功** | 确认官方 ID：`gemini-3.8-flash`、`gemini-3.5-flash-lite`、`gemini-3.1-flash-lite`、`gemini-3-flash-preview` |
| **官方 Custom API 配置** | **成功** | 通过官方 `CustomProviderSettingsService` 保存加密配置与修订版本号，未直接 SQL 写表 |
| **Gemini 3.8 Flash 兼容性** | **失败 (超时)** | 2 次 probe 推理请求分别在 30 秒与 90 秒发生 `HttpTimeoutException` |
| **停止条件触发** | **触发停止** | 探测预算达到硬上限（2/2 次），首选模型推理不可达，触发 Fail-Closed 停止规则 |
| **C03 A/B 真实模型实验** | **INCOMPLETE** | 因首选模型未通过兼容性验证，严守不自动 fallback、不静默切换纪律，未启动下游实验 |
| **全局 Provider 状态** | **保持不变** | 开发实例全局 Provider 依然为 `KILO`，未擅自切至 `CUSTOM` |
| **隔离与开发数据库安全** | **安全核实** | 严格保护 `spec_agent` 业务数据库，无数据破坏或非法迁移 |
| **离线测试门禁** | **全部通过** | Python 37 项测试全过，Java P3BC03 自然语言场景测试全部通过 |

---

## 二、模型配置与兼容性探查详细事实

### 1. 接入参数与端点
- **Provider**：`CUSTOM`（内部协议适配器：`CHAT_COMPLETIONS`）
- **Base URL**：`https://generativelanguage.googleapis.com/v1beta/openai`
- **规范端点**：`https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`
- **鉴权方式**：`Authorization: Bearer <MASKED_KEY>`
- **密钥来源**：`$env:USERPROFILE\Desktop\key2.txt`（单行 53 字节有效凭据）
- **凭据保护状态**：经测试快照、Git diff、控制台与日志全程脱敏，数据库存储使用 `ModelCredentialCrypto` 的 `enc:v2:bda587f6:...` AES-GCM 加密。

### 2. 模型目录发现结果
通过官方只读接口 `GET https://generativelanguage.googleapis.com/v1beta/openai/models`（0 推理配额消耗）成功获得 63 个可用模型，用户提供的候选模型与官方真实 ID 映射关系如下：

| 用户配额名称 | 官方真实 Model ID | 每日配额限额 | 响应特性评估 |
|---|---|---:|---|
| **Gemini 3.8 Flash** | `gemini-3.8-flash` | 20 次/天 | 首选质量评测候选，带长链思考（Extended Thinking） |
| **Gemini 3.5 Flash Lite** | `gemini-3.5-flash-lite` | 500 次/天 | 高配额备选，极速轻量，无长思考延迟 |
| **Gemini 3.1 Flash Lite** | `gemini-3.1-flash-lite` | 500 次/天 | 第二备选，极速轻量 |
| **Gemini 3 Flash** | `gemini-3-flash-preview` | 20 次/天 | 额外备选 |

### 3. 首选模型（Gemini 3.8 Flash）兼容性探查过程
官方兼容性探查（`CompatibilityProbeService`）要求模型返回标准的 `{"kind":"FINAL","assistantText":"probe ok"}` 结构并经权威语义校验：

- **第一次探查**（耗时 30,000 ms）：
  - 结果：`java.net.http.HttpTimeoutException`
  - 归因分析：底层 probe 默认超时为 30 秒，而具有深度思考能力的大型推理模型未在 30 秒内完成输出。
- **超时对齐修复**：
  - 将 `CompatibilityProbeService` 中硬编码的 30 秒修改为与系统标准推理超时一致的 `ProviderHttpSupport.INFERENCE_TIMEOUT`（90 秒）。
- **第二次探查**（耗时 90,000 ms）：
  - 结果：再次抛出 `java.net.http.HttpTimeoutException`
  - 事实确认：`gemini-3.8-flash` 在同步 OpenAI 兼容端点下，90 秒内依然未能返回输出。可能原因包括 Google 当前对该前沿模型的思考预算较长、当前免费配额池端点队列积压或暂不支持短超时的同步 Chat Completions。
- **探测预算消耗**：共发出 **2 次** 真实模型请求，消耗预算 **2 / 2**。

---

## 三、停止规则与配额感知策略执行

根据任务书第四阶段与第七阶段的硬性约束：
1. **停止条件**：“遇到首个 429、Provider/连接失败、契约错误、超时或协议规定的其他停止条件时，立即停止实验；不得为了凑齐样本而重试、切换模型或继续其他变体。”
2. **禁止静默切换**：“同一 Run、同一 A/B 实验不得自动切换模型、Provider、凭据或 endpoint。若需要使用另一模型继续，必须结束当前实验，将它作为新的独立运行记录。”
3. **预算控制**：探测预算上限为 2 次，目前已消耗 2 次；全轮总上限 10 次，目前真实请求总数 2 次（失败 2 次，超时 2 次）。未发生违规重试或静默 fallback。
4. **决策结果**：立即停止实验，将首选模型配置标记为超时不可用，如实向用户交付详尽证据，不盲目将剩余 8 次预算消耗在未冻结的模型上。

---

## 四、后续建议与备选模型切换方案

若后续继续进行 C03 真实模型验证，建议采取以下独立冻结策略：
1. **切换至高配额模型 `gemini-3.5-flash-lite`**：
   - 官方 ID：`gemini-3.5-flash-lite`
   - 日配额：500 次/天（足以支持多轮 A/B 重归因）
   - 特性：轻量无深度思考延迟，单次响应通常在 1~3 秒以内，能够完全避免 90 秒超时。
2. **独立运行冻结**：
   - 将 `gemini-3.5-flash-lite` 作为新的独立配置冻结；
   - 重新分配预算（1 次验证 + 8 次 A/B）；
   - 两臂严格使用该冻结模型，保持评估可比性。

---

## 五、回归测试与环境核实

1. **Python 离线回归**：
   - 命令：`uv --directory agent-brain run pytest tools/tests/test_p3b_semantic_ab.py`
   - 结果：`37 passed in 0.04s`（涵盖 C03 全部 5 大分支与确定性规则判定）。
2. **Java 离线回归**：
   - 命令：`./gradlew.bat test --tests *P3BC03NaturalLanguageScenarioTest`
   - 结果：`BUILD SUCCESSFUL in 16s`（18×2 场景语料哈希与确定性离线断言完全通过）。
3. **数据库核验**：
   - 开发数据库 `spec_agent` 的 `model_provider_settings` 中 `active_provider` 确认保持为 `KILO`。
   - 用户原有的未暂存删除（`tools/structured-output-probes/qwen25-7b-schema-20261020/report.md`）与未跟踪文件（`agent-brain/uv.lock`）均完整保留。
