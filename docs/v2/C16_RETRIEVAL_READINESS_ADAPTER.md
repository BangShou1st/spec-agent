# C16 首个生产适配器：检索重建代际就绪

状态（2026-10-25 事实校正后）：CODE_COMPLETE / OFFLINE_VERIFIED / DATABASE_MIGRATION_VERIFIED / REAL_WORKER_VERIFIED / CROSS_PROCESS_RECOVERY_VERIFIED / E2E_VERIFIED —— 隔离环境验收于 2026-10-21 完成，见 `docs/acceptance-evidence/c16-real-integration-20261021/delivery.md`；生产部署验收仍未执行。
基线 dev `8fb328b9ddfe1a00e5f56e90eb1a250b9fd80872`。不改变 WAIT v4 协议。
（首版状态行为 CODE_COMPLETE / OFFLINE_VERIFIED / INTEGRATION_NOT_VERIFIED / RELEASE_BLOCKED，基于当时仅有的离线证据；已被同日更晚的隔离环境真实集成证据取代，历史原文见文末与离线证据目录，不覆盖。）

## 真实任务与接入范围

`EmbeddingSettingsController.rebuild` → `EmbeddingRebuilds.start` 已经创建真实的
`embedding_rebuilds` 行；其 id 就是 `RetrievalIndexJobs.prepareGeneration` 创建的
`retrieval_index_generations.id`。项目 corpus 等于 project_id，组织归属受现有复合 FK 约束。
HELP 安装级 corpus 没有 project_id，不属于本适配器范围。

`EmbeddingRebuilds.tick` 由已有后台调度器执行，在 RebuildLeaseContext 下经
SharedRetrievalHost/PythonRetrievalClient 调用真实分片/向量工作，再由
RebuildLeaseCoordinator.finish/fail 提交状态。每批重新认领会改变 execution_epoch；
它是租约 fencing，不是业务重试代次。READY 表示构建就绪，不表示索引已激活。
activate 仍必须走原管理入口，适配器不会启动重建、调用模型或自动激活。

## 持久化与身份

V103 是当前序列 V102 后的下一版本，原 V98–V102 不改。

- `embedding_rebuilds.readiness_revision`：从 0 开始，仅显式 FAILED→QUEUED retry 加一。
- `embedding_rebuild_outcomes`：主键 `(generation_id, readiness_revision)`，存储项目、组织及
  首次终态。真实 worker 的 READY/FAILED 更新与此事实写入同一业务事务。
  recoverExpired/claim 的永久失效失败也经过同一个 failRow。
- `capability_rebuild_waits`：invocation → generation/revision，不从模型接受外部 ID。
  随 invocation/rebuild 删除级联，扫描时间仅用于公平轮转，不是业务事件身份。

`retrieval.await_generation` v1 为无参数只读能力。Host 在当前项目和持久化 Run 用户委托
范围内，锁定已经请求的 QUEUED/RUNNING/READY 重建。无任务返回 REBUILD_NOT_FOUND；
READY 同步成功，其余先持久化绑定再注册现有 PENDING。重放不重新选择任务。
绑定 INSERT SELECT 再次核对真实调用/任务、project、org、revision 和允许状态。

任务所有者是组织/项目，不声称存在未持久化的创建者用户。观察者身份来自已有
workspace_run_authorizations，必须仍具 PROJECT_WRITE；不授予创建重建所需的
CREDENTIAL_MANAGE/PROJECT_MANAGE。完成、投影及重放重新校验该委托与当前权限，
共享授权锁在调用行 CAS 前取得。原 WAIT 订阅者也继续通过自己的项目/Run 权限检查。

## 完成与恢复

真实任务终态事务提交 → 持久化 outcome → 现有 RunWorker continuation 恢复轮询 →
RebuildWaitCompletionRecovery → CapabilityRuntime.completeDeferred →
CapabilityInvocationRepository.completePending → 现有 completion port →
WAIT 订阅 READY + continuation outbox → 既有协调器/唯一子 Run 恢复。

