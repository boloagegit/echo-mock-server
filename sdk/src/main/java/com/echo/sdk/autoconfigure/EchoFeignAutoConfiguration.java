package com.echo.sdk.autoconfigure;

import com.echo.sdk.feign.EchoRoutingSettings;
import feign.Capability;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.FeignClientSpecification;
import org.springframework.context.annotation.Bean;

/** Registers a per-client configuration without replacing the AP's Feign beans. */
@AutoConfiguration(beforeName = "org.springframework.cloud.openfeign.FeignAutoConfiguration")
@ConditionalOnClass({Capability.class, FeignClientSpecification.class})
@ConditionalOnProperty(prefix = "echo.client", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EchoClientProperties.class)
public class EchoFeignAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    EchoRoutingSettings echoRoutingSettings(EchoClientProperties properties) {
        return new EchoRoutingSettings(properties.getServerUrl(),
                properties.getIncludeClients(), properties.getIncludeHosts());
    }

    @Bean
    FeignClientSpecification echoFeignClientSpecification(EchoRoutingSettings settings) {
        return new FeignClientSpecification("default.echoRouting", getClass().getName(),
                new Class<?>[] {EchoFeignClientConfiguration.class});
    }
}
