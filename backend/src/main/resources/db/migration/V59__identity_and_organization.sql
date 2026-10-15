-- ============================================================================
-- P2-B：身份、组织、成员与授权范围（第二阶段 §4.1）
--
-- 本迁移只建立身份/授权基础事实，不修改任何既有业务表的语义：
-- 业务表加 org_id / owner 列属于 P2-C 与 P2-E，与登录能力解耦。
--
-- 设计约束（与 docs/v2/PHASE2_IDENTITY_AUTHORIZATION_DESIGN.md §4.1 对齐）：
--   * 不以邮箱作为身份主键；身份由 (issuer, subject) 唯一确定。
--   * 禁止用客户端传入的 orgId 自动创建组织。
--   * 项目成员必须能推出项目所属组织（跨组织授予在 P2-C 用复合外键收紧；
--     本批先建立表与索引，组织一致性由应用层强制）。
--   * 审计事件不含正文与密钥。
-- ============================================================================

CREATE TABLE app_users (
    id           UUID PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

COMMENT ON TABLE app_users IS
    'P2：身份主体。禁用用户必须在每次授权检查生效（status 参与判定，不靠会话缓存）。';

CREATE TABLE user_identities (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    issuer     VARCHAR(500) NOT NULL,
    subject    VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_identities_issuer_subject UNIQUE (issuer, subject)
);

COMMENT ON TABLE user_identities IS
    'P2：外部身份到本地用户的映射。唯一键是 (issuer, subject)，绝不使用邮箱作为主键。';

CREATE INDEX ix_user_identities_user ON user_identities (user_id);

CREATE TABLE organizations (
    id         UUID PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    kind       VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_organizations_kind CHECK (kind IN ('STANDARD', 'LEGACY', 'LOCAL')),
    CONSTRAINT ck_organizations_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

COMMENT ON TABLE organizations IS
    'P2：组织。kind=LOCAL 是本地单机模式的显式组织，kind=LEGACY 承载存量数据；'
    '两者都不是"匿名管理员"，而是有明确归属的普通组织。';

CREATE TABLE organization_members (
    org_id     UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    role       VARCHAR(20) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_organization_members PRIMARY KEY (org_id, user_id),
    CONSTRAINT ck_organization_members_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    CONSTRAINT ck_organization_members_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

COMMENT ON TABLE organization_members IS
    'P2：组织成员资格。最后一个 OWNER 不允许退出或降级（由服务层强制）。';

CREATE INDEX ix_organization_members_user ON organization_members (user_id);

-- 项目成员。project_id 指向既有 projects 表（V2）。
-- 组织一致性（成员必须属于项目所在组织）在 P2-C 增加 projects.org_id 后
-- 以复合外键 (project_id, org_id) -> projects(id, org_id) 收紧；本批由
-- 应用层在授予时校验，且授予行为写入 security_audit_events。
CREATE TABLE project_members (
    project_id UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    role       VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_project_members PRIMARY KEY (project_id, user_id),
    CONSTRAINT ck_project_members_role CHECK (role IN ('MANAGER', 'EDITOR', 'VIEWER'))
);

COMMENT ON TABLE project_members IS
    'P2：项目角色。角色只映射到确定性能力集合（PROJECT_READ/WRITE/MANAGE），'
    '"能聊天"绝不等价于"能执行所有工具"。';

CREATE INDEX ix_project_members_user ON project_members (user_id);

-- 授权范围行：撤权与提交的串行化点（第二阶段 §8）。
-- 锁顺序中位于 execution scope 与业务 Run 之间：
--   task -> 容量槽 -> execution scope -> authorization scope -> 业务 Run -> 业务行
CREATE TABLE authorization_scopes (
    scope_key  VARCHAR(200) PRIMARY KEY,
    version    BIGINT       NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_authorization_scopes_key_prefix
        CHECK (scope_key LIKE 'authz:%')
);

COMMENT ON TABLE authorization_scopes IS
    'P2：授权事务锁与版本来源。键族 authz:org:|authz:project:|authz:thread:|'
    'authz:credential:|authz:user: —— 与 P1 的 runtime_task_scopes 分表，'
    '避免与执行范围语义混淆。撤权事务只锁本表并写 outbox，绝不反向取 task 锁。';

CREATE TABLE security_audit_events (
    id            BIGSERIAL PRIMARY KEY,
    actor_user_id UUID,
    org_id        UUID,
    action        VARCHAR(100) NOT NULL,
    resource_type VARCHAR(60)  NOT NULL,
    resource_id   VARCHAR(100),
    outcome       VARCHAR(20)  NOT NULL,
    reason_code   VARCHAR(80),
    correlation_id VARCHAR(100),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_security_audit_outcome CHECK (outcome IN ('ALLOW', 'DENY', 'ERROR'))
);

COMMENT ON TABLE security_audit_events IS
    'P2：安全审计。绝不记录业务正文、凭证明文或密钥。';

CREATE INDEX ix_security_audit_events_org_created
    ON security_audit_events (org_id, created_at DESC);
CREATE INDEX ix_security_audit_events_actor_created
    ON security_audit_events (actor_user_id, created_at DESC);

-- 身份模式与安全协议版本的数据库登记（与 P1 的 runtime_execution_mode 同构）。
-- 同库实例必须一致；缺配置不得自动回退，见 security_mode 准入协议。
CREATE TABLE security_mode (
    id               SMALLINT     PRIMARY KEY DEFAULT 1,
    mode             VARCHAR(20)  NOT NULL,
    protocol_version INTEGER      NOT NULL DEFAULT 1,
    issuer_uri       VARCHAR(500),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_security_mode_single_row CHECK (id = 1),
    CONSTRAINT ck_security_mode_value CHECK (mode IN ('LOCAL', 'OIDC'))
);

COMMENT ON TABLE security_mode IS
    'P2：数据库范围的身份模式登记。单机默认 LOCAL；OIDC 需显式改写该行'
    '（停全部实例 -> UPDATE -> 启动新舰队），防止实例间模式分歧。';

INSERT INTO security_mode (id, mode, protocol_version)
VALUES (1, 'LOCAL', 1);
