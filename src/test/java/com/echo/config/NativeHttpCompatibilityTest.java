package com.echo.config;

import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeHttpCompatibilityTest {
    @Test
    void customFilterMustStayInTheRequestPath() {
        ServletContext servlet = mock(ServletContext.class);
        FilterRegistration filter = mock(FilterRegistration.class);
        when(filter.getClassName()).thenReturn("example.RequestSignatureFilter");
        when(servlet.getFilterRegistrations()).thenAnswer(ignored -> Map.of("signature", filter));
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            assertThat(new NativeHttpCompatibility(context, servlet, new MockEnvironment()).supported()).isFalse();
        }
    }

    @Test
    void mvcObservationAndFirewallExtensionsRequireServletHandling() {
        for (Class<?> extension : new Class<?>[]{CustomMvc.class, DefaultServerRequestObservationConvention.class,
                StrictHttpFirewall.class}) {
            try (var context = new GenericApplicationContext()) {
                context.registerBean(extension);
                context.refresh();
                assertThat(new NativeHttpCompatibility(context, emptyServlet(), new MockEnvironment()).supported())
                        .as(extension.getName()).isFalse();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"spring.mvc.servlet.path,/api", "server.servlet.context-path,/echo",
            "server.servlet.encoding.charset,ISO-8859-1", "spring.jpa.open-in-view,true",
            "spring.main.allow-bean-definition-overriding,true"})
    void servletSettingsAreNotSilentlyIgnored(String key, String value) {
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            assertThat(new NativeHttpCompatibility(context, emptyServlet(),
                    new MockEnvironment().withProperty(key, value)).supported()).isFalse();
        }
    }

    private static ServletContext emptyServlet() {
        ServletContext servlet = mock(ServletContext.class);
        when(servlet.getFilterRegistrations()).thenReturn(Map.of());
        return servlet;
    }

    static class CustomMvc implements WebMvcConfigurer {}
}
