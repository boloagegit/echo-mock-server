package com.echo.compat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import feign.Capability;
import feign.Client;
import feign.Request;
import feign.RequestInterceptor;
import feign.Response;
import feign.codec.ErrorDecoder;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.RetryableFeignBlockingLoadBalancerClient;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** A real AP: Boot auto-configuration + @FeignClient + network I/O, no SDK test doubles. */
public class CompatibilityProbe {
    private static final byte[] BINARY = new byte[] {0, 1, 13, 10, (byte) 0x80, (byte) 0xff};
    private static final AtomicReference<Request.Options> OPTIONS = new AtomicReference<>();
    private static final AtomicInteger OBSERVATIONS = new AtomicInteger();
    private static final AtomicInteger AUDIT_CALLS = new AtomicInteger();
    private static final AtomicInteger CUSTOM_TRANSPORT_CALLS = new AtomicInteger();
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final String realEcho;
    private final Fixture original;
    private final Fixture echo;

    public record Payload(String message, int count) { }

    @FeignClient(name = "orders", url = "${probe.original-url}", configuration = OrdersConfiguration.class)
    public interface Orders {
        @GetMapping("/compat/get") ResponseEntity<Payload> get();
        @PostMapping(value = "/compat/json", consumes = "application/json") Payload post(@RequestBody Payload payload);
        @GetMapping("/compat/path/{id}") Response encoded(@PathVariable("id") String id, @RequestParam("q") String query);
        @GetMapping("/compat/bytes") byte[] bytes();
        @GetMapping("/compat/cookies") Response cookies();
        @GetMapping("/compat/status") Payload fail();
        @GetMapping("/compat/slow") String slow();
        @GetMapping("/compat/redirect") Response redirect();
        @GetMapping("/compat/trailing/") Response trailingSlash();
    }

    public interface ReadClient { @GetMapping("/compat/get") Payload get(); }

    @FeignClient(name = "ledger", contextId = "ledgerPrimary", url = "${probe.original-url}")
    public interface LedgerPrimary extends ReadClient { }

    @FeignClient(name = "ledger", contextId = "ledgerSecondary", url = "${probe.original-url}")
    public interface LedgerSecondary extends ReadClient { }

    @FeignClient(name = "inventory", path = "/tenant")
    public interface Inventory extends ReadClient { }

    @FeignClient(name = "shipping")
    public interface Shipping extends ReadClient { }

    @FeignClient(name = "isolated", url = "${probe.original-url}", configuration = IsolatedConfiguration.class)
    public interface Isolated extends ReadClient { }

    @FeignClient(name = "resilient", url = "${probe.original-url}", fallback = ApplicationFallback.class)
    public interface Resilient extends ReadClient { @GetMapping("/compat/status") Payload fail(); }

    public static class ApplicationFallback implements Resilient {
        @Override public Payload get() { return new Payload("app-fallback", 1); }
        @Override public Payload fail() { return new Payload("app-fallback", 1); }
    }

    public static class IsolatedConfiguration {
        @Bean FeignClientConfigurer isolatedConfiguration() {
            return new FeignClientConfigurer() { @Override public boolean inheritParentConfiguration() { return false; } };
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableFeignClients(clients = {Orders.class, LedgerPrimary.class, LedgerSecondary.class, Inventory.class,
            Shipping.class, Isolated.class, Resilient.class})
    public static class App {
        @Bean
        RequestInterceptor companyAuthorization() {
            return template -> {
                template.header("Authorization", "Bearer compatibility-probe");
                template.header("x-original-host", "spoofed-one", "spoofed-two");
            };
        }

        @Bean
        AuditingCapability auditingCapability() { return new AuditingCapability(); }

        @Bean ApplicationFallback applicationFallback() { return new ApplicationFallback(); }

        // Own the AP's CircuitBreaker executor so repeated context tests also
        // verify orderly shutdown instead of waiting for a cached pool's idle timeout.
        @Bean(destroyMethod = "shutdownNow")
        ExecutorService circuitBreakerExecutor() { return Executors.newCachedThreadPool(); }

        @Bean
        Customizer<Resilience4JCircuitBreakerFactory> circuitBreakerExecutorCustomizer(ExecutorService circuitBreakerExecutor) {
            return factory -> factory.configureExecutorService(circuitBreakerExecutor);
        }

        @Bean
        ObservationRegistry observationRegistry() {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
                @Override public boolean supportsContext(Observation.Context context) { return true; }
                @Override public void onStart(Observation.Context context) { OBSERVATIONS.incrementAndGet(); }
            });
            return registry;
        }
    }

