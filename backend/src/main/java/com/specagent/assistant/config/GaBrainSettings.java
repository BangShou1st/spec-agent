package com.specagent.assistant.config;

import com.specagent.common.BrainConnectionSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Independent GA service origin; shares only the existing host authentication token. */
@Component
public class GaBrainSettings {
    private String baseUrl;
    private final BrainConnectionSettings host;
    public GaBrainSettings(@Value("${spec.global-assistant.brain-base-url:http://127.0.0.1:8101}") String baseUrl,
            BrainConnectionSettings host) { this.baseUrl=baseUrl; this.host=host; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl=value; }
    public String getInternalSecret() { return host.getInternalSecret(); }
    public int getConnectTimeoutMs() { return 2000; }
    public BrainConnectionSettings connection() {
        return new BrainConnectionSettings() {
            public String getBaseUrl() { return GaBrainSettings.this.getBaseUrl(); }
            public String getInternalSecret() { return GaBrainSettings.this.getInternalSecret(); }
        };
    }
}
