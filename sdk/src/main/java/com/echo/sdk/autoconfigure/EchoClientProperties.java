package com.echo.sdk.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration shared by Boot 3 and Boot 4; disabled unless explicitly enabled. */
@ConfigurationProperties("echo.client")
public class EchoClientProperties {
    private boolean enabled;
    private String serverUrl;
    private List<String> includeClients = new ArrayList<>();
    private List<String> includeHosts = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Full HTTP mock base URL, including any context path and /mock. */
    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    /** Exact Feign context IDs (the Feign name if contextId is not supplied). */
    public List<String> getIncludeClients() { return includeClients; }
    public void setIncludeClients(List<String> includeClients) { this.includeClients = includeClients; }

    /** Exact hosts, optionally including a port. Both filters apply when supplied. */
    public List<String> getIncludeHosts() { return includeHosts; }
    public void setIncludeHosts(List<String> includeHosts) { this.includeHosts = includeHosts; }
}
