package com.echo.compat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Checks the optional Spring Retry dependency is not needed by the SDK. */
public class WithoutRetryProbe {
    public static void main(String[] args) throws Exception {
        try {
            Class.forName("org.springframework.retry.support.RetryTemplate");
            throw new AssertionError("This probe requires Spring Retry to be absent");
        } catch (ClassNotFoundException expected) {
            // Deliberately absent from the consumer's runtime classpath.
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"message\":\"fixture\",\"count\":7}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var context = new SpringApplicationBuilder(CompatibilityProbe.App.class).run(
                "--probe.echo-url=" + base + "/mock", "--probe.original-url=" + base,
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off", "--logging.level.root=ERROR")) {
            // Inventory has no discovered instance: only successful SDK bypass can reach the fixture.
            if (!context.getBean(CompatibilityProbe.Inventory.class).get().message().equals("fixture")) {
                throw new AssertionError("Selected service could not reach Echo without Spring Retry");
            }
            if (!context.getBean(CompatibilityProbe.Shipping.class).get().message().equals("fixture")) {
                throw new AssertionError("Unselected service lost normal discovery");
            }
            System.out.println("PASS optional_spring_retry_absent_selected_and_unselected_routes");
        } finally {
            server.stop(0);
        }
    }
}
