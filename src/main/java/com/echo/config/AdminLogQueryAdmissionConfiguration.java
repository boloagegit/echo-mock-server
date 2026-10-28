package com.echo.config;

import com.echo.service.AdminLogQueryAdmission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/** Authenticate first; unrelated management, native mock, and SSE requests never wait here. */
@Configuration(proxyBeanMethods = false)
public class AdminLogQueryAdmissionConfiguration {
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> adminLogQueryAdmissionFilter(AdminLogQueryAdmission admission) {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new AdmissionFilter(admission));
        registration.setName("adminLogQueryAdmission");
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        registration.addUrlPatterns("/api/admin/logs");
        registration.setAsyncSupported(false);
        return registration;
    }

    static final class AdmissionFilter extends OncePerRequestFilter {
        private final AdminLogQueryAdmission admission;

        AdmissionFilter(AdminLogQueryAdmission admission) { this.admission = admission; }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String method = request.getMethod();
            // Spring MVC also executes the GET handler's database query for HEAD.
            return !admission.isEnabled() || (!"GET".equals(method) && !"HEAD".equals(method))
                    || (!request.getRequestURI().equals(request.getContextPath() + "/api/admin/logs")
                    // Servlet mappings also accept encoded unreserved characters.
                    && !UrlPathHelper.defaultInstance.getPathWithinApplication(request).equals("/api/admin/logs"));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            try (var lease = admission.acquire()) {
                if (lease == null) {
                    busy(request, response, 429, "ADMIN_LOG_QUERY_BUSY");
                    return;
                }
                chain.doFilter(request, response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                busy(request, response, 503, "ADMIN_LOG_QUERY_INTERRUPTED");
            }
        }

        private static void busy(HttpServletRequest request, HttpServletResponse response,
                int status, String code) throws IOException {
            response.setStatus(status);
            response.setHeader("Retry-After", "1");
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            if (!"HEAD".equals(request.getMethod())) {
                response.getWriter().write("{\"error\":\"" + code + "\"}");
            }
        }
    }
}
