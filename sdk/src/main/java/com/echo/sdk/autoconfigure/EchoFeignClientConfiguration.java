package com.echo.sdk.autoconfigure;

import com.echo.sdk.feign.EchoFeignCapability;
import com.echo.sdk.feign.EchoRoutingSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Loaded only inside Feign's named contexts, not component-scanned into the AP. */
public class EchoFeignClientConfiguration {
    @Bean
    EchoFeignCapability echoFeignCapability(EchoRoutingSettings settings, Environment environment) {
        return new EchoFeignCapability(settings,
                environment.getRequiredProperty("spring.cloud.openfeign.client.name"));
    }
}
