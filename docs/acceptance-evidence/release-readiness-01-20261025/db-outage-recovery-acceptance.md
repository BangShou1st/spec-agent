---
status: Runbook
authority: Current Authority
---

# A4 — DB 中断恢复手工脚本与发布前验收清单(RELEASE-READINESS-01)

- 日期:2026-10-25。本轮**未新增自动化代码、未新增测试**;本文整理既有手工资产与预期判定标准,
  并将其列为生产发布前人工验收项。

## 1. 既有手工资产

| 资产 | 位置 | 用途 |
|---|---|---|
| DB 中断演练脚本 | `tools/p2_database_outage_acceptance.py` | OIDC GA 执行期间真实崩溃/重启隔离 PostgreSQL 容器,验证 fail-closed 与恢复 |
| Worker 崩溃脚本 | `tools/p2_kill_worker_acceptance.py` | 杀死验收 worker 进程,验证接管与恢复 |
| 密钥轮换脚本 | `tools/p2_key_rotation_acceptance.py`、`tools/P2CredentialRotationProbe.java` | 凭据轮换/恢复演练 |
| 双进程验收 | `tools/p1e_dual_process_acceptance.py` | 双进程竞争与租约 |
| 驱动说明 | `docs/v2/PHASE2_IMPLEMENTATION_STATUS.md`(L989 附近) | 脚本驱动方式与已知失败记录 |

## 2. DB 中断演练的预期判定标准(脚本内建,fail-closed)

脚本场景:MODEL RESERVED 执行期间重启隔离 PostgreSQL → 全链预期:

1. 中断期间两个 worker 的健康端点必须 503(`healthDuringOutage`),不得伪装可用;
2. 中断时刻在途 run 必须 FAILED(`GA_EXECUTION_FAILED`),MODEL 预留不虚报成功;
3. 迟到返回不得复活终态 run(`afterLateReturn`);
4. worker 重启后 admission 恢复,新 fixture 的 run 完整走完(RUN_STARTED→…→RUN_COMPLETED,
   taskState=SUCCEEDED,`freshRun`);
5. 全部满足才置 `passed: true`。

脚本自保护前置(任何一条不满足即拒绝运行):仅限 `backend/build/p2-acceptance` 下的隔离工件、
专用库(`spec_agent_p2_workers_*`)、已排空非终态任务、无 Gradle Test JVM 存活、无密钥轮换
进程并存、worker 进程与已核实 jar 绑定、OIDC fixture + bsk 已认证浏览器会话。

## 3. 既有一次性执行证据(历史代码版本)

`docs/v2/evidence/P2_RESUMED_DATABASE_OUTAGE.json`:

- 库 `spec_agent_p2_workers_20261015a`,jar SHA-256 `cbcc5f1b…8846`,**passed=true**;
- 完整覆盖第 2 节 5 项判据(中断 run FAILED、双 worker 503、迟到返回、fresh run COMPLETED);
- **时效限制**:该证据对应 2026-10-15 的代码与 jar,不包含其后合入 dev 的 V104–V110 迁移、
  GA continuation 修复与 arch-quality 系列变更。它证明「当时的中断语义正确」,不能证明
  「当前验收 SHA 上同样正确」。

## 4. 本轮状态与发布前验收项

- 本轮(RELEASE-READINESS-01)**未复验**该演练:执行需要 OIDC fixture、双 worker 进程与
  bsk 认证浏览器的完整隔离环境,且任务约定本轮不新增自动化代码。当前验收 SHA 上的
  DB 中断恢复演练维持**未验收( NOT RUN)**状态。
- **列为生产发布前人工验收项**:投产前由 owner 按 `docs/v2/PHASE2_IMPLEMENTATION_STATUS.md`
  的驱动说明,在隔离环境对最终发布 jar 复跑一轮 `tools/p2_database_outage_acceptance.py`,
  归档新的 evidence JSON(passed=true)后方可关闭本项。
- 相关既有自动化覆盖(引用,不重跑):AnswerResume / AgentRunRecovery / OrphanRecovery /
  MultiWorkerScheduling / ExecutorLeaseLoss 集成测试已覆盖进程级恢复语义;本演练补足的是
  「真实数据库进程消失」维度,两者不可互相替代。
