package com.echo.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.context.request.ServletWebRequest;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.config.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WebMvcConfigTest {

    @Test
    void addViewControllers_shouldRegisterRoot() {
        WebMvcConfig config = new WebMvcConfig();
        ViewControllerRegistry registry = mock(ViewControllerRegistry.class);
        ViewControllerRegistration registration = mock(ViewControllerRegistration.class);
        when(registry.addViewController("/")).thenReturn(registration);

        config.addViewControllers(registry);

        verify(registry).addViewController("/");
        verify(registration).setViewName("forward:/index.html");
        verify(registry).setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void addResourceHandlers_shouldRegisterIndexHtml() {
        WebMvcConfig config = new WebMvcConfig();
        ResourceHandlerRegistry registry = mock(ResourceHandlerRegistry.class);
        ResourceHandlerRegistration registration = mock(ResourceHandlerRegistration.class);
        ResourceHandlerRegistration webjarsRegistration = mock(ResourceHandlerRegistration.class);
        when(registry.addResourceHandler("/index.html")).thenReturn(registration);
        when(registry.addResourceHandler("/webjars/**")).thenReturn(webjarsRegistration);
        when(registration.addResourceLocations(anyString())).thenReturn(registration);
        when(webjarsRegistration.addResourceLocations(anyString())).thenReturn(webjarsRegistration);
        when(webjarsRegistration.setCacheControl(any(CacheControl.class))).thenReturn(webjarsRegistration);

        config.addResourceHandlers(registry);

        verify(registry).addResourceHandler("/index.html");
        verify(registration).addResourceLocations("classpath:/static/");
        verify(registration).setCachePeriod(0);
        // WebJars are cached by the browser but always revalidated.
        verify(webjarsRegistration).setCacheControl(argThat(cache -> "no-cache".equals(cache.getHeaderValue())));
    }

    @Test
    void configureContentNegotiation_shouldSetDefaults() throws Exception {
        WebMvcConfig config = new WebMvcConfig();
        ContentNegotiationConfigurer configurer = mock(ContentNegotiationConfigurer.class);
        when(configurer.favorParameter(anyBoolean())).thenReturn(configurer);
        when(configurer.ignoreAcceptHeader(anyBoolean())).thenReturn(configurer);
        when(configurer.defaultContentTypeStrategy(any())).thenReturn(configurer);
        when(configurer.mediaType(anyString(), any(MediaType.class))).thenReturn(configurer);

        config.configureContentNegotiation(configurer);

        verify(configurer).favorParameter(false);
        verify(configurer).ignoreAcceptHeader(false);
        var strategy = ArgumentCaptor.forClass(ContentNegotiationStrategy.class);
        verify(configurer).defaultContentTypeStrategy(strategy.capture());

        var mock = new MockHttpServletRequest();
        mock.setServletPath("/mock/actuator/health/liveness");
        assertThat(strategy.getValue().resolveMediaTypes(new ServletWebRequest(mock)))
                .containsExactly(MediaType.APPLICATION_OCTET_STREAM);

        var contextualProbe = new MockHttpServletRequest();
        contextualProbe.setContextPath("/echo");
        contextualProbe.setServletPath("/actuator/health/readiness");
        assertThat(strategy.getValue().resolveMediaTypes(new ServletWebRequest(contextualProbe)))
                .containsExactly(MediaType.APPLICATION_JSON);

        var prefixedServletProbe = new MockHttpServletRequest();
        prefixedServletProbe.setServletPath("/api");
        prefixedServletProbe.setPathInfo("/actuator/health/liveness");
        assertThat(strategy.getValue().resolveMediaTypes(new ServletWebRequest(prefixedServletProbe)))
                .containsExactly(MediaType.APPLICATION_JSON);
    }

    @Test
    void restTemplate_shouldBeConfigured() throws Exception {
        WebMvcConfig config = new WebMvcConfig();
        
        var restTemplate = config.restTemplate();
        
        assertThat(restTemplate).isNotNull();
    }

    @Test
    void restTemplate_shouldUseHttpComponentsFactory() throws Exception {
        WebMvcConfig config = new WebMvcConfig();
        
        var restTemplate = config.restTemplate();
        
        // 驗證使用 HttpComponentsClientHttpRequestFactory (支援 SSL 忽略)
        assertThat(restTemplate.getRequestFactory())
                .isInstanceOf(org.springframework.http.client.HttpComponentsClientHttpRequestFactory.class);
    }
}
