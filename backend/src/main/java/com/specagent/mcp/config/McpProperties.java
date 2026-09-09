package com.specagent.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP Runtime limits. Model output can never change these.
 */
@Component
@ConfigurationProperties(prefix = "spec.agent.mcp")
public class McpProperties {

    private int discoveryTimeoutMs = 10_000;
    private int callTimeoutMs = 15_000;
    private int resultMaxInlineBytes = 30_000;
    private int connectionMaxRedirects = 3;
    private int connectionMaxMetadataBytes = 4096;
    private int maxDescriptionChars = 320;
    private boolean allowLocalhostHttp = false;

    public int getDiscoveryTimeoutMs() { return discoveryTimeoutMs; }
    public void setDiscoveryTimeoutMs(int v) { discoveryTimeoutMs = v; }

    public int getCallTimeoutMs() { return callTimeoutMs; }
    public void setCallTimeoutMs(int v) { callTimeoutMs = v; }

    public int getResultMaxInlineBytes() { return resultMaxInlineBytes; }
    public void setResultMaxInlineBytes(int v) { resultMaxInlineBytes = v; }

    public int getConnectionMaxRedirects() { return connectionMaxRedirects; }
    public void setConnectionMaxRedirects(int v) { connectionMaxRedirects = v; }

    public int getConnectionMaxMetadataBytes() { return connectionMaxMetadataBytes; }
    public void setConnectionMaxMetadataBytes(int v) { connectionMaxMetadataBytes = v; }

    public int getMaxDescriptionChars() { return maxDescriptionChars; }
    public void setMaxDescriptionChars(int v) { maxDescriptionChars = v; }

    /** Explicit opt-in for localhost HTTP MCP servers (test/local tooling). */
    public boolean isAllowLocalhostHttp() { return allowLocalhostHttp; }
    public void setAllowLocalhostHttp(boolean v) { allowLocalhostHttp = v; }

    public static McpProperties defaults() {
        return new McpProperties();
    }
}