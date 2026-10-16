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
 * 請求中變更了規則時，回應前等到新的規則快照生效：透過 API 存檔後立刻送出的模擬請求一定用到新規則，
 * 而模擬請求本身不必等待重建（見 {@link RuleSnapshotCache}）。一次批次匯入只等一次。
 */
public final class RuleChangeWait {

    private RuleChangeWait() {
    }

    /** 有回應內容時，在寫出內容前等待（內容較大時回應可能在請求結束前就已送出）。 */
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

    /** 沒有回應內容的請求在結束時等待；請求開始時丟掉同一執行緒上一個請求留下的狀態。 */
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
