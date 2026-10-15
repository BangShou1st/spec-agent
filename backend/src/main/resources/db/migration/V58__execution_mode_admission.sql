-- 数据库范围的模式准入协议(design §11"禁止新旧调度器混跑")。
--
-- 本地配置 spec.agent.execution.multi-worker-enabled 是"每实例"的选择,
-- 它本身无法阻止两个实例带着不同模式连到同一个数据库。本表把"这个数据库
-- 被准入给哪一种调度模式"变成数据库内的持久事实:
--
-- - 任务级(PER_TASK)实例启动时在此登记;发现已登记为 SINGLE_EXECUTOR 时
--   拒绝启动(反之亦然)。模式切换必须走维护窗口:停止全部实例、显式改写
--   本行、再启动新舰队。
-- - 旧二进制兼容边界:旧单执行器实例不认识本表,无法被协议本身拦截;但它
--   启动时必须取得 executor 键的会话级排他 advisory lock
--   (ExecutorLease.LOCK_KEY),而任务级实例(含 API-only)持有同一键的
--   共享 advisory lock——共享与排他互斥,因此旧执行器既进不来、也起不来。
--   旧 API-only 实例(不取任何锁)是剩余边界,由发布流程的维护窗口覆盖。
--
-- 单行表:只有 id=1 一行。 admission 组件原子地"无则插入、有则校验"。
CREATE TABLE runtime_execution_mode (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    mode VARCHAR(32) NOT NULL CHECK (mode IN ('SINGLE_EXECUTOR', 'PER_TASK')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(255)
);

INSERT INTO runtime_execution_mode (id, mode, updated_by)
VALUES (1, 'SINGLE_EXECUTOR', 'V58 migration baseline');
