package com.echo.config;

import com.echo.service.RuleSnapshotCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.io.IOException;

/**
 * If a request changes rules, wait for the new snapshot before responding so mock requests sent immediately after an API save use the new rules,
 * The simulation request itself does not have to wait for reconstruction (see {@link RuleSnapshotCache}). Only wait once for a batch import.
 */
public final class RuleChangeWait {

    private RuleChangeWait() {
    }

    /** When there is response content, wait before writing the content (if the content is large, the response may be sent before the request is completed). */
    @ControllerAdvice
    public static class BeforeBody implements ResponseBodyAdvice<Object> {
        @Override
        public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
            return true;
        }

        @Override
        public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                      Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                      ServerHttpRequest request, ServerHttpResponse response) {
            RuleSnapshotCache.awaitPendingChanges();
            return body;
        }
    }

    /** A request without response content is waited at the end; the state left by the previous request on the same thread is discarded when the request is started. */
    @Component
    public static class AtRequestEnd extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            RuleSnapshotCache.clearPendingChanges();
            try {
                chain.doFilter(request, response);
            } finally {
                RuleSnapshotCache.awaitPendingChanges();
            }
        }
    }
}