    public static class OrdersConfiguration {
        @Bean
        RequestInterceptor orderHeader() { return template -> template.header("X-Order-Config", "kept"); }
        @Bean
        ErrorDecoder companyErrorDecoder() { return (method, response) -> new CompanyError(response.status()); }
    }

    public static final class CompanyError extends RuntimeException {
        final int status;
        CompanyError(int status) { super("Company error " + status); this.status = status; }
    }

    public static final class AuditingCapability implements Capability, Ordered {
        @Override public int getOrder() { return 0; }
        @Override public Client enrich(Client client) {
            return (request, options) -> {
                AUDIT_CALLS.incrementAndGet();
                OPTIONS.set(options);
                return client.execute(request, options);
            };
        }
    }

    public static class ExistingTransport {
        @Bean
        Client companyClient() {
            Client transport = new Client.Default(null, null);
            return (request, options) -> {
                CUSTOM_TRANSPORT_CALLS.incrementAndGet();
                return transport.execute(request, options);
            };
        }
    }

    public static class ExistingOkHttpTransport implements org.springframework.beans.factory.DisposableBean {
        private final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        @Bean Client companyOkHttpClient() { return new feign.okhttp.OkHttpClient(client); }
        @Override public void destroy() {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
    }

    public CompatibilityProbe(String realEcho, Fixture original, Fixture echo) {
        this.realEcho = realEcho;
        this.original = original;
        this.echo = echo;
    }

