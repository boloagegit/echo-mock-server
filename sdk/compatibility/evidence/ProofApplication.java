package com.echo.proof;

import feign.RequestInterceptor;
import feign.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Independent live AP for screenshots; neither controller calls Echo directly. */
public class ProofApplication {
    public interface Backend {
        @PostMapping(value = "/sdk-proof/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
        Response orders(@RequestBody String body, @RequestHeader("X-Proof-Boot") String boot);
    }

    @FeignClient(name = "company-backend", contextId = "routed", url = "${proof.original-url}")
    public interface RoutedBackend extends Backend { }

    @FeignClient(name = "company-backend", contextId = "unselected", url = "${proof.original-url}")
    public interface UnselectedBackend extends Backend { }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableFeignClients(clients = {RoutedBackend.class, UnselectedBackend.class})
    public static class App {
        @Bean RequestInterceptor applicationAuthorization() {
            return template -> template.header("Authorization", "Bearer sdk-proof-application");
        }
    }

    @RestController
    public static class Api {
        private final RoutedBackend routed;
        private final UnselectedBackend unselected;
        private final Environment environment;

        public Api(RoutedBackend routed, UnselectedBackend unselected, Environment environment) {
            this.routed = routed;
            this.unselected = unselected;
            this.environment = environment;
        }

        @GetMapping(value = "/proof", produces = MediaType.TEXT_HTML_VALUE)
        public String page() throws IOException {
            return new String(ProofApplication.class.getResourceAsStream("/proof.html").readAllBytes(), StandardCharsets.UTF_8);
        }

        @GetMapping("/api/proof/meta")
        public Map<String, Object> metadata() throws Exception {
            Path sdk = Path.of(Class.forName("com.echo.sdk.autoconfigure.EchoFeignAutoConfiguration")
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("boot", SpringBootVersion.getVersion());
            result.put("java", System.getProperty("java.version"));
            result.put("sdkEnabled", environment.getProperty("echo.client.enabled", Boolean.class, false));
            result.put("originalFeignUrl", environment.getRequiredProperty("proof.original-url"));
            result.put("echoMockUrl", environment.getRequiredProperty("echo.client.server-url"));
            result.put("selectedContextId", "routed");
            result.put("sdkJar", sdk.getFileName().toString());
            result.put("sdkJarSha256", sha256(Files.readAllBytes(sdk)));
            result.put("applicationYamlSha256", sha256(ProofApplication.class.getResourceAsStream("/proof-application.yml").readAllBytes()));
            result.put("proofRun", environment.getRequiredProperty("proof.run"));
            return result;
        }

        @GetMapping("/api/proof/call")
        public Map<String, Object> call(@RequestParam("client") String client,
                @RequestParam("requestId") String requestId) throws Exception {
            if (!requestId.matches("[A-Za-z0-9-]{1,120}") || !client.matches("selected|unselected")) {
                throw new IllegalArgumentException("Invalid evidence request");
            }
            String boot = SpringBootVersion.getVersion();
            String body = "{\"requestId\":\"" + requestId + "\",\"boot\":\"" + boot
                    + "\",\"client\":\"" + client + "\"}";
            Backend backend = client.equals("selected") ? routed : unselected;
            long started = System.nanoTime();
            try (Response response = backend.orders(body, boot)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("requestId", requestId);
                result.put("client", client);
                result.put("originalFeignUrl", environment.getRequiredProperty("proof.original-url") + "/sdk-proof/orders");
                result.put("upstreamStatus", response.status());
                result.put("responseHeaders", response.headers());
                result.put("responseBody", new String(response.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8));
                result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
                return result;
            }
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(App.class).sources(Api.class).run(args);
    }
}
