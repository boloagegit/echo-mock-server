package com.echo.sdk.feign;

import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable, validated routing policy; deliberately independent of JSON codecs. */
public final class EchoRoutingSettings {
    private final URI server;
    private final String serverBase;
    private final Set<String> clients;
    private final Set<String> hosts;

    public EchoRoutingSettings(String serverUrl, Collection<String> clients, Collection<String> hosts) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("echo.client.server-url is required when enabled");
        }
        this.server = URI.create(serverUrl);
        if (!isHttp(server) || server.getHost() == null || server.getRawUserInfo() != null
                || server.getRawQuery() != null || server.getRawFragment() != null
                || server.getPort() == 0 || server.getPort() > 65535) {
            throw new IllegalArgumentException("echo.client.server-url must be an absolute HTTP(S) base URL"
                    + " without credentials, query or fragment");
        }
        this.serverBase = serverUrl.replaceAll("/+$", "");
        this.clients = values(clients);
        this.hosts = values(hosts).stream().map(EchoRoutingSettings::validatedHost).collect(Collectors.toUnmodifiableSet());
        if (this.clients.isEmpty() && this.hosts.isEmpty()) {
            throw new IllegalArgumentException("Set echo.client.include-clients or echo.client.include-hosts when enabled");
        }
    }

    private static Set<String> values(Collection<String> values) {
        if (values == null) return Set.of();
        return values.stream().map(value -> {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Echo selectors must not be blank");
            return value.trim();
        }).collect(Collectors.toUnmodifiableSet());
    }

    private static String validatedHost(String value) {
        URI host = URI.create("http://" + value);
        if (host.getHost() == null || host.getRawUserInfo() != null || !host.getRawPath().isEmpty()
                || host.getRawQuery() != null || host.getRawFragment() != null
                || host.getPort() == 0 || host.getPort() > 65535) {
            throw new IllegalArgumentException("echo.client.include-hosts accepts literal hosts with an optional port");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    public boolean includesClient(String contextId) {
        return clients.isEmpty() || clients.contains(contextId);
    }

    public boolean includesRequest(URI original) {
        if (!isHttp(original) || original.getHost() == null || original.getRawUserInfo() != null) return false;
        // Calls already addressed to this Echo instance must not acquire a second /mock prefix.
        if (server.getScheme().equalsIgnoreCase(original.getScheme())
                && server.getRawAuthority().equalsIgnoreCase(original.getRawAuthority())) return false;
        return hosts.isEmpty() || hosts.contains(original.getHost().toLowerCase(Locale.ROOT))
                || hosts.contains(original.getRawAuthority().toLowerCase(Locale.ROOT));
    }

    public String redirectUrl(URI original) {
        String path = original.getRawPath();
        String query = original.getRawQuery();
        return serverBase + (path == null ? "" : path) + (query == null ? "" : "?" + query);
    }

    private static boolean isHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
    }
}
