-- ============================================================================
-- P2-D：runtime_tasks 持久化执行主体（第二阶段 §7、§8）
--
-- 设计要点（docs/v2/PHASE2_IDENTITY_AUTHORIZATION_DESIGN.md §4.2、§7）：
--   * 画布/GA Run 与 runtime_tasks 保存 org_id、actor_user_id、
--     authorization_context_ref；**以持久化引用为准**，不能由 Brain 声称
--     自己属于某用户。
--   * 恢复 Worker 根据数据库重建 ActorContext，不依赖 HTTP 线程上下文。
--   * 每个任务的主体来源（LOCAL/OIDC/SYSTEM）必须落库：判定规则按
--     **主体来源**而非组织 id 展开（见 AuthorizationGuard），因此只存
--     user/org 不足以重建。
--
-- 本迁移只**加列**，不修改任何既有语义。存量行（只可能来自第一阶段
-- 单机任务级调度）回填到显式本地/存量主体：单机部署里"本地主体"就是
-- "存量数据的主人"，这与 V61 的引导行、LocalPrincipalIds 逐字一致。
--
-- 为什么给 org/source/user 都留数据库 DEFAULT：
--   * org_id / actor_source / actor_user_id 三者构成判定所需的最小三元组，
--     缺一即无法重建主体。给它们一个**指向显式本地主体的默认值**，使
--     存量行与第一阶段遗留的直插路径都得到一个真实、可禁用、可审计的
--     主体，而不是"匿名管理员"。
--   * 这只是存量兼容的安全网：OIDC 模式下 OwnershipAttribution 在无主体时
--     **失败关闭**，因此生产写路径绝不可能"忘记写主体→静默变成本地主体"。
-- ============================================================================

ALTER TABLE runtime_tasks ADD COLUMN actor_user_id UUID
    DEFAULT '00000000-0000-4000-8000-000000000002';
ALTER TABLE runtime_tasks ADD COLUMN actor_org_id UUID NOT NULL
    DEFAULT '00000000-0000-4000-8000-000000000001';
ALTER TABLE runtime_tasks ADD COLUMN actor_source VARCHAR(20) NOT NULL
    DEFAULT 'LOCAL';
-- 执行授权引用（唯一的持久化授权上下文引用；绝不存凭证明文或密钥）。
ALTER TABLE runtime_tasks ADD COLUMN authorization_context_ref TEXT;

COMMENT ON COLUMN runtime_tasks.actor_user_id IS
    'P2：任务发起者用户。LOCAL/OIDC 必填；SYSTEM 主体为 NULL（窄服务权限，'
    '无 user 字段不得被当作超级管理员）。';
COMMENT ON COLUMN runtime_tasks.actor_org_id IS
    'P2：任务归属组织。执行期重建 ActorContext 的唯一组织来源，绝不来自模型输出。';
COMMENT ON COLUMN runtime_tasks.actor_source IS
    'P2：主体来源 LOCAL/OIDC/SYSTEM。判定规则按来源展开，因此只存 user/org 不够。';
COMMENT ON COLUMN runtime_tasks.authorization_context_ref IS
    'P2：执行授权引用（不透明引用，非明文凭证）。恢复/续跑据此重建授权上下文。';

-- 存量行回填：把默认本地主体之外的一切遗留行也显式收敛到本地主体。
-- （NOT NULL DEFAULT 已覆盖 org/source；这里补齐 actor_user_id 并留下核验。）
UPDATE runtime_tasks
SET actor_user_id = '00000000-0000-4000-8000-000000000002'
WHERE actor_source = 'LOCAL' AND actor_user_id IS NULL;

-- 主体三元组的一致性约束：用户主体必须携带 userId；系统主体必须不带。
ALTER TABLE runtime_tasks ADD CONSTRAINT ck_runtime_tasks_actor_source
    CHECK (
        (actor_source IN ('LOCAL', 'OIDC') AND actor_user_id IS NOT NULL)
        OR (actor_source = 'SYSTEM' AND actor_user_id IS NULL)
    );

-- 类别核验：任何一行都必须能被重建为某个主体来源，不存在"无来源"的任务。
DO $$
DECLARE
    bad_rows INTEGER;
BEGIN
    SELECT COUNT(*) INTO bad_rows FROM runtime_tasks
    WHERE actor_source NOT IN ('LOCAL', 'OIDC', 'SYSTEM');
    IF bad_rows > 0 THEN
        RAISE EXCEPTION
            'P2-D 迁移失败：% 行 runtime_tasks 的 actor_source 无法重建为主体',
            bad_rows;
    END IF;
END $$;

-- 按组织反查任务（撤权收敛与审计读路径使用）。
CREATE INDEX idx_runtime_tasks_actor_org ON runtime_tasks (actor_org_id, actor_user_id);