# C16 检索代际就绪生产适配器交付

CODE_COMPLETE：代码链路已实现。
OFFLINE_VERIFIED：经审计离线回归通过。
INTEGRATION_NOT_VERIFIED：真实集成未验收。
RELEASE_BLOCKED：不满足发布条件。

## 八项验收回答

1. **已真正连接实际业务任务的代码路径。** POST rebuild 创建的 embedding_rebuilds/generation，
   由已有后台 tick 经真实 Python 检索接口执行，finish/fail 在业务终态事务落持久化完成事实。
   本轮没有实际运行这项外部业务任务，不声称它已经通过生产集成验收。
2. **WAIT 可以订阅真实任务的具体依赖。** 新 capability `retrieval.await_generation` 无参数，
   Host 选择本项目已请求的任务并绑定 generation/retry revision 到 invocation。现有 v4 pending
   投影和 WAIT dependencyRef 指向这个 invocation；仍禁止无依赖、自订阅、跨项目、外部任意 ID。
3. **完成进入现有 Host/outbox/continuation。** 真实终态 outcome → 已有 continuation poller →
   scanner → completeDeferred/终态 CAS → completion port → 订阅 READY/outbox → 原协调器恢复。
   事实与业务状态同事务，重扫可重放，现有截止时间 CAS、outbox 代数和唯一子 Run 裁决幂等。
4. **没有仅靠人工模拟才能完成的生产代码环节。** 不存在 fixture 生产适配器或测试完成控制器。
   管理员请求重建及激活本来就是现有产品操作；适配器不自动发起重建。没有运行中取消接口，
   本轮未伪造；READY 后 discard 不撤销曾完成的 readiness。真实外部服务条件仍待发布验收。
5. **实际离线结果：Java 主套件 206、独立契约套件 110、Python 507 全通过，无跳过/失败。**
   两个 Java 套件有重叠，不相加声称独立用例数。另有 11 个本地契约重放全部匹配、历史证据
   审计 PASS、三个危险任务组合 dry-run 正确拒绝、bootJar 成功。三项终态反例做过失败→通过
   验证，见 review.md 与原始 ZIP。真数据库锁、事务竞争、提交可见性、迁移、跨进程和 E2E
   没有覆盖，全部 NOT RUN。首轮测试编译错误已修复，不计入通过项。
6. **新增 V103，未执行。** 增加 retry revision、真实终态事实表和 invocation 绑定表/索引，
   并回填现有项目终态。存在 DDL/FK/索引/回填锁、存量约束及执行计划风险，旧 writer 不能混跑。
   原 V98–V102 未修改，V99/V100/V101 的真实迁移验收仍 NOT RUN。
7. **还缺隔离环境与额外授权。** 需要获授权的独立 PostgreSQL 目标、经过审计的迁移/集成入口、
   可销毁的用户/组织/项目、已批准 embedding profile、真实 Brain/embedding 服务与安全凭据、
   两个独立 worker 和可控制的崩溃/租约/超时窗口。先排空旧 writer 并统一升级，才能验证真实
   任务完成、撤权/取消、并发订阅/超时、outbox 重投与恢复。未连接既有数据库或调用真实模型。
8. **本地 dev 代码提交：`22b4398f579fafbebc21c79d578558012ac44a25`。**
   21 个目标文件详见 code-files.json，包含 adapter/绑定和恢复仓储、真实 worker/管理接缝、
   V103、Java/Python 回归、离线白名单及设计说明。证据作为后续独立文档提交。
   最终 SHA/文件/状态另见本目录 final-git-state.json（在文档提交后生成，避免自引用）。
   没有推送，main/PR #19 未动。原有 627 路径的内容/删除状态全部保留；原有未跟踪文件与
   本轮原始证据留在本地，不声称工作区 clean。

覆盖矩阵：

| 场景 | 离线依据 | 真集成 |
|---|---|---|
| 真实创建入口、generation 身份、worker READY/FAILED 同事务写事件 | RebuildReadinessOfflineTest | NOT RUN |
| 参数拒绝、项目/组织/持久化委托、撤权、具体 pending 绑定 | RetrievalAwaitGenerationOfflineTest / RebuildWaitRepositoryOfflineTest | NOT RUN |
| generation/retry 旧事件、尚未完成的假终态 | 生产 validateCompletion 三项红→绿反例 | NOT RUN |
| 重复完成、晚于超时、超时读后竞争、暂不可用、重新扫描 | RebuildWaitCompletionRecoveryOfflineTest | NOT RUN |
| 正确/错误 WAIT、完成早于订阅、重复 outbox、恢复结果、Run 失效 | 复用 WaitDependency/WaitLifecycle/Deferred* 离线测试 | NOT RUN |
| 取消/放弃激活 | 真实 discard 路径 + DISCARDED 结果映射；运行中取消接口不存在 | NOT RUN |
| v4 兼容与旧协议拒绝、参数引用/required、C03 | Java 独立/根测试、Python、11 项重放 | NOT RUN（真实模型） |

原始日志、XML、初始保护快照和契约重放见 raw-offline-evidence.zip；摘要与 SHA256 同目录。
实现细节见 [设计](../../v2/C16_RETRIEVAL_READINESS_ADAPTER.md)。
