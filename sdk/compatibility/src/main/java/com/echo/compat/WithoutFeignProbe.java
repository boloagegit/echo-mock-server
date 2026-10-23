package com.echo.compat;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Run with all Feign artifacts removed from the classpath. */
public class WithoutFeignProbe {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    public static class App { }

    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(App.class).run(
                "--spring.config.location=optional:classpath:/absent.properties", "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
                "--echo.client.enabled=true", "--echo.client.server-url=invalid")) {
            if (context.containsBean("echoRoutingSettings")) throw new AssertionError("SDK must back off without Feign");
            System.out.println("PASS no_feign_dependency_does_not_break_startup");
        }
    }
}
