-- 多 Worker 第一阶段:续跑检查的 worker 归属。
--
-- 设计依据 docs/v2/PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §10:
-- "后台检查使用 SKIP LOCKED + 幂等,避免双重续跑"。
--
-- 改造前 findPending 是普通 SELECT:多 Worker 下两个实例会同时取到同一批待处理
-- 检查并各自评估。既有代数门(markProcessed 只标记被评估的那个代数)能防止
-- "标记了不该标记的行",但 coordinator.continueIfEligible 仍会被重复执行——它靠
-- 幂等键收敛,代价是重复的工作与重复的日志。
--
-- 现在由数据库裁决归属:认领用 FOR UPDATE SKIP LOCKED,并带一个会过期的
-- claim;认领者崩溃后 claim 自然过期,由其他实例接管,因此不会永久搁置。
ALTER TABLE agent_run_continuation_checks ADD COLUMN IF NOT EXISTS claimed_by UUID;
ALTER TABLE agent_run_continuation_checks ADD COLUMN IF NOT EXISTS claim_expires_at TIMESTAMPTZ;

-- 认领候选索引不需要新建:V24 的 idx_continuation_checks_pending
-- (requested_at) WHERE processed_at IS NULL 与认领排序完全一致,复用它即可。
-- 重复创建会因索引同名而迁移失败(此处刻意不写 CREATE INDEX)。

COMMENT ON COLUMN agent_run_continuation_checks.claimed_by IS
    '当前认领该检查的 worker 实例 id(每次进程启动新生成)。NULL 表示无人认领。';
COMMENT ON COLUMN agent_run_continuation_checks.claim_expires_at IS
    '认领的过期时间。认领者崩溃后过期,其他 worker 可以重新认领——'
    '绝不允许一个死掉的 worker 永久搁置一条待处理检查。';
