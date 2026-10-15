package com.specagent.access.security;

import com.specagent.access.BrowserSessionValidity;
import com.specagent.access.IdentityModeProperties;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Read the shared session store, including expiry; no local logout notification is needed. */
@Component
public class SharedBrowserSessionValidity implements BrowserSessionValidity {
    private final NamedParameterJdbcTemplate jdbc;
    private final IdentityModeProperties properties;
    public SharedBrowserSessionValidity(NamedParameterJdbcTemplate jdbc,IdentityModeProperties properties) {
        this.jdbc=jdbc;this.properties=properties;
    }
    public boolean active(String sessionId) {
        if(sessionId==null) return false;
        long now=System.currentTimeMillis();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM spring_session s WHERE s.session_id=:id
                    AND s.expiry_time>:now AND s.creation_time>:earliest
                    AND EXISTS(SELECT 1 FROM spring_session_attributes a
                        WHERE a.session_primary_id=s.primary_id AND a.attribute_name='SPRING_SECURITY_CONTEXT'))
                """,Map.of("id",sessionId,"now",now,
                            "earliest",now-properties.getAbsoluteTimeout().toMillis()),Boolean.class));
    }
}
