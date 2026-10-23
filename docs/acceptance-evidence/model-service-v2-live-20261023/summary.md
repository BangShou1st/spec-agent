---
status: Frozen
authority: Current Authority
date: 2026-10-23
---

# Model Service V2-Live 验收摘要

结论：`MODEL_SERVICE_V2_LIVE_PASS`。真实免费 Gemini 模型作为一个普通 Custom 连接完成了 CHAT、STRUCTURED、NATIVE_TOOLS 三项资格验证，并通过项目 Agent 与全局助手的真实链路；同时发现并最小修复了一处真实的确认来源协议缺陷。完整数字见 [verification.json](verification.json)。

## 真实链路

| 层级 | 结果 |
| --- | --- |
| Custom 连接 | 新建 UUID `a95bc5e8-86a0-4daa-b3b6-730b90ba610a`，`CHAT_COMPLETIONS`，模型 `gemini-3.5-flash-lite` |
| CHAT | VERIFIED |
| STRUCTURED | VERIFIED（项目 JSON_OBJECT 决策） |
| NATIVE_TOOLS | VERIFIED（同一无副作用 `probe_echo` 两次往返） |
| 项目 Agent | `completed`，2 次生产模型调用，0 重试，2 节点 / 1 回答 / 1 补丁落库，Run 冻结 `custom-provider:<uuid>:1` 与指纹 |
| 全局助手 | `NATIVE_TOOLS`，2 次模型调用，1 次 `project.get_summary` 工具调用 SUCCEEDED，Checkpoint 恢复为真，最终回答非空 |
| 资格失效 | 改模型 ID 后三项能力全部 UNTESTED，未继承旧资格 |
| 重新验证 | 新模型四项请求后三项全部 VERIFIED |
| 删除隔离 | 当前连接拒绝删除；删除非活动连接不影响当前选择 |

## 发现的真实缺陷与修复

首轮真实项目 Agent 在 STATE_UPDATE 被 Java 拒绝：当前周期的不可变 Answer 已写入数据库且出现在 `allowedSourceRefs` / `retrievedContext`，但尚未进入 `lineage`，而 `CONFIRMED` claim 的来源校验只承认 lineage answer。模型按提示引用该 Answer 时必然失败。

修复：`AgentBrainResponseValidator` 在确认来源中接受“当前路线自己的 `USER_AUTHORED` ANSWER（ROUTE 范围、原路线一致）”。外部证据、其他路线、节点/项目级检索、assumed/unresolved 记忆仍然不能确认。运行时 `AnswerCycleService` 本就把所有 CONFIRMED claim 重新锚定到当前不可变 Answer，因此该修复与既有落库语义一致。新增 5 个确定性用例覆盖放行与拒绝边界。

## 费用与请求

正式验收轮 12 次底层请求（普通聊天 2、结构化 2、原生工具往返 4、项目 Agent 2、全局助手 2），0 自动重试。目录不公布价格，免费额度由 owner 明示声明；API 不暴露实际扣费，本轮未使用付费层。调试阶段另有约 44 次显式请求（每次均为独立运行，不是自动重试），总计约 56 次，误差已如实记录。

## 回归

Java `testOffline` 401/0/0；审查过的 58 类 `test` 集合 459/0/0；Python 341/0/0；前端 987 通过、1 个受 `SPEC_AGENT_GA_LIVE_REPLAY=true` 门禁跳过；类型检查与构建通过。

## 边界

多 Custom 切换与跨实例冻结由既有确定性 L5 用例覆盖，本轮现场只验证资格失效、重验与删除隔离。供应商私有续接材料迁往 Java 仍是独立阶段。项目 Agent 的 token 用量未由内部 broker 暴露。
