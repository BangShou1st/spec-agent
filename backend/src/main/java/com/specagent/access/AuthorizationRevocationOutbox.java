package com.specagent.access;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Written in the membership transaction; consumers acquire execution locks only after commit. */
@Repository
public class AuthorizationRevocationOutbox {
    public record Event(UUID id,UUID userId,UUID orgId) {}
    private final JdbcTemplate jdbc;
    public AuthorizationRevocationOutbox(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public void append(UUID userId,UUID orgId) {
        jdbc.update("INSERT INTO authorization_revocation_outbox(id,actor_user_id,org_id) VALUES(?,?,?)",
                UUID.randomUUID(),userId,orgId);
    }
    public List<Event> pending() {
        return jdbc.query("SELECT * FROM authorization_revocation_outbox WHERE processed_at IS NULL ORDER BY created_at,id LIMIT 50",
                (rs,i)->new Event(rs.getObject("id",UUID.class),rs.getObject("actor_user_id",UUID.class),rs.getObject("org_id",UUID.class)));
    }
    public void complete(UUID id) {
        jdbc.update("UPDATE authorization_revocation_outbox SET processed_at=now() WHERE id=? AND processed_at IS NULL",id);
    }
}
