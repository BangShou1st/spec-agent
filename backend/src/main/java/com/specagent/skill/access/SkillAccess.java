package com.specagent.skill.access;

import com.specagent.access.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/** Skill installations are organization configuration, not a global writable catalog. */
@Component
public class SkillAccess {
    private final AuthorizationGuard guard;
    private final NamedParameterJdbcTemplate jdbc;
    private final com.specagent.runtime.execution.WorkspaceRunExecutionAccessPort executions;

    public SkillAccess(AuthorizationGuard guard, NamedParameterJdbcTemplate jdbc,
            com.specagent.runtime.execution.WorkspaceRunExecutionAccessPort executions) {
        this.guard = guard;
        this.jdbc = jdbc;
        this.executions = executions;
    }

    public UUID org() {
        return actor().orgId();
    }

    public ActorContext actor() {
        ActorContext actor = guard.ownershipActor();
        if (!actor.isUser()) {
            throw AccessDeniedException.forbidden(AccessReason.SERVICE_NOT_AUTHORIZED,
                    "Skill access requires user delegation");
        }
        guard.requireSelectedOrg(actor, null);
        return actor;
    }

    public void execution(UUID project, UUID run) { executions.requireExecution(project,run); }

    public UUID manage() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Skill mutations require a transaction");
        }
        ActorContext actor = guard.ownershipActor();
        UUID org = org();
        guard.lockSharedFailClosed(List.of(AuthorizationScopes.org(org),
                AuthorizationScopes.user(actor.userId())));
        guard.requireSelectedOrg(actor, Capability.ORG_MANAGE);
        // Configuration writes are short. Serialize installs/enable/name checks within
        // one org, including the empty catalog case; never hold this across git/network IO.
        var sql = jdbc.getJdbcTemplate();
        String previous = sql.queryForObject("SELECT current_setting('lock_timeout')", String.class);
        Long existingMillis = sql.queryForObject(
                "SELECT setting::bigint FROM pg_settings WHERE name='lock_timeout'", Long.class);
        long timeout = existingMillis != null && existingMillis > 0
                ? Math.min(existingMillis, 3000L) : 3000L;
        try {
            sql.queryForObject("SELECT set_config('lock_timeout',?,true)", String.class, timeout + "ms");
            sql.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 8137926501))::text",
                    String.class, "skill-configuration:" + org);
            sql.queryForObject("SELECT set_config('lock_timeout',?,true)", String.class, previous);
        } catch (org.springframework.dao.DataAccessException ex) {
            // A failed PostgreSQL lock statement aborts the transaction. Do not try
            // to restore settings in that transaction or expose SQL/driver details.
            throw new IllegalStateException("SKILL_CONFIGURATION_UNAVAILABLE");
        }
        return org;
    }

    /** Authorize before remote import; recheck with locks at the later commit. */
    public void requireManagement() {
        org();
        guard.requireSelectedOrg(guard.ownershipActor(), Capability.ORG_MANAGE);
    }

    public void project(UUID projectId, Capability capability) {
        guard.requireProjectFor(guard.ownershipActor(), projectId, capability);
    }
}
