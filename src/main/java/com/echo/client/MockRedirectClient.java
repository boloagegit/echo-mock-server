package com.echo.client;

import feign.Client;
import feign.Request;
import feign.Response;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/**
 * Feign Client interceptor for redirecting requests to Echo Mock Server.
 * 
 * This class is intended to be used in Client AP projects (not Echo itself).
 * It intercepts outgoing requests and redirects them to the Echo Mock Server,
 * while preserving the original host in the X-Original-Host header.
 * 
 * Usage in Client AP:
 * <pre>
 * &#64;Bean
 * public Client feignClient() {
 *     return new MockRedirectClient(
 *         new Client.Default(null, null),
 *         "http://localhost:8080"
 *     );
 * }
 * </pre>
 */
public class MockRedirectClient implements Client {

    private static final String ORIGINAL_HOST_HEADER = "X-Original-Host";

    private final Client delegate;
    private final String echoServerUrl;

    /**
     * Create a new MockRedirectClient.
     *
     * @param delegate       The underlying Feign client to delegate requests to
     * @param echoServerUrl  The URL of the Echo Mock Server (e.g., "http://localhost:8080/mock")
     */
    public MockRedirectClient(Client delegate, String echoServerUrl) {
        this.delegate = delegate;
        this.echoServerUrl = echoServerUrl.endsWith("/") 
                ? echoServerUrl.substring(0, echoServerUrl.length() - 1) 
                : echoServerUrl;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        // Parse original URL
        URI originalUri = URI.create(request.url());
        String originalHost = originalUri.getHost();
        
        // Extract port if present
        if (originalUri.getPort() != -1) {
            originalHost = originalHost + ":" + originalUri.getPort();
        }

        // Build new URL pointing to Echo Server
        String newUrl = buildRedirectUrl(originalUri);

        // Add X-Original-Host header
        Map<String, Collection<String>> newHeaders = new LinkedHashMap<>(request.headers());
        newHeaders.put(ORIGINAL_HOST_HEADER, Collections.singletonList(originalHost));

        // Create new request with modified URL and headers
        Request redirectedRequest = Request.create(
                request.httpMethod(),
                newUrl,
                newHeaders,
                request.body(),
                request.charset(),
                request.requestTemplate()
        );

        // Execute via delegate
        return delegate.execute(redirectedRequest, options);
    }

    /**
     * Build the redirect URL by replacing the scheme and authority with Echo Server URL.
     *
     * @param originalUri The original request URI
     * @return The new URL pointing to Echo Server
     */
    private String buildRedirectUrl(URI originalUri) {
        StringBuilder sb = new StringBuilder(echoServerUrl);
        
        // Append path
        if (originalUri.getRawPath() != null) {
            sb.append(originalUri.getRawPath());
        }
        
        // Append query string if present
        if (originalUri.getRawQuery() != null) {
            sb.append("?").append(originalUri.getRawQuery());
        }
        
        // Append fragment if present
        if (originalUri.getRawFragment() != null) {
            sb.append("#").append(originalUri.getRawFragment());
        }
        
        return sb.toString();
    }

    /**
     * Get the configured Echo Server URL.
     *
     * @return The Echo Server URL
     */
    public String getEchoServerUrl() {
        return echoServerUrl;
    }
}