    private ConfigurableApplicationContext start(Map<String, String> overrides, Class<?>... extraSources) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("probe.echo-url", echo.base() + "/proxy/mock");
        properties.put("probe.original-url", original.base());
        properties.put("spring.main.web-application-type", "none");
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "ERROR");
        properties.putAll(overrides);
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(App.class).sources(extraSources).run(args);
    }

    private void check(String name, boolean success) {
        checks.add(Map.of("name", name, "passed", success));
        System.out.println((success ? "PASS " : "FAIL ") + name);
        if (!success) throw new AssertionError(name);
    }

    private void run() throws Exception {
        try (var context = start(Map.of())) {
            Orders orders = context.getBean(Orders.class);
            int observationsBefore = OBSERVATIONS.get();
            ResponseEntity<Payload> result = orders.get();
            check("auto_configuration_from_plain_jar", result.getBody().equals(new Payload("echo", 7)));
            check("response_status_and_headers", result.getStatusCode().value() == 201
                    && "echo".equals(result.getHeaders().getFirst("X-Replied-By")));
            check("base_path_and_mock_prefix_once", echo.last().path.equals("/proxy/mock/compat/get"));
            check("original_authority_with_port_and_no_duplicate_header", echo.last().header("X-Original-Host")
                    .equals(List.of(URI.create(original.base()).getRawAuthority())));
            check("application_authorization_preserved", echo.last().header("Authorization")
                    .equals(List.of("Bearer compatibility-probe")));
            check("named_client_configuration_preserved", echo.last().header("X-Order-Config").equals(List.of("kept"))
                    && echo.last().header("X-Company").equals(List.of("present")));
            check("feign_options_preserved", OPTIONS.get().connectTimeoutMillis() == 321
                    && OPTIONS.get().readTimeoutMillis() == 654 && !OPTIONS.get().isFollowRedirects());
            check("default_query_parameter_preserved", echo.last().query.contains("company=default"));
            check("feign_call_is_observed", OBSERVATIONS.get() > observationsBefore);
            check("default_transport_is_jdk", transport(context).getClass().equals(Client.Default.class));

            Payload payload = new Payload("中文 / + % \"quoted\"", 19);
            check("ap_json_encoder_and_decoder_preserved", payload.equals(orders.post(payload)));
            check("request_method_and_content_type", echo.last().method.equals("POST")
                    && echo.last().header("Content-Type").get(0).startsWith("application/json"));

            String query = "a/b +%中文";
            try (Response ignored = orders.encoded("a b", query)) {
                check("encoded_path_not_double_encoded", echo.last().path.equals("/proxy/mock/compat/path/a%20b"));
                String encoded = Arrays.stream(echo.last().query.split("&")).filter(q -> q.startsWith("q=")).findFirst().orElseThrow();
                check("encoded_query_preserved", URLDecoder.decode(encoded.substring(2), StandardCharsets.UTF_8).equals(query));
            }
            try (Response ignored = orders.trailingSlash()) {
                check("trailing_slash_behavior_unchanged", echo.last().path.equals("/proxy/mock/compat/trailing/"));
            }
            check("binary_response_not_decoded_by_sdk", Arrays.equals(BINARY, orders.bytes()));
            try (Response response = orders.cookies()) {
                check("multi_value_response_headers_preserved", response.headers().entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase("Set-Cookie")).findFirst().orElseThrow().getValue().size() == 2);
            }

            int before = original.hits.size();
            try {
                orders.fail();
                throw new AssertionError("Expected company error decoder");
            } catch (CompanyError error) {
                check("custom_error_decoder_and_intentional_503_preserved", error.status == 503);
                check("mock_503_does_not_fall_back_to_real_service", original.hits.size() == before);
            }
            int attempts = AUDIT_CALLS.get();
            try {
                orders.slow();
                throw new AssertionError("Expected timeout");
            } catch (feign.RetryableException expected) {
                check("read_timeout_uses_existing_options", hasCause(expected, java.net.SocketTimeoutException.class));
                check("sdk_adds_no_retry_or_fallback", AUDIT_CALLS.get() == attempts + 1 && original.hits.size() == before);
            }
            try (Response response = orders.redirect()) {
                check("existing_redirect_policy_preserved", response.status() == 302 && original.hits.size() == before);
            }
            check("selected_context_id_routes_to_echo", context.getBean(LedgerPrimary.class).get().message.equals("echo"));
            check("same_name_different_context_id_isolated", context.getBean(LedgerSecondary.class).get().message.equals("original"));
            check("selected_service_name_bypasses_discovery", context.getBean(Inventory.class).get().message.equals("echo"));
            check("logical_service_name_and_client_path_preserved", echo.last().header("X-Original-Host").equals(List.of("inventory"))
                    && echo.last().path.equals("/proxy/mock/tenant/compat/get"));
            check("unselected_service_retains_load_balancer", context.getBean(Shipping.class).get().message.equals("original"));
            check("micrometer_observation_still_runs", OBSERVATIONS.get() > 0);
        }

        try (var context = start(Map.of("probe.lb-retry", "true"))) {
            check("retryable_load_balancer_is_active", context.getBean(FeignClientFactory.class)
                    .getInstance("inventory", Client.class) instanceof RetryableFeignBlockingLoadBalancerClient);
            check("retryable_load_balancer_selected_route", context.getBean(Inventory.class).get().message.equals("echo"));
            check("retryable_load_balancer_unselected_route", context.getBean(Shipping.class).get().message.equals("original"));
        }
        for (String transport : List.of("hc5", "okhttp")) {
            try (var context = start(Map.of("probe." + transport, "true"))) {
                if (transport.equals("okhttp") && SpringBootVersion.getVersion().startsWith("4.")) {
                    // A migration finding, not a success claim for OkHttp auto-configuration.
                    check("migration_openfeign5_okhttp_flag_no_longer_selects_okhttp", transport(context).getClass().equals(Client.Default.class));
                } else {
                    check(transport + "_transport_is_active", transport(context).getClass().getName().equals(
                            transport.equals("hc5") ? "feign.hc5.ApacheHttp5Client" : "feign.okhttp.OkHttpClient"));
                }
                check(transport + "_transport_direct_url", context.getBean(Orders.class).get().getBody().message.equals("echo"));
                check(transport + "_transport_service_name", context.getBean(Inventory.class).get().message.equals("echo"));
            }
        }
        try (var context = start(Map.of(), ExistingTransport.class)) {
            int calls = CUSTOM_TRANSPORT_CALLS.get();
            check("custom_transport_routes_to_echo", context.getBean(Orders.class).get().getBody().message.equals("echo"));
            check("existing_client_instance_preserved", CUSTOM_TRANSPORT_CALLS.get() == calls + 1);
        }
        try (var context = start(Map.of(), ExistingOkHttpTransport.class)) {
            check("explicit_ap_okhttp_client_is_preserved", transport(context).getClass().getName().equals("feign.okhttp.OkHttpClient")
                    && context.getBean(Orders.class).get().getBody().message.equals("echo"));
        }
        try (var context = start(Map.of("echo.client.include-clients", "isolated"))) {
            check("isolated_feign_context_still_has_sdk", context.getBean(Isolated.class).get().message.equals("echo"));
            check("isolated_context_keeps_parent_interceptors_disabled", echo.last().header("Authorization").isEmpty());
        }
        try (var context = start(Map.of("echo.client.include-clients", "resilient", "spring.cloud.openfeign.circuitbreaker.enabled", "true"))) {
            Resilient client = context.getBean(Resilient.class);
            check("circuit_breaker_success_routes_to_echo", client.get().message.equals("echo"));
            check("existing_application_fallback_preserved", client.fail().message.equals("app-fallback"));
        }
        try (var context = start(Map.of("echo.client.include-hosts", "127.0.0.1"))) {
            check("host_filter_accepts_selected_host", context.getBean(Orders.class).get().getBody().message.equals("echo"));
            int hits = echo.hits.size();
            try {
                context.getBean(Inventory.class).get();
                throw new AssertionError("Inventory should have no discovered instances");
            } catch (feign.FeignException expected) {
                check("host_filter_rejects_other_selected_client_host", expected.status() == 503 && echo.hits.size() == hits);
            }
        }
        try (var context = start(Map.of("echo.client.include-clients", "", "echo.client.include-hosts", "127.0.0.1"))) {
            check("host_only_selection", context.getBean(LedgerSecondary.class).get().message.equals("echo"));
        }
        try (var context = start(Map.of("probe.original-url", echo.base() + "/proxy/mock"))) {
            context.getBean(Orders.class).get();
            check("already_echo_url_not_rerouted", echo.last().path.equals("/proxy/mock/compat/get")
                    && echo.last().header("X-Original-Host").contains("spoofed-one"));
        }
        try (var context = start(Map.of("echo.client.enabled", "false", "echo.client.server-url", "not-a-url",
                "spring.cloud.discovery.client.simple.instances.inventory[0].uri", original.base()))) {
            check("disabled_restores_direct_url_without_changing_feign", context.getBean(Orders.class).get().getBody().message.equals("original"));
            check("disabled_restores_service_discovery", context.getBean(Inventory.class).get().message.equals("original"));
        }
        try (var context = start(Map.of("spring.config.location", "optional:classpath:/absent.properties",
                "echo.client.server-url", "not-a-url"))) {
            check("jar_alone_is_disabled_by_default", context.getBean(Orders.class).get().getBody().message.equals("original"));
        }
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        try (var context = start(Map.of("probe.echo-url", "http://127.0.0.1:" + closedPort + "/mock"))) {
            int before = original.hits.size();
            try {
                context.getBean(Orders.class).get();
                throw new AssertionError("Expected unavailable Echo to fail");
            } catch (feign.RetryableException expected) {
                check("unavailable_echo_does_not_call_real_downstream", original.hits.size() == before);
            }
        }
        try (var context = start(Map.of("echo.client.include-clients", "", "echo.client.include-hosts", ""))) {
            throw new AssertionError("Enabled SDK must require explicit selectors");
        } catch (RuntimeException expected) {
            check("enabled_without_selectors_fails_at_startup", causalMessage(expected).contains("include-clients or echo.client.include-hosts"));
        }
        actualEcho();
    }

    private void actualEcho() throws Exception {
        List<String> owned = new ArrayList<>();
        try {
            for (String host : List.of(URI.create(original.base()).getRawAuthority(), "inventory")) {
                Map<String, Object> spec = new LinkedHashMap<>(Map.of("protocol", "HTTP", "targetHost", host,
                        "matchKey", host.equals("inventory") ? "/tenant/compat/get" : "/compat/get", "method", "GET",
                        "enabled", true, "action", "MOCK", "status", 201,
                        "responseBody", Map.of("message", "real-echo", "count", 7),
                        "responseContentType", "TEXT"));
                spec.put("headerCondition", "Authorization=Bearer compatibility-probe");
                spec.put("responseHeaders", Map.of("X-Real-Echo-Test", "matched", "Content-Type", "application/json"));
                HttpResponse<String> created = admin("POST", "/api/admin/rules/apply",
                        json(Map.of("apiVersion", "echo.mock/v1", "kind", "Rule", "spec", spec)));
                if (created.statusCode() != 201) throw new AssertionError("Rule fixture failed: HTTP " + created.statusCode() + " " + created.body());
                var id = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(created.body());
                if (!id.find()) throw new AssertionError("Missing rule ID: " + created.body());
                owned.add(id.group(1));
            }
            try (var context = start(Map.of("probe.echo-url", realEcho + "/mock"))) {
                ResponseEntity<Payload> result = context.getBean(Orders.class).get();
                check("real_echo_rule_match_and_ap_decode", result.getStatusCode().value() == 201
                        && result.getBody().equals(new Payload("real-echo", 7))
                        && "matched".equals(result.getHeaders().getFirst("X-Real-Echo-Test")));
                check("real_echo_logical_service_rule_match", context.getBean(Inventory.class).get().message.equals("real-echo"));
            }
        } finally {
            for (String id : owned) {
                if (admin("DELETE", "/api/admin/rules/" + id, null).statusCode() != 204) {
                    throw new AssertionError("Could not remove owned rule " + id);
                }
            }
        }
    }

    private HttpResponse<String> admin(String method, String path, String body) throws Exception {
        String credentials = Base64.getEncoder().encodeToString("starter-probe:starter-probe-password".getBytes(StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(URI.create(realEcho + path)).header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json").header("Accept", "application/json");
        return HttpClient.newHttpClient().send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) if (type.isInstance(cause)) return true;
        return false;
    }

    private static Client transport(ConfigurableApplicationContext context) {
        Client client = context.getBean(FeignClientFactory.class).getInstance("orders", Client.class);
        if (client instanceof FeignBlockingLoadBalancerClient loadBalancer) return loadBalancer.getDelegate();
        if (client instanceof RetryableFeignBlockingLoadBalancerClient loadBalancer) return loadBalancer.getDelegate();
        return client;
    }

    private static String causalMessage(Throwable exception) {
        StringBuilder result = new StringBuilder();
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) result.append(cause.getMessage()).append('\n');
        return result.toString();
    }

    private record Hit(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        List<String> header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).findFirst().orElse(List.of());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String name;
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final List<Hit> hits = new CopyOnWriteArrayList<>();
        Fixture(String name) throws IOException {
            this.name = name;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        }
        String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        Hit last() { return hits.get(hits.size() - 1); }
        private void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getRawPath();
            hits.add(new Hit(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                    Map.copyOf(exchange.getRequestHeaders()), body));
            int status = 201;
            byte[] response = json(Map.of("message", name, "count", 7)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Replied-By", name);
            if (path.endsWith("/json")) response = body;
            if (path.endsWith("/bytes")) {
                response = BINARY;
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            }
            if (path.endsWith("/cookies")) {
                exchange.getResponseHeaders().add("Set-Cookie", "one=1; Path=/");
                exchange.getResponseHeaders().add("Set-Cookie", "two=2; Path=/");
            }
            if (path.endsWith("/status")) status = 503;
            if (path.endsWith("/redirect")) {
                status = 302;
                exchange.getResponseHeaders().add("Location", base() + "/redirect-followed");
            }
            if (path.endsWith("/slow")) {
                try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            try (exchange) {
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            }
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("bootVersion", SpringBootVersion.getVersion());
        report.put("javaVersion", System.getProperty("java.version"));
        Path sdk = Path.of(Class.forName("com.echo.sdk.autoconfigure.EchoFeignAutoConfiguration").getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        report.put("sdkJarSha256", sha256(Files.readAllBytes(sdk)));
        report.put("applicationYamlSha256", sha256(CompatibilityProbe.class.getResourceAsStream("/application.yml").readAllBytes()));
        try (Fixture original = new Fixture("original"); Fixture echo = new Fixture("echo")) {
            CompatibilityProbe probe = new CompatibilityProbe(args[1], original, echo);
            report.put("checks", probe.checks);
            try {
                probe.run();
                report.put("passed", true);
            } catch (Throwable failure) {
                report.put("passed", false);
                report.put("failure", causalMessage(failure));
                throw failure;
            } finally {
                Files.createDirectories(output.toAbsolutePath().getParent());
                Files.writeString(output, json(report) + "\n");
            }
        }
    }

    // Test/report transport only. The SDK never imports either Jackson generation.
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) return "{" + map.entrySet().stream()
                .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).collect(java.util.stream.Collectors.joining(",")) + "}";
        if (value instanceof Collection<?> list) return "[" + list.stream().map(CompatibilityProbe::json)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
