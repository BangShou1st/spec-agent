-- 多 Worker 第一阶段 P1-D:全局助手接入公共调度层。
--
-- 设计依据 docs/v2/PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §5.1、§6.1、§7:
--   * 全局助手当前依赖 dispatch 参数在内存里传递 message 与 UI context;
--     必须持久化后再应答,否则进程崩溃就丢失执行输入。
--   * GA 的执行代次不能再硬编码为 1:接管必须能表达"这是第 N 次执行"。
--   * GA 的恢复分两步走:先做持久化排队 + 多会话并行 + 按租约诚实终态化,
--     再开放经过验证的 checkpoint 恢复。本迁移只提供前一步所需的事实。

-- 执行输入的持久化载荷(不含凭证:UI context 只有页面名与被校验过的项目 id)。
-- 与 input_ref/input_version 互补:后者是引用与版本,本列是实际需要的输入内容。
ALTER TABLE runtime_tasks ADD COLUMN input_payload jsonb;

-- GA 执行记录允许代次 > 1(接管递增),并把代次与租约的来源交给 runtime_tasks。
-- 原约束只要求 execution_epoch > 0,本注释确认多 attempt 语义:
--   * 首次认领 epoch = 1;
--   * 每次接管在 runtime_tasks 内单调递增,ga_executions 行按新代次更新。
COMMENT ON COLUMN ga_executions.execution_epoch IS
    '执行代次。首次认领为 1;每次任务接管由 runtime_tasks 单调递增后写入,'
    '使旧执行器的迟到写入被 lease_id + epoch 双重拒绝。';

-- 按 attempt 记录 GA 内部的执行认领与事件去重(design §7 第二步的前置事实)。
-- internal_sequence 现在是 (run_id, execution_epoch) 内唯一,而不是全 run 唯一:
-- 旧 attempt 的内部流序号不得与新 attempt 冲突,否则接管后第一次写入就会撞唯一键。
ALTER TABLE ga_execution_events DROP CONSTRAINT ga_execution_events_run_id_internal_sequence_key;
ALTER TABLE ga_execution_events ADD COLUMN execution_epoch BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ga_execution_events
    ADD CONSTRAINT ga_execution_events_attempt_sequence_key
    UNIQUE (run_id, execution_epoch, internal_sequence);

COMMENT ON COLUMN ga_execution_events.execution_epoch IS
    '产生该内部事件的执行代次。按 attempt 分区内部序号:旧 attempt 的 delta、'
    '终态与 checkpoint 一律拒绝,接管后的新 attempt 从自己的序号空间重新开始。';
