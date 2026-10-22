package com.echo.compat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Fixed-URL Feign must not require the optional Spring Cloud LoadBalancer module. */
public class WithoutLoadBalancerProbe {
    public static void main(String[] args) throws Exception {
        try {
            Class.forName("org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory");
            throw new AssertionError("This probe requires Spring Cloud LoadBalancer to be absent");
        } catch (ClassNotFoundException expected) {
            // Keep Spring Cloud Commons and OpenFeign, remove only the optional LB module.
        }
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/mock/", exchange -> {
            byte[] body = "{\"message\":\"echo\",\"count\":7}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        fixture.start();
        try (var context = new SpringApplicationBuilder(CompatibilityProbe.App.class).run(
                "--probe.echo-url=http://127.0.0.1:" + fixture.getAddress().getPort() + "/mock",
                "--probe.original-url=http://unresolved-downstream.invalid",
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")) {
            if (!context.getBean(CompatibilityProbe.Orders.class).get().getBody().message().equals("echo")) {
                throw new AssertionError("Fixed-URL Feign could not reach Echo without the optional LB module");
            }
            System.out.println("PASS fixed_url_feign_without_spring_cloud_loadbalancer");
        } finally {
            fixture.stop(0);
        }
    }
}
