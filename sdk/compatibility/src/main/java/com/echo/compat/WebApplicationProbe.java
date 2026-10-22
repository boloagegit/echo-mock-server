package com.echo.compat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Full servlet AP request -> existing @FeignClient -> Echo/original -> AP JSON response. */
public class WebApplicationProbe {
    @RestController
    public static class Routes {
        private final CompatibilityProbe.Inventory inventory;
        private final CompatibilityProbe.Shipping shipping;
        public Routes(CompatibilityProbe.Inventory inventory, CompatibilityProbe.Shipping shipping) {
            this.inventory = inventory;
            this.shipping = shipping;
        }
        @GetMapping("/compat/web/selected")
        public CompatibilityProbe.Payload selected() { return inventory.get(); }
        @GetMapping("/compat/web/unselected")
        public CompatibilityProbe.Payload unselected() { return shipping.get(); }
    }

    public static void main(String[] args) throws Exception {
        AtomicBoolean correctOriginalHost = new AtomicBoolean();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            boolean mocked = exchange.getRequestURI().getRawPath().startsWith("/mock/");
            if (mocked) correctOriginalHost.set("inventory".equals(exchange.getRequestHeaders().getFirst("X-Original-Host")));
            byte[] body = ("{\"message\":\"" + (mocked ? "echo" : "original") + "\",\"count\":7}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        fixture.start();
        String base = "http://127.0.0.1:" + fixture.getAddress().getPort();
        try (var context = new SpringApplicationBuilder(CompatibilityProbe.App.class).sources(Routes.class).run(
                "--probe.echo-url=" + base + "/mock", "--probe.original-url=" + base,
                "--spring.main.web-application-type=servlet", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.main.banner-mode=off", "--logging.level.root=ERROR")) {
            int port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
            HttpClient http = HttpClient.newHttpClient();
            for (String route : new String[] {"selected", "unselected"}) {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/compat/web/" + route)).header("Accept", "application/json").build(),
                        HttpResponse.BodyHandlers.ofString());
                String expected = route.equals("selected") ? "echo" : "original";
                if (response.statusCode() != 200 || !response.body().contains("\"message\":\"" + expected + "\"")) {
                    throw new AssertionError("Servlet AP " + route + " failed: " + response.statusCode() + " " + response.body());
                }
            }
            if (!correctOriginalHost.get()) throw new AssertionError("Servlet AP did not propagate the logical original host");
            System.out.println("PASS servlet_http_request_to_feign_to_echo_and_original_to_json_response");
        } finally {
            fixture.stop(0);
        }
    }
}
