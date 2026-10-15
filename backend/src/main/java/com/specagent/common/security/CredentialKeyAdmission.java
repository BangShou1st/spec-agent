package com.specagent.common.security;

import jakarta.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** OIDC instances must explicitly share a primary encryption key; no local generation fallback. */
@Component
@DependsOnDatabaseInitialization
public class CredentialKeyAdmission {
    private final JdbcTemplate jdbc;
    private final String mode,key;
    public CredentialKeyAdmission(JdbcTemplate jdbc,
            @Value("${spec.agent.security.mode:LOCAL}") String mode,
            @Value("${spec.agent.secret.master-key:${SPEC_AGENT_SECRET_MASTER_KEY:}}") String key) {
        this.jdbc=jdbc;this.mode=mode;this.key=key;
    }
    @PostConstruct public void verify() {
        if(!"OIDC".equalsIgnoreCase(mode)) return;
        if(key==null || key.isBlank()) throw new IllegalStateException("OIDC deployment requires an explicit shared master key");
        try {
            byte[] decoded=Base64.getDecoder().decode(key.strip());
            if(decoded.length!=32) throw new IllegalArgumentException("Master key must be 32 bytes");
            String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded));
            jdbc.update("INSERT INTO credential_key_admission(id,primary_key_fingerprint) VALUES(1,?) ON CONFLICT DO NOTHING",fingerprint);
            String registered=jdbc.queryForObject("SELECT primary_key_fingerprint FROM credential_key_admission WHERE id=1",String.class);
            if(!fingerprint.equals(registered)) throw new IllegalStateException(
                    "Credential key differs from the registered fleet key; perform a coordinated key rotation");
        } catch(java.security.GeneralSecurityException ex) {throw new IllegalStateException("Credential key admission failed",ex);}
    }
}
