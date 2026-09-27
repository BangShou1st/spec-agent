-- 执行器所有权代次(所有权校验、业务写入/检查点与执行器接管的统一原子协议)。
--
-- executor_ownership 是全库单行的所有权代次记录:每个执行器在 advisory
-- lock 之下获取(并递增)一个单调代次;接管必然意味着新代次。
-- agent_runs.owner_epoch 记录认领该 run 的执行器代次;run 行的一切状态
-- 写入(检查点/终态/认领)都以单条条件 UPDATE 同时验证:
--   1. 全局代次仍等于本执行器的代次(接管一旦提交,旧执行器的后续写入
--      立即落空,无论其线程在检查后暂停了多久);
--   2. run 行的认领代次不晚于本执行器代次;
--   3. run 尚未终态(终态不可覆盖)。
-- 检查与写入在同一条语句内完成,因此不存在"检查通过后暂停任意时间"
-- 仍能写入的窗口;业务产物事务把带所有权条件的检查点写入放在同一事务,
-- 条件不满足即整体回滚。
ALTER TABLE agent_runs ADD COLUMN owner_epoch BIGINT NOT NULL DEFAULT 0;

CREATE TABLE executor_ownership (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    epoch BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO executor_ownership (id, epoch) VALUES (1, 0);
