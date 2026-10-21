package com.specagent.agent.runtime;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/** Blocks the historical destructive V99 before it can erase an existing binding.
 * Already-applied V99 is never replayed or repaired here. Run migrations with workers drained.
 */
@Component
public final class LegacyBindingMigrationGuard implements Callback {
    @Override public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE && context.getMigrationInfo() != null
                && context.getMigrationInfo().getVersion() != null
                && "99".equals(context.getMigrationInfo().getVersion().getVersion());
    }

    @Override public boolean canHandleInTransaction(Event event, Context context) { return true; }

    @Override public void handle(Event event, Context context) {
        if (!supports(event, context)) return;
        try {
            var connection = context.getConnection();
            // The table lock must survive this callback until V99's transaction commits.
            if (connection.getAutoCommit()) {
                throw new FlywayException("V99_BINDING_GUARD_REQUIRES_TRANSACTION");
            }
            try (var statement = connection.createStatement()) {
                statement.execute("LOCK TABLE agent_runs IN SHARE ROW EXCLUSIVE MODE");
                // Preserve all non-null historical values, not just canonical digests.
                // Identical legacy sentinels are the only safe non-null rewrite by V99.
                try (var rows = statement.executeQuery("""
                        SELECT EXISTS (SELECT 1 FROM agent_runs
                          WHERE status NOT IN ('created', 'completed', 'failed')
                            AND model_binding_fingerprint IS NOT NULL
                            AND model_binding_fingerprint <> 'legacy-unbound')
                        """)) {
                    if (!rows.next() || rows.getBoolean(1)) {
                        throw new FlywayException("V99_BINDING_OVERWRITE_BLOCKED: preserve existing bindings; "
                                + "drain workers and review the upgrade before retrying");
                    }
                }
            }
        } catch (SQLException failure) {
            // Driver messages may contain connection/row data; do not echo them.
            throw new FlywayException("V99_BINDING_GUARD_QUERY_FAILED");
        }
    }

    @Override public String getCallbackName() { return "legacy-binding-preservation"; }
}
