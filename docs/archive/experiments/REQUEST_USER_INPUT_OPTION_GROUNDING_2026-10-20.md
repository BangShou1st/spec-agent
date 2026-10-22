> [!NOTE]
> **Document Status**: `Historical` (历史归档)\
> **Authority**: 本文档为历史阶段性记录，仅供追溯与复盘使用。当前系统的实际行为与规约以 [docs/README.md](../../README.md) 及 [docs/v2/README.md](../../v2/README.md) 为准。

---

# Agent 澄清选项依据与自由输入回归报告

日期：2026-10-20

## 结论

`options=[] + allowFreeAnswer=true + allowMultiSelect=false` 已被现有产品链完整支持。问题不在数据契约或节点运行时：原 DECISION Prompt 没有明确要求候选项必须有上下文依据，也没有明确允许开放式澄清使用空选项；模型因此把猜测出的项目主题包装成候选项。

本轮只增加一条通用 Prompt 约束和一条前端占位提示。Java 校验、动作资格、sourceRefs、数据库结构、Answer/Graph/Route 语义均未放宽或更改。

## 阶段 A：实际交互链路

| 层 | 实际行为与证据 |
|---|---|
| Python Brain | `ModelDecisionAction.payload` 是严格顶层模型中的 `Dict[str, Any]`。DECISION 解析会校验动作族、未知顶层字段、来源引用和 eligibility，但不限制 payload 内的 `options` 数量。新增 scripted DECISION 回归通过 `handle_decision` 接受并原样返回空数组组合。 |
| Java 契约 | `AgentBrainResponseValidator.validateRequestUserInput` 要求 `options` 是数组，并逐项验证选项对象和非空 label；空数组不会进入循环，因此通过。`allowFreeAnswer` 必须是 Boolean；本轮测试显式携带 `allowMultiSelect=false`。来源引用仍使用原合法 fixture，未修改 sourceRefs 校验。 |
| ActionExecutor / 图变更 | `ProposalActionExecutor.parseOptions` 对空数组产生空列表，并把两个回答标记交给 `AgentGraphMutationService.InteractionNode`；事务内进入 `NodeService` 创建 INTERACTION/QUESTION 节点。未改变执行器或授权门。 |
| 节点与数据库 | `Node` 将 options 复制为不可变列表；`NodeRepository` 把列表序列化为 JSONB 并从 JSONB 读取。节点创建与读取集成测试确认 options 仍为空、自由回答为 true、多选为 false。迁移和查询中未发现 options 至少一个元素的约束。 |
| API | `NodeResponse.from` 将 options 映射为数组。MockMvc 图 API 集成测试确认 Agent 创建的空选项 Question 返回 `options: []`、`allowFreeAnswer: true`、`allowMultiSelect: false`。 |
| 前端显示与提交 | `GraphQuestionNode.vue` 按 `allowFreeAnswer` 显示 textarea；无选项时，自由文本是启用提交按钮的唯一输入。组件提交 `{selectedOptionId:null, selectedOptionIds:null, freeText:...}`。本轮把无选项时的占位提示改为“请输入回答”，有选项时仍显示“补充说明（可选）”。 |
| Answer Cycle | `WorkspaceView` 将组件事件交给 workspace store；store 以 `ANSWER_TIP` 创建 run，并提交自由文本。`AnswerCycleRunController` 的 POST 命令承载 `CreateRunRequest.freeText`；`RunWorker` 将文本交给 `AnswerCycleService.submitAnswer`。服务接受非空自由文本、拒绝空回答，并在 `allowFreeAnswer=false` 时拒绝自由文本。本轮集成测试从 Agent 创建纯自由输入 Question，提交回答后确认 run 完成且 Answer 保存原文。 |

没有发现其他层要求 `options` 至少有一个元素。Java 契约要求字段存在且为数组，不要求数组非空；数据库 JSONB、API 映射、前端渲染和 Answer Cycle 都支持空数组。

## 阶段 B/C：Prompt 与离线回归

生产 DECISION Prompt 原先要求拆分问题、生成并推荐选项，却没有给选项增加依据约束。现已做最小修改：

- 5b 仅在候选答案有依据时才把多项决定整理成 options。
- 新增 5e：options 只能来自当前用户输入或可信上下文；无法可靠枚举开放式澄清答案时用 `options: []` 并允许自由输入；不得为凑选项或推荐而猜测主题；存在依据时保留有意义的选项。

这是一条通用规则，不提 `memory.search`、C09 或固定措辞。没有修改 Python Schema、Java 校验、ActionEligibility，也没有新增模型调用。

新增离线回归覆盖：

