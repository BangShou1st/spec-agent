-- ============================================================================
-- P2-C 第一步：归属列与存量回填（第二阶段 §4.2、§11）
--
-- 本迁移刻意只做"加列 + 回填 + 核验"，**不**设置 NOT NULL。
-- 设计 §11 规定的迁移顺序是:
--     加列回填 -> 0 行校验 -> NOT NULL + FK
-- 第三步必须与所有写入路径的改造同时落地，否则新建行会立即违反约束。
-- 因此本迁移固定为"第一步"，把最难、最不可逆的数据事实先建立起来，
-- 让后续批次只需改代码而不再移动数据。
--
-- 目标:
--   * projects              : org_id, created_by
--   * global_assistant_threads : org_id, owner_user_id
--   * profiles              : org_id（冻结决定:V2 profiles 属于组织级资源）
--
-- 存量归属规则（§2.5，冻结）:存量项目/会话/配置一律归入一个**显式**的
-- legacy 组织与 legacy 用户，绝不自动分配给第一个登录者。
--
-- 单机兼容（关键）:该 legacy 组织/用户与 LOCAL 模式使用的本地组织/用户
-- 使用**同一组固定标识**。单机部署的存量数据因此天然属于本地组织，
-- 行为与改造前逐字一致；多人部署则由管理员离线审核后把这些对象显式
-- 分配给真实组织与用户。
-- ============================================================================

-- ------------------------------------------------------------------ 1. 加列

ALTER TABLE projects ADD COLUMN IF NOT EXISTS org_id uuid;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS created_by uuid;
ALTER TABLE global_assistant_threads ADD COLUMN IF NOT EXISTS org_id uuid;
ALTER TABLE global_assistant_threads ADD COLUMN IF NOT EXISTS owner_user_id uuid;
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS org_id uuid;

-- ------------------------------------------------------- 2. 引导 legacy 主体

INSERT INTO organizations (id, name, kind)
VALUES ('00000000-0000-4000-8000-000000000001'::uuid, 'Legacy Workspace', 'LEGACY')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_users (id, display_name)
VALUES ('00000000-0000-4000-8000-000000000002'::uuid, 'Legacy Owner')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_members (org_id, user_id, role, status)
VALUES ('00000000-0000-4000-8000-000000000001'::uuid,
        '00000000-0000-4000-8000-000000000002'::uuid, 'OWNER', 'ACTIVE')
ON CONFLICT (org_id, user_id) DO NOTHING;

-- 授权范围行同步建立，使 legacy 范围可被撤权事务加锁与版本化。
INSERT INTO authorization_scopes (scope_key, version)
VALUES ('authz:org:00000000-0000-4000-8000-000000000001', 0),
       ('authz:user:00000000-0000-4000-8000-000000000002', 0)
ON CONFLICT (scope_key) DO NOTHING;

-- ------------------------------------------------------------------ 3. 回填

UPDATE projects
SET org_id = '00000000-0000-4000-8000-000000000001'::uuid,
    created_by = '00000000-0000-4000-8000-000000000002'::uuid
WHERE org_id IS NULL;

UPDATE global_assistant_threads
SET org_id = '00000000-0000-4000-8000-000000000001'::uuid,
    owner_user_id = '00000000-0000-4000-8000-000000000002'::uuid
WHERE org_id IS NULL OR owner_user_id IS NULL;

UPDATE profiles
SET org_id = '00000000-0000-4000-8000-000000000001'::uuid
WHERE org_id IS NULL;

-- -------------------------------------------------------- 4. 核验（0 行）

DO $$
DECLARE
    project_gap integer;
    thread_gap  integer;
    profile_gap integer;
BEGIN
    SELECT COUNT(*) INTO project_gap FROM projects WHERE org_id IS NULL OR created_by IS NULL;
    SELECT COUNT(*) INTO thread_gap
        FROM global_assistant_threads WHERE org_id IS NULL OR owner_user_id IS NULL;
    SELECT COUNT(*) INTO profile_gap FROM profiles WHERE org_id IS NULL;

    IF project_gap > 0 OR thread_gap > 0 OR profile_gap > 0 THEN
        RAISE EXCEPTION
            'P2-C backfill verification failed: projects=%, threads=%, profiles=%',
            project_gap, thread_gap, profile_gap;
    END IF;
END
$$;

-- --------------------------------------------------- 5. 索引与一致性约束

CREATE INDEX IF NOT EXISTS ix_projects_org ON projects (org_id, created_at, id);
CREATE INDEX IF NOT EXISTS ix_ga_threads_org_owner
    ON global_assistant_threads (org_id, owner_user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_profiles_org ON profiles (org_id);

-- profiles 由全局唯一名改为**每组织唯一名**（冻结决定 #2:profiles 是组织级资源）。
-- 两个组织各自拥有同名 profile 是合法且预期的，因此必须先释放全局唯一约束。
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS profiles_name_key;
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS uq_profiles_org_name;
ALTER TABLE profiles ADD CONSTRAINT uq_profiles_org_name UNIQUE (org_id, name);

-- "project.default_profile_id 必须引用同组织 profile"的数据库侧约束。
-- 复合外键只能引用带唯一约束的列集合，因此先为 profiles 建立 (id, org_id) 唯一。
-- default_profile_id 可空：复合外键在任一列为 NULL 时不参与校验，正是所需语义。
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS uq_profiles_id_org;
ALTER TABLE profiles ADD CONSTRAINT uq_profiles_id_org UNIQUE (id, org_id);

ALTER TABLE projects DROP CONSTRAINT IF EXISTS fk_projects_default_profile_same_org;
ALTER TABLE projects ADD CONSTRAINT fk_projects_default_profile_same_org
    FOREIGN KEY (default_profile_id, org_id) REFERENCES profiles (id, org_id);

-- 项目成员必须能推出项目所属组织：复合外键防跨组织授予。
-- projects.org_id 目前可空；复合外键不会因为可空列而失效（有值时即校验）。
ALTER TABLE projects DROP CONSTRAINT IF EXISTS uq_projects_id_org;
ALTER TABLE projects ADD CONSTRAINT uq_projects_id_org UNIQUE (id, org_id);

ALTER TABLE project_members ADD COLUMN IF NOT EXISTS org_id uuid;
UPDATE project_members pm
SET org_id = p.org_id
FROM projects p
WHERE p.id = pm.project_id AND pm.org_id IS NULL;

ALTER TABLE project_members DROP CONSTRAINT IF EXISTS fk_project_members_same_org;
ALTER TABLE project_members ADD CONSTRAINT fk_project_members_same_org
    FOREIGN KEY (project_id, org_id) REFERENCES projects (id, org_id);
