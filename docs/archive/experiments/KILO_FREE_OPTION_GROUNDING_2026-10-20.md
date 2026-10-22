> [!NOTE]
> **Document Status**: `Historical` (历史归档)\
> **Authority**: 本文档为历史阶段性记录，仅供追溯与复盘使用。当前系统的实际行为与规约以 [docs/README.md](../../README.md) 及 [docs/v2/README.md](../../v2/README.md) 为准。

---

# Kilo 免费模型自由输入式澄清真实行为验证报告

日期：2026-10-20  
实验 ID：`kilo-free-option-grounding-20261020`  
场景：`P3B-C09-AMBIG`（版本：2，冻结哈希：`6e2484e0a7547e2c`，变体：`base`）  

---

## 一、验证结论

**单样本真实行为通过**。

在缺少具体检索对象的真实业务场景下，Kilo 免费模型 `cohere/north-mini-code:free` 在真实完整链路（Java Host → Python Brain → Java Internal Model Broker → Kilo Gateway → Python/Java Runtime）中：
1. 正确选择主动作 `REQUEST_USER_INPUT`；
2. 正确聚焦并追问缺失的具体检索对象（`"请问您要查找的具体问题是什么？"`），未重复询问已知的项目范围；
3. 严格遵循上一轮新增的 Prompt 规则 5e，未虚构任何无依据候选项，生成了理想的 `options: []`；
4. 正确设置 `allowFreeAnswer: true` 与 `allowMultiSelect: false`，未阻碍用户输入具体主题；
5. 完整通过 Python Schema、Java 动作契约、ActionEligibility（enforced 模式）及 sourceRefs 确定性审计；
6. 最终执行状态为 `completed`，无任何失败记录。

---

## 二、运行条件与环境核验

| 检查项 | 状态 / 参数 | 核验方式 |
|---|---|---|
| **全局 Provider** | `KILO` | 隔离库激活设置核验 |
| **模型 ID** | `cohere/north-mini-code:free` | 严格冻结，无 fallback |
| **免费属性** | `model_listed_as_free: true` | `FreeModelPolicy.isFreeModelId`（以 `:free` 结尾恒为匿名请求） |
| **Kilo 网关目录** | 包含（共 401 个模型） | 实时只读查询 `https://ai-gateway.kilo.ai/api/v1/models` 验证 |
| **隔离评测数据库** | `spec_agent_p3b_live_kilo_free_20261020` | 符合 `^spec_agent_p3b_live_[a-z0-9_]+$`，未读写开发库 `spec_agent` |
| **ActionEligibility** | `enforced` | 生产运行时强制门禁模式 |
| **请求预算约束** | 上限 2 次（实际消耗 2 次） | `SPEC_AGENT_EVAL_P3B_MAX_TOTAL_REQUESTS=2`，base 完成后跳过 holdout 并停止 |
| **重试与裁判** | 0 重试，0 备用模型，0 裁判调用 | 严格单次执行，无重试链路 |

---

## 三、真实交互链路与模型输出证据

### 1. 模型可见输入事实
- **用户根问题**：“帮我从当前项目的历史讨论中找出与那项具体问题有关的记录。只查当前项目，不需要其他话题。”
- **用户后续回答**：“对，范围就是当前项目。具体是哪项问题，我还没告诉你。”
- **可用能力**：`memory.search`（可见且要求必填参数 `query`；`scope` 已在输入中提供）。
- **自然语言渲染**：由 `ScenarioRunner` 原生自然语言渲染，无占位符，无预置干扰信息。

### 2. 实际请求过程与耗时
- **STATE_UPDATE**：耗时 25,066 ms，返回 `status: SUCCESS`。
- **DECISION**：耗时 27,044 ms，返回 `status: SUCCESS`。
- **总耗时**：57,382 ms。
- **上游真实请求总数**：2 次。

### 3. 模型原始决策 Payload
```json
{
  "action_family": "REQUEST_USER_INPUT",
  "payload": {
    "kind": "INTERACTION",
    "questionText": "请问您要查找的具体问题是什么？",
    "purpose": "明确您要查找的问题，以便检索历史记录。",
    "options": [],
    "allowFreeAnswer": true,
    "allowMultiSelect": false
  },
  "source_ref_count": 3
}
```

---

## 四、核心验收问题回答

1. **实际使用的 Provider 和 Model ID 是什么？**  
   Provider 为 `KILO`，Model ID 为 `cohere/north-mini-code:free`。
2. **是否确认仍为免费模型？**  
   确认。经网关目录与 `FreeModelPolicy` 确认带有 `:free` 后缀，匿名调用，未传递或使用任何 API 密钥。
3. **实际发生几次上游模型请求？**  
   实际发生 **2 次**（STATE_UPDATE 1 次 + DECISION 1 次），严格符合预算。
4. **STATE_UPDATE 是否成功？**  
   成功（`status: SUCCESS`）。
5. **DECISION 是否成功？**  
   成功（`status: SUCCESS`）。
6. **原始模型选择了什么主动作？**  
   `REQUEST_USER_INPUT`。
7. **原始 `questionText` 是什么？**  
   `"请问您要查找的具体问题是什么？"`
8. **原始 `purpose` 是什么？**  
   `"明确您要查找的问题，以便检索历史记录。"`
9. **原始 `options` 包含哪些选项？**  
   `[]`（空列表）。
10. **`allowFreeAnswer` 和 `allowMultiSelect` 分别是什么？**  
    `allowFreeAnswer: true`，`allowMultiSelect: false`。
11. **选项是否有真实用户依据？**  
    是。模型未编造任何未经提及的候选选项（未出现登录、支付、预算、故障等猜测），在无法枚举具体对象时输出空数组。
12. **Python、Java、ActionEligibility、sourceRefs 是否通过？**  
    全部通过。Java 契约校验 `payload_validity: VALID`；门禁校验 `eligibility_outcome: PASSED`；来源引用审计 `source_reference_audit: DETERMINISTIC_VERIFIED`（引用 3 个合法有效 sourceRef，无作废引用）。
13. **最终执行状态是否为 completed？**  
    是。`execution_state: "completed"`，`failure_classification: "NONE"`。
14. **本轮是否证明新的 Prompt 规则在这个真实样本上生效？**  
    是。证明了 Prompt 5e 规则（开放式澄清使用 `options: []` 并允许自由输入，不编造选项）在真实模型链路中生效。
15. **如果未生效，属于模型契约错误、动作选择错误，还是选项语义错误？**  
    本轮已完全生效，未发生契约错误、动作错误或语义错误。
16. **是否值得继续使用当前 Kilo 免费模型进行正常功能开发？**  
    值得。模型在此类涉及意图理解、参数缺失判断与自由输入澄清的核心交互上能够稳定遵循业务规则。
17. **最新 dev SHA、测试结果和报告路径是什么？**  
    - 测试结果：`evalLiveP3B` 执行通过（BUILD SUCCESSFUL in 1m 14s）。
    - 报告证据路径：
      - `tools/p3b/c09-live-runs/kilo-free-option-grounding-20261020/report.json`
      - `tools/p3b/c09-live-runs/kilo-free-option-grounding-20261020/report.json.attempts.jsonl`
    - 文档报告路径：`docs/KILO_FREE_OPTION_GROUNDING_2026-10-20.md`。
