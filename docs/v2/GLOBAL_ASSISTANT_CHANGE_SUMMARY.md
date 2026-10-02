# 全局助手与统一 RAG 本轮提交说明

2026-10-02。本项目用于个人学习；本次发布指提交并推送远程仓库，不是生产部署。历史文档中的生产资格、人工验收和旧目录清理要求不作为本次提交门槛；不新增审核流程。

## 完成内容

- 全局助手采用 Python create_agent、middleware 与 LangGraph checkpoint，经 Java broker 使用当前聊天模型的原生工具调用；打通正文流、取消、超时、失败与恢复。
- Java 继续负责业务 Runtime、权限、审批策略、能力执行与副作用幂等。前端按“用户请求 → 本轮处理过程 → 本轮回答”展示真实事件，支持折叠、重连与历史恢复。
- 两类 Agent 共用 Python RAG 与本地 Ollama qwen3-embedding:0.6b，复用 Java pgvector 和受控宿主 RPC；实现结构化分块、引用、混合检索、profile/generation、CAS、来源校验与冻结输入。没有第二套索引或 Python 直连数据库。
- 锁定依赖并提供独立 Windows Python 候选环境准备脚本；52 查询标记为校准开发集，另备 68 条未运行的候选查询和人工审阅材料。

## 已有验证证据

Java 回归 1739 项，0 失败、0 错误、13 跳过；Python 221 项通过；前端 119 项通过、1 跳过，类型检查与构建通过。这些保留原执行日期，未为本次提交重复运行。另有真实模型、HTTPS Git、长摘要、浏览器与重启恢复集成记录。

候选 CPython 3.11.14 使用同一依赖锁，通过 6 次新进程启动、约 65 秒持续服务及 12 次真实 Java RPC/pgvector/Ollama 查询，0 自动重试。首次 Ollama 未启动的健康检查失败单独保留。待审候选数据的拒绝执行测试及脚本语法、静态检查通过。

详细范围见 [实施记录](GLOBAL_ASSISTANT_LANGCHAIN_IMPLEMENTATION_STATUS.md)、[历史测试报告](evidence/GLOBAL_ASSISTANT_RELEASE_VERIFICATION.json)和[收尾证据](evidence/GLOBAL_ASSISTANT_PRE_RELEASE_CLOSEOUT.json)。保留可审阅的 JSON 证据；本地环境、凭据、缓存、浏览器截图与临时构建产物不提交。

## 遗留事项

- 原 CPython 3.11.15 间歇性 0xC0000005 原生崩溃，根因未明；候选有限验证不能证明长期稳定。原环境和证据保留。
- 68 条候选查询尚未人工审阅或执行；不能宣称独立人工效果评测完成，52 条开发集结果也不能替代。
- 旧临时目录 `C:/Users/32962/AppData/Local/Temp/spec-agent-git-skill-13327698071011337690` 未清理。删除曾被自动审批以 `blocked by policy` 拒绝，未绕过。

以上如实保留为学习项目遗留，不阻塞提交。聊天模型与 Java/Python 边界不变，默认引擎未切换，未执行生产迁移或部署。
