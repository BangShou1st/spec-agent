package com.specagent.assistant.config;

import com.specagent.modelsettings.ModelCredentialCrypto;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DB authority after any explicit save/clear; environment fallback is installation compatibility only. */
@Service
public class SearchSettings {
    public record Snapshot(boolean enabled,String key,long revision,String source) {
        public boolean configured() { return enabled && key!=null && !key.isBlank(); }
        @Override public String toString() { return "SearchSnapshot[revision="+revision+", source="+source+"]"; }
    }
    private final JdbcTemplate jdbc;
    private final ModelCredentialCrypto crypto;
    private final String environmentKey;
    private final com.specagent.access.OrganizationConfigurationScope scope;
    public SearchSettings(JdbcTemplate jdbc,ModelCredentialCrypto crypto,
            @Value("${spec.global-assistant.tavily-api-key:}") String environmentKey,
            com.specagent.access.OrganizationConfigurationScope scope) {
        this.jdbc=jdbc; this.crypto=crypto; this.environmentKey=environmentKey==null?"":environmentKey.strip();
        this.scope=scope;
    }
    public Snapshot snapshot() {
        UUID org=scope.readOrg();
        var rows=jdbc.queryForList("SELECT * FROM search_settings WHERE singleton_id=1 AND org_id=?",org);
        if(rows.isEmpty()) return scope.installationDefaultsAllowed()
                ? new Snapshot(!environmentKey.isBlank(),environmentKey,0,"ENVIRONMENT")
                : new Snapshot(false,null,0,"DATABASE");
        var r=rows.getFirst(); return new Snapshot((Boolean)r.get("enabled"),crypto.decryptScoped((String)r.get("api_key"),org,"search_settings",1),((Number)r.get("revision")).longValue(),(String)r.get("source"));
    }
    public Map<String,Object> view() {
        var s=snapshot(); var m=new LinkedHashMap<String,Object>();
        boolean credential=s.key()!=null && !s.key().isBlank();
        m.put("enabled",s.enabled()); m.put("configured",credential); m.put("maskedKey",credential?"••••"+s.key().substring(Math.max(0,s.key().length()-4)):null);
        m.put("revision",s.revision()); m.put("source",s.source()); m.put("environmentAvailable",scope.installationDefaultsAllowed() && !environmentKey.isBlank());
        var rows=jdbc.queryForList("SELECT test_code,tested_at,tested_revision FROM search_settings WHERE singleton_id=1 AND org_id=?",scope.readOrg());
        m.put("testCode",rows.isEmpty()?null:rows.getFirst().get("test_code")); m.put("testedAt",rows.isEmpty()?null:rows.getFirst().get("tested_at"));
        return m;
    }
    public static String validateKey(String value) {
        if(value==null) return null;
        String key=value.strip();
        if(key.isEmpty() || key.length()>4096 || key.contains("••") || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("INVALID_CREDENTIAL");
        return key;
    }
    @Transactional public void requireManagement() { scope.writeOrg(); }
    @Transactional public Map<String,Object> save(boolean enabled,String key,boolean importEnvironment,Long expectedRevision) {
        UUID org=scope.writeOrg();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","search-settings:"+org);
        if(importEnvironment && !scope.installationDefaultsAllowed())
            throw new IllegalArgumentException("ENVIRONMENT_IMPORT_DISABLED");
        var previous=snapshot();
        if(expectedRevision!=null && expectedRevision!=previous.revision()) throw new IllegalStateException("SETTINGS_CHANGED");
        String next=importEnvironment?validateKey(environmentKey):key==null?previous.key():validateKey(key);
        jdbc.update("""
            INSERT INTO search_settings(org_id,singleton_id,enabled,api_key,revision,source) VALUES(?,1,?,?,?,'DATABASE')
            ON CONFLICT(org_id,singleton_id) DO UPDATE SET enabled=EXCLUDED.enabled,api_key=EXCLUDED.api_key,
            revision=EXCLUDED.revision,source='DATABASE',test_code=NULL,tested_at=NULL,tested_revision=NULL
            """,org,enabled,crypto.encryptScoped(next,org,"search_settings",1),previous.revision()+1);
        return view();
    }
    @Transactional public Map<String,Object> clear(Long revision) {
        UUID org=scope.writeOrg();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","search-settings:"+org);
        var s=snapshot(); if(revision!=null && revision!=s.revision()) throw new IllegalStateException("SETTINGS_CHANGED");
        jdbc.update("""
            INSERT INTO search_settings(org_id,singleton_id,enabled,api_key,revision,source) VALUES(?,1,FALSE,NULL,?,'DATABASE')
            ON CONFLICT(org_id,singleton_id) DO UPDATE SET enabled=FALSE,api_key=NULL,revision=EXCLUDED.revision,
            test_code=NULL,tested_revision=NULL,tested_at=NULL
            """,org,s.revision()+1);
        return view();
    }
    @Transactional public void recordTest(long revision,String code) {
        UUID org=scope.writeOrg();
        jdbc.update("UPDATE search_settings SET test_code=?,tested_revision=?,tested_at=now() WHERE singleton_id=1 AND revision=? AND org_id=?",code,revision,revision,org);
    }
}