1. Python `handle_decision` 接受无选项澄清 payload，并原样保留 `allowFreeAnswer`、`allowMultiSelect`。
2. Prompt 测试锁定 grounded options、空 options 和保留有据选项的规则。
3. Java 响应校验接受空 options，同时保持原合法 sourceRefs。
4. ProposalActionExecutor 创建并持久化空选项 Question。
5. Graph API 返回空数组和正确回答标记。
6. Agent 创建空选项 Question 后，用户自由文本通过既有 Answer Cycle 落库并完成 run。
7. 前端空选项节点显示文本框、在输入前禁用提交、输入后发出自由文本 payload；WorkspaceView 与 store 测试覆盖 `ANSWER_TIP` 提交。

## 安全与兼容性

- `ActionEligibility` 和动作授权路径未修改；原 sourceRefs、快照、运行时身份与选项 label 校验保持 fail-closed。
- 不生成空白问题节点；`NodeService` 仍要求 question 非空。
- Answer、Graph、Route 和历史记录的生产逻辑未修改；完整后端 `testNonLive` 回归通过。
- 有据选项仍可通过执行器创建和 API 返回；前端既有单选、推荐标记、多选和自由文本组合测试保持通过。多选仍按节点的 `allowMultiSelect` 决定。
- 确定性 scripted payload 只能证明契约和交互链接受正确形态；不能证明真实模型今后每次都会按 Prompt 选择该形态。

## 验证结果

所有测试使用 Fake、scripted payload、测试数据库或本地 UI 单测。`backend/src/test/resources/application-test.yml` 指向 `spec_agent_test`；全量重跑还显式固定了该 JDBC URL。未连接开发数据库 `spec_agent`。本轮临时启动了原先已停止的 `spec-agent-postgres` 测试容器，测试结束后已停止容器。

| 验证 | 结果 |
|---|---:|
| Python Brain 定向 `test_decision_prompt.py` + `test_engines.py` | 31 passed |
| `agent-brain/tests` + `tools/tests` | 420 passed |
| Java 响应契约、ProposalActionExecutor、AnswerCycle、Graph API 定向测试 | 38 passed |
| `evalBFast` | 162 passed |
| `testNonLive` | 2229 tests，0 failures，0 errors，36 skipped，352 suites |
| 前端 `GraphQuestionNode`、`WorkspaceView`、`workspaceStore` | 142 passed |
| 前端 `vue-tsc --noEmit` | passed |
| `git diff --check` | passed |

第一次 `testNonLive` 尝试给整个 JVM 设置了 `SPRING_PROFILES_ACTIVE=test`，触发 `FakeGaNativeTransportTest` 的 profile 隔离断言。之后移除了全局 profile，同时显式固定测试库 JDBC URL 并关闭实时测试开关；最终全量重跑通过。第一次 Java 集成测试因测试 PostgreSQL 容器当时处于停止状态而无法连接；启动隔离测试容器后，38 项定向测试和完整 `testNonLive` 均通过。

## 未覆盖边界与下一步

- 未进行真实模型推理、Live A/B、Compatibility Probe 或浏览器 E2E；没有能力据此预测模型遵循新 Prompt 的概率。
- 本轮真实模型请求数严格为 **0**：Kilo 0、Ollama 0、其他云端 Provider 0、裁判模型 0。
- 建议下一轮在用户单独授权后，用当前 Kilo 模型对同一个缺少具体主题的合成问题做 **1 次**低预算验证；不需要裁判模型。若输出仍编造候选，再根据该次具体响应判断是否需要进一步调整 Prompt。

## Git 状态与文件

开始时分支为 `dev`，HEAD 为 `87a14e9e86b1806caa3addc42be5884d4b1871e1`；`origin/dev` 为 `7c1d825239ec2bacd541284a31770b8f519d68f3`，`main` 为 `74b9815d69a562d6782c4efbef1b7daf3f075905`。开始时已有的 `tools/structured-output-probes/qwen25-7b-schema-20261020/report.md` 删除状态和未跟踪的 `agent-brain/uv.lock` 均未纳入本轮提交，也未被恢复、清理或覆盖。

本轮修改文件：

- `agent-brain/src/spec_agent_brain/prompts/decision.py`
- `agent-brain/tests/test_decision_prompt.py`
- `agent-brain/tests/test_engines.py`
- `backend/src/test/java/com/specagent/agent/action/ProposalActionExecutorTest.java`
- `backend/src/test/java/com/specagent/agent/decision/AgentBrainResponseValidatorTest.java`
- `backend/src/test/java/com/specagent/agent/runtime/AnswerCycleIntegrationTest.java`
- `backend/src/test/java/com/specagent/workspace/graph/GraphWorkspaceApiIntegrationTest.java`
- `frontend/src/features/workspace/graph/components/GraphQuestionNode.vue`
- `frontend/src/features/workspace/graph/components/__tests__/GraphQuestionNode.spec.ts`
- `docs/REQUEST_USER_INPUT_OPTION_GROUNDING_2026-10-20.md`

本轮实现和报告将提交到 `dev` 并推送 `origin/dev`。最终提交 SHA 由 Git 提交后状态确认。
