-- ============================================================================
-- P2-B：Spring Session JDBC 共享会话表
--
-- 第二阶段 §6：会话存储使用 PostgreSQL，API 多实例不要求粘性会话。
-- 现阶段不为会话额外引入 Redis；测量证明确有压力后才替换会话存储。
--
-- 本文件是 Spring Session 官方 schema-postgresql.sql 的逐字副本。
-- 之所以由 Flyway 建立而不是让 Boot 自动初始化：本项目的 schema 事实源
-- 只能是 db/migration（validate-on-migrate 开启），自动初始化会让测试库与
-- 生产库出现两套不可审计的 DDL。
--
-- 应用侧配置必须为 spring.session.jdbc.initialize-schema=never，
-- 否则 Boot 会尝试在执行期再次建表。
-- ============================================================================

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
