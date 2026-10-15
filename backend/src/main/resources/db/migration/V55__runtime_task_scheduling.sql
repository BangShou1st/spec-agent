-- 多 Worker 执行架构(第一阶段)的公共持久化调度层。
-- 见 docs/v2/PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §5。
--
-- 设计要点:
--   * runtime_tasks 是公共调度事实源,与业务 Run(agent_runs /
--     global_assistant_runs)一一对应但生命周期分离:业务 Run 表达业务进度,
--     调度表表达"谁在什么时候有权执行"。
--   * 执行所有权按任务隔离:每个任务自带 execution_epoch / lease_id /
--     worker_id,不再依赖全库单执行器的 advisory lock。
--   * runtime_scope_owners 表达"同一项目/同一助手会话至多一个活跃执行"。
--   * runtime_capacity_slots 是预建的固定执行槽,认领任务与占用槽位在同一
--     事务内完成;单机线程池配置不再被当成全局限额。

-- ---------------------------------------------------------------------------
-- runtime_tasks: 公共调度表
-- ---------------------------------------------------------------------------
CREATE TABLE runtime_tasks (
    id                 UUID PRIMARY KEY,
    family             VARCHAR(32) NOT NULL
        CHECK (family IN ('WORKSPACE', 'GLOBAL_ASSISTANT')),
    -- 恰好一个非空:任务归属于一个业务 Run,且每个业务 Run 至多一个任务。
    --
    -- ON DELETE CASCADE 是防御性的引用完整性,不是取消机制:产品协议的取消
    -- 必须走显式的任务取消事务(design §4.6、§6.5),绝不允许用"删掉业务行"
    -- 来表达取消。业务行本身在非终态期间已被删除守卫拒绝删除。
    workspace_run_id   UUID REFERENCES agent_runs(id) ON DELETE CASCADE,
    ga_run_id          UUID REFERENCES global_assistant_runs(id) ON DELETE CASCADE,
    task_type          VARCHAR(64) NOT NULL,
    state              VARCHAR(20) NOT NULL
        CHECK (state IN ('QUEUED', 'LEASED', 'RECOVERING',
                         'SUCCEEDED', 'FAILED', 'CANCELLED')),
    -- 每次进程启动生成的新 UUID,绝不用固定主机名冒充执行身份。
    worker_id          UUID,
    -- 首次认领变为 1;每次撤销旧所有权/新认领单调递增。
    execution_epoch    BIGINT NOT NULL DEFAULT 0,
    lease_id           UUID,
    lease_until        TIMESTAMPTZ,
    heartbeat_at       TIMESTAMPTZ,
    attempt_count      INTEGER NOT NULL DEFAULT 0,
    max_attempts       INTEGER NOT NULL DEFAULT 3,
    available_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 首次实际执行时固定的绝对总截止时间;恢复绝不重置它。
    deadline_at        TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    -- 执行输入的引用与版本(不保存凭证)。
    input_ref          TEXT,
    input_version      VARCHAR(64),
    last_error_code    VARCHAR(80),
    recovery_reason    TEXT,
    -- 执行协议版本:不兼容的 Worker 认领失败。
    protocol_version   INTEGER NOT NULL DEFAULT 1,
    CHECK ((workspace_run_id IS NULL) <> (ga_run_id IS NULL)),
    CHECK (execution_epoch >= 0),
    CHECK (attempt_count >= 0),
    CHECK (max_attempts >= 1)
);

CREATE UNIQUE INDEX uq_runtime_tasks_workspace_run
    ON runtime_tasks(workspace_run_id) WHERE workspace_run_id IS NOT NULL;
CREATE UNIQUE INDEX uq_runtime_tasks_ga_run
    ON runtime_tasks(ga_run_id) WHERE ga_run_id IS NOT NULL;

-- 排队索引:只覆盖 QUEUED,顺序为认领顺序。
CREATE INDEX idx_runtime_tasks_queue
    ON runtime_tasks(family, available_at, created_at, id)
    WHERE state = 'QUEUED';
-- 过期 LEASED 扫描索引。
CREATE INDEX idx_runtime_tasks_expired
    ON runtime_tasks(lease_until)
    WHERE state = 'LEASED';
-- 按业务 Run 反查(受保护写入与终态读取)。
CREATE INDEX idx_runtime_tasks_workspace_run ON runtime_tasks(workspace_run_id);
CREATE INDEX idx_runtime_tasks_ga_run ON runtime_tasks(ga_run_id);

