package com.echo.sdk.feign;

import feign.Client;
import feign.Request;
import feign.Response;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Routes selected requests without creating pools, retrying or consuming responses. */
final class EchoRoutingClient implements Client {
    private final Client original;
    private final Client direct;
    private final EchoRoutingSettings settings;

    EchoRoutingClient(Client original, Client direct, EchoRoutingSettings settings) {
        this.original = original;
        this.direct = direct;
        this.settings = settings;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        URI uri = URI.create(request.url());
        if (!settings.includesRequest(uri)) return original.execute(request, options);
        Map<String, Collection<String>> headers = new LinkedHashMap<>();
        request.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("X-Original-Host") && !name.equalsIgnoreCase("Host")) {
                headers.put(name, values);
            }
        });
        headers.put("X-Original-Host", List.of(uri.getRawAuthority()));
        Request redirected = Request.create(request.httpMethod(), settings.redirectUrl(uri), headers,
                request.body(), request.charset(), request.requestTemplate());
        return direct.execute(redirected, options);
    }
}