没有新事件总线、执行器、调度器或生产完成 HTTP 回调。提交后的数据库事实就是可靠事件；
轮询消费不依赖 afterCommit 内存通知。注册前已完成会同步成功，注册与完成之间不丢失事实。
调用原 owner 使用已有自动停靠；同项目/route/anchor 的另一个决策可通过 v4 pending 投影
订阅具体 invocation。禁止自订阅、无依赖或跨项目 WAIT 的原有规则不变。

扫描每次轮转 bounded 候选，每项独立事务；暂时无法完成不会删除事实。失效或撤权项不能
阻塞后续候选。重启后重扫同一绑定；每次完成都重读已提交的同 generation/revision 事实，
并校验完整结果身份，拒绝编造终态、旧 generation 或旧 revision 结果。
最终唯一裁决仍是现有 PENDING/截止时间 CAS；只有获胜者写既有订阅/outbox。
超时、重复事件、晚到完成、重复 outbox 均沿用已有门禁和唯一 continuation 子 Run。
撤权不泄露结果，之后由既有超时/续跑撤权处理收敛；未知授权范围保持可重试失败。

READY/ACTIVE → SUCCEEDED；FAILED/DISCARDED 终态事实 → FAILED，结果只带安全状态和
generation/revision 溯源，不带凭据、原文或 worker 错误详情。业务没有运行中取消重建的
接口，本轮不伪造 CANCELLED 状态。现有 discard 只允许 READY，所以已经提交的首次
READY 不因随后放弃激活而倒退；新调用不会选择 DISCARDED。迁移前已 DISCARDED 的历史
任务按失败事实保留。Run 取消仍由现有 Run/WAIT 所有权和 continuation 资格检查处理。

## 发布门槛与验收状态（2026-10-25 事实校正更新）

原首版本节标题为「尚未验收的发布门槛」，其中 V103 未执行、V99/V100/V101 迁移
NOT RUN、跨进程恢复与 E2E NOT RUN 等断言基于当时仅有的离线证据，已被同日更晚的
隔离环境真实集成验收取代：见
`docs/acceptance-evidence/c16-real-integration-20261021/delivery.md`。该轮在隔离
PostgreSQL（`spec_agent_p3b_live_iso20261021` 专用容器/账户/网络）上完成 V98–V103
真实迁移链（含 V99 守卫的安全阻断与人工处置）、真实 Python 检索进程 + 真实本地
Ollama 的 worker 执行、七项失败路径、七项跨进程竞争/崩溃/恢复场景与 E2E 全链路，
并暴露并修复两个 P0 生产缺陷（完成扫描列名错误、续跑线程缺 actor 绑定）。

仍然有效的发布门槛（未被上述证据取消）：

- 禁止新旧业务 writer 混跑：旧版本 retry 不递增 revision，旧 worker 不写 outcome。
  上线必须先排空/停止旧 rebuild writer，在获授权的发布流程执行迁移并统一升级后恢复；
  不宣称支持滚动混跑。
- 隔离环境验收不等于生产部署：生产切换仍需获授权的目标数据库、凭据注入、已批准
  embedding profile、可用的真实 Brain/embedding 服务与两独立 worker 进程，以及
  RUNBOOK 列出的运维门槛。

原首版记录（历史，NOT RUN 断言已被上段取代，按原文保留）：V103 只编写并静态核对，
没有执行。真实 V99/V100/V101 迁移验收仍 NOT RUN，不能由本轮离线测试替代。运行
真实任务完成、事务竞争、撤权、崩溃窗口、outbox 重投、跨进程恢复与 E2E 验收均
NOT RUN。本轮未连接数据库、调用模型或启动完整 Spring 集成环境。

离线证据见 `docs/acceptance-evidence/c16-production-adapter-20261021/delivery.md`。
