package com.echo.sdk.feign;

import feign.Capability;
import feign.Client;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.RetryableFeignBlockingLoadBalancerClient;
import org.springframework.core.Ordered;

/** Decorates the existing transport before observation capabilities wrap it. */
public final class EchoFeignCapability implements Capability, Ordered {
    private final EchoRoutingSettings settings;
    private final String contextId;

    public EchoFeignCapability(EchoRoutingSettings settings, String contextId) {
        this.settings = settings;
        this.contextId = contextId;
    }

    @Override
    public Client enrich(Client client) {
        if (!settings.includesClient(contextId) || client instanceof EchoRoutingClient) return client;
        Client direct = client;
        // Only selected requests bypass discovery. Unselected requests retain the
        // original load-balancer, retry/lifecycle behavior and transport instance.
        if (direct instanceof FeignBlockingLoadBalancerClient loadBalancer) direct = loadBalancer.getDelegate();
        if (direct instanceof RetryableFeignBlockingLoadBalancerClient loadBalancer) direct = loadBalancer.getDelegate();
        return new EchoRoutingClient(client, direct, settings);
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
