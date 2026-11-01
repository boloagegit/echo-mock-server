package com.echo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides where {@code X-Original-Host} forwarding may go.
 *
 * <p>{@link Mode#LEGACY} is the default and keeps the historical behavior: any host,
 * redirects followed. The restricted modes are opt-in for deployments that do not want
 * callers to choose arbitrary outbound targets.</p>
 */
@Component
@Slf4j
public final class OriginalHostPolicy {

    /** Forwarding modes for {@code X-Original-Host}. */
    public enum Mode {
        /** Any host is accepted and redirects are followed (historical behavior). */
        LEGACY,
        /** Only configured {@code host[:port]} entries are accepted; redirects are not followed. */
        ALLOWLIST,
        /** {@code X-Original-Host} forwarding is rejected. */
        DISABLED
    }

    private static final int HTTPS_PORT = 443;
    // host or [ipv6], optionally followed by :port. Nothing else (no path, userinfo or scheme).
    private static final Pattern AUTHORITY = Pattern.compile(
            "^(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(?::(\\d{1,5}))?$");

    private final Mode mode;
    private final Set<String> allowedHosts;

    @Autowired
    public OriginalHostPolicy(
            @Value("${echo.http.original-host.mode:LEGACY}") String mode,
            @Value("${echo.http.original-host.allowed-hosts:}") String allowedHosts) {
        this(parseMode(mode), parseAllowedHosts(allowedHosts));
        logPolicy();
    }

    OriginalHostPolicy(Mode mode, Set<String> allowedHosts) {
        this.mode = mode;
        this.allowedHosts = Set.copyOf(allowedHosts);
    }

    /** Historical behavior, used when no policy bean is wired (tests, manual construction). */
    public static OriginalHostPolicy legacy() {
        return new OriginalHostPolicy(Mode.LEGACY, Set.of());
    }

    public Mode mode() {
        return mode;
    }

    /** Redirects are followed only in {@link Mode#LEGACY}. */
    public boolean followRedirects() {
        return mode == Mode.LEGACY;
    }

    /**
     * Returns {@code null} when {@code targetHost} may be forwarded to, otherwise the
     * rejection reason.
     */
    public String rejectionReason(String targetHost) {
        return switch (mode) {
            case LEGACY -> null;
            case DISABLED -> "X-Original-Host forwarding is disabled";
            case ALLOWLIST -> {
                String normalized = normalize(targetHost);
                yield normalized != null && allowedHosts.contains(normalized)
                        ? null : "X-Original-Host target is not in the allowed host list";
            }
        };
    }

    /**
     * Normalizes {@code host[:port]} to lower-case {@code host:port}, with HTTPS port 443
     * when no port is given. Returns {@code null} for anything that is not a bare authority.
     */
    static String normalize(String value) {
        if (value == null) return null;
        Matcher matcher = AUTHORITY.matcher(value.trim());
        if (!matcher.matches()) return null;
        int port = HTTPS_PORT;
        if (matcher.group(2) != null) {
            port = Integer.parseInt(matcher.group(2));
            if (port < 1 || port > 65535) return null;
        }
        return matcher.group(1).toLowerCase(Locale.ROOT) + ":" + port;
    }

    static Mode parseMode(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return Mode.LEGACY;
        try {
            return Mode.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("echo.http.original-host.mode must be one of "
                    + Arrays.toString(Mode.values()) + ", but was: " + value, e);
        }
    }

    static Set<String> parseAllowedHosts(String value) {
        Set<String> hosts = new LinkedHashSet<>();
        if (value == null) return hosts;
        for (String entry : value.split(",")) {
            if (entry.isBlank()) continue;
            String normalized = normalize(entry);
            if (normalized == null) {
                throw new IllegalArgumentException("echo.http.original-host.allowed-hosts entries must be "
                        + "host or host:port (no scheme or path), but got: " + entry.trim());
            }
            hosts.add(normalized);
        }
        return hosts;
    }

    private void logPolicy() {
        switch (mode) {
            case LEGACY -> log.warn("X-Original-Host forwarding accepts any target host and follows redirects "
                    + "(echo.http.original-host.mode=LEGACY). Set ALLOWLIST or DISABLED if callers are not trusted.");
            case ALLOWLIST -> {
                if (allowedHosts.isEmpty()) {
                    log.warn("X-Original-Host mode is ALLOWLIST but echo.http.original-host.allowed-hosts is empty; "
                            + "every X-Original-Host request will be rejected");
                } else {
                    log.info("X-Original-Host forwarding restricted to {} host(s) without redirects: {}",
                            allowedHosts.size(), allowedHosts);
                }
            }
            case DISABLED -> log.info("X-Original-Host forwarding is disabled");
        }
    }
}
