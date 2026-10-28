package com.echo.config;

import jakarta.servlet.ServletContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.server.observation.ServerRequestObservationConvention;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/** Conservatively keep Servlet extensions in the path instead of silently bypassing them. */
final class NativeHttpCompatibility {
    private static final Logger LOG = LoggerFactory.getLogger(NativeHttpCompatibility.class);
    private static final Set<String> FILTERS = Set.of(
            "org.springframework.boot.web.servlet.filter.OrderedCharacterEncodingFilter",
            "org.springframework.boot.web.servlet.filter.OrderedFormContentFilter",
            "org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter",
            "org.springframework.web.filter.ServerHttpObservationFilter",
            "org.springframework.web.filter.DelegatingFilterProxy",
            "org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean$1",
            "org.springframework.web.filter.ForwardedHeaderFilter",
            "org.springframework.boot.web.servlet.support.ErrorPageFilter",
            "io.undertow.websockets.jsr.JsrWebSocketFilter",
            // This built-in filter also checks the resolved management route internally.
            AdminLogQueryAdmissionConfiguration.AdmissionFilter.class.getName(),
            RuleChangeWait.AtRequestEnd.class.getName());
    private static final Set<String> MVC_CONFIGURERS = Set.of(
            WebMvcConfig.class.getName(),
            "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter",
            "org.springframework.security.config.annotation.web.configuration.WebMvcSecurityConfiguration",
            "org.springframework.boot.actuate.autoconfigure.endpoint.web.servlet.WebMvcEndpointManagementContextConfiguration$EndpointObjectMapperWebMvcConfigurer",
            "org.springframework.data.web.config.SpringDataWebConfiguration");
    private static final Set<String> ADVICES = Set.of(
            RuleChangeWait.BeforeBody.class.getName(), "com.echo.controller.AdminExceptionHandler");
    private final SingletonSupplier<Boolean> supported;

    NativeHttpCompatibility(ApplicationContext context, ServletContext servletContext, Environment environment) {
        // Evaluate after server initialization, when all filter registrations and MVC beans exist.
        supported = SingletonSupplier.of(() -> inspect(context, servletContext, environment));
    }

    boolean supported() { return supported.obtain(); }

    private static boolean inspect(ApplicationContext context, ServletContext servletContext, Environment environment) {
        if (!"/".equals(environment.getProperty("spring.mvc.servlet.path", "/"))
                || !environment.getProperty("server.servlet.context-path", "").isEmpty()
                || !"UTF-8".equalsIgnoreCase(environment.getProperty("server.servlet.encoding.charset", "UTF-8"))
                || environment.getProperty("spring.jpa.open-in-view", Boolean.class, false)
                || environment.getProperty("spring.main.allow-bean-definition-overriding", Boolean.class, false)) {
            return fallback("custom Servlet path, encoding, open-in-view or bean overriding");
        }
        if (context.getBeanNamesForType(ServerRequestObservationConvention.class).length > 0) {
            return fallback("custom Servlet observation convention");
        }
        if (context.getBeanNamesForType(HttpFirewall.class).length > 0
                || hasAdditionalBean(context, WebSecurityCustomizer.class, "webSecurityCustomizer")
                || hasAdditionalBean(context, SecurityFilterChain.class, "securityFilterChain")) {
            return fallback("custom Servlet security");
        }
        for (var filter : servletContext.getFilterRegistrations().values()) {
            if (!FILTERS.contains(filter.getClassName())) return fallback("Servlet filter " + filter.getClassName());
        }
        for (Object configurer : context.getBeansOfType(WebMvcConfigurer.class).values()) {
            String name = ClassUtils.getUserClass(configurer).getName();
            if (!MVC_CONFIGURERS.contains(name)) return fallback("MVC configuration " + name);
        }
        for (Object advice : context.getBeansWithAnnotation(ControllerAdvice.class).values()) {
            String name = ClassUtils.getUserClass(advice).getName();
            if (!ADVICES.contains(name)) return fallback("controller advice " + name);
        }
        return true;
    }

    private static boolean hasAdditionalBean(ApplicationContext context, Class<?> type, String standard) {
        for (String name : context.getBeanNamesForType(type)) {
            if (!name.equals(standard)) return true;
        }
        return false;
    }

    private static boolean fallback(String reason) {
        LOG.info("HTTP mock requests retain Servlet handling: {}", reason);
        return false;
    }
}