-- ---------------------------------------------------------------------------
-- runtime_task_scopes: 任务需要的执行范围。范围只能由 Java 根据命令生成。
-- ---------------------------------------------------------------------------
CREATE TABLE runtime_task_scopes (
    task_id   UUID NOT NULL REFERENCES runtime_tasks(id) ON DELETE CASCADE,
    scope_key VARCHAR(160) NOT NULL,
    PRIMARY KEY (task_id, scope_key)
);
CREATE INDEX idx_runtime_task_scopes_key ON runtime_task_scopes(scope_key, task_id);

-- ---------------------------------------------------------------------------
-- runtime_scope_owners: 范围当前占用者。不独立续租,所有权以所属任务为准。
-- ---------------------------------------------------------------------------
CREATE TABLE runtime_scope_owners (
    scope_key       VARCHAR(160) PRIMARY KEY,
    task_id         UUID NOT NULL REFERENCES runtime_tasks(id) ON DELETE CASCADE,
    execution_epoch BIGINT NOT NULL,
    lease_id        UUID NOT NULL,
    worker_id       UUID NOT NULL,
    acquired_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_runtime_scope_owners_task ON runtime_scope_owners(task_id);

-- ---------------------------------------------------------------------------
-- runtime_capacity_slots: 预建的固定执行槽。
-- 一类容量一行(slot_key),每行容量上限由 capacity 表达;占用落在 slot 行上。
-- ---------------------------------------------------------------------------
CREATE TABLE runtime_capacity_slots (
    slot_key        VARCHAR(80) PRIMARY KEY,
    capacity        INTEGER NOT NULL CHECK (capacity >= 0),
    used            INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (used <= capacity)
);

INSERT INTO runtime_capacity_slots (slot_key, capacity) VALUES
    ('GLOBAL', 4),
    ('WORKSPACE', 2),
    ('GLOBAL_ASSISTANT', 2);

-- 认领顺序固定为 slot_key 升序,避免不同认领之间的锁序反转。
CREATE TABLE runtime_task_capacity (
    task_id  UUID NOT NULL REFERENCES runtime_tasks(id) ON DELETE CASCADE,
    slot_key VARCHAR(80) NOT NULL REFERENCES runtime_capacity_slots(slot_key),
    PRIMARY KEY (task_id, slot_key)
);

-- ---------------------------------------------------------------------------
-- runtime_task_attempts: 每次执行尝试(epoch)的审计记录。
-- ---------------------------------------------------------------------------
CREATE TABLE runtime_task_attempts (
    id              UUID PRIMARY KEY,
    task_id         UUID NOT NULL REFERENCES runtime_tasks(id) ON DELETE CASCADE,
    execution_epoch BIGINT NOT NULL,
    worker_id       UUID NOT NULL,
    lease_id        UUID NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    outcome         VARCHAR(40),
    failure_reason  TEXT,
    UNIQUE (task_id, execution_epoch)
);
CREATE INDEX idx_runtime_task_attempts_task ON runtime_task_attempts(task_id, execution_epoch);

-- ---------------------------------------------------------------------------
-- runtime_queue_quota: 入队容量配额行。
-- 排队上限检查必须有数据库级串行化,不能 COUNT 之后无锁插入(design §9):
-- 入队事务先对本行的对应 family 行取 FOR UPDATE,再计数并插入任务。
-- 这是"叶子锁"——只有入队路径取得它,认领/终态/恢复一律不碰,因此不参与
-- task → 容量槽 → scope → 业务 Run 的锁序,不可能形成等待环。
-- ---------------------------------------------------------------------------
CREATE TABLE runtime_queue_quota (
    family VARCHAR(32) PRIMARY KEY
        CHECK (family IN ('WORKSPACE', 'GLOBAL_ASSISTANT'))
);
INSERT INTO runtime_queue_quota (family) VALUES ('WORKSPACE'), ('GLOBAL_ASSISTANT');

-- ---------------------------------------------------------------------------
-- workspace_run_steps: 画布步骤账本(P1-C 的安全接管依据)。
-- 步骤身份不包含 attempt:接管后同一个逻辑步骤仍然复用同一行。
-- ---------------------------------------------------------------------------
CREATE TABLE workspace_run_steps (
    run_id        UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    step_key      VARCHAR(120) NOT NULL,
    input_hash    CHAR(64),
    state         VARCHAR(20) NOT NULL
        CHECK (state IN ('PREPARED', 'IN_FLIGHT', 'SUCCEEDED', 'UNKNOWN', 'FAILED')),
    origin_epoch  BIGINT NOT NULL DEFAULT 0,
    result_ref    TEXT,
    retry_class   VARCHAR(32) NOT NULL DEFAULT 'SAFE'
        CHECK (retry_class IN ('SAFE', 'UNSAFE')),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, step_key)
);
CREATE INDEX idx_workspace_run_steps_state ON workspace_run_steps(run_id, state);
