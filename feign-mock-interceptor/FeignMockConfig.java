package com.example.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "mock.enabled", havingValue = "true")
public class FeignMockConfig {

    @Value("${mock.server.url:http://localhost:8080}")
    private String mockServerUrl;

    @Bean
    public RequestInterceptor mockRedirectInterceptor() {
        return template -> {
            String targetUrl = template.feignTarget().url();
            URI uri = URI.create(targetUrl);
            
            // Keep original host
            String originalHost = uri.getHost();
            if (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443) {
                originalHost += ":" + uri.getPort();
            }
            template.header("X-Original-Host", originalHost);
            
            // Reserve base path (such as /csiw), combined with path of template
            String basePath = uri.getPath();
            if (basePath == null || basePath.equals("/")) {
                basePath = "";
            }
            template.target(mockServerUrl + "/mock" + basePath);
        };
    }
}
