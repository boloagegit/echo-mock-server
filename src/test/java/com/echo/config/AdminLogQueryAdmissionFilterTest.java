package com.echo.config;

import com.echo.service.AdminLogQueryAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminLogQueryAdmissionFilterTest {
    @Test
    void encodedListPathsAndContextPathsCannotBypassCapacity() throws Exception {
        var admission = new AdminLogQueryAdmission(enabled());
        var filter = new AdminLogQueryAdmissionConfiguration.AdmissionFilter(admission);
        try (var held = admission.acquire()) {
            assertThat(held).isNotNull();
            for (String method : new String[]{"GET", "HEAD"}) {
                for (String context : new String[]{"", "/echo"}) {
                    var request = request(method, context + "/api/admin/%6cogs");
                    request.setContextPath(context);
                    var response = new MockHttpServletResponse();
                    filter.doFilter(request, response,
                            (req, res) -> { throw new AssertionError("Encoded list query bypassed capacity"); });
                    assertThat(response.getStatus()).isEqualTo(429);
                    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
                    if ("HEAD".equals(method)) assertThat(response.getContentAsString()).isEmpty();
                }
            }
        }
        assertThat(admission.snapshot()).containsEntry("active", 0).containsEntry("queued", 0);
    }

    @Test
    void permitCoversSynchronousQueryAndSerializationAndIsReleasedAfterWriteFailure() throws Exception {
        var properties = enabled();
        var admission = new AdminLogQueryAdmission(properties);
        var filter = new AdminLogQueryAdmissionConfiguration.AdmissionFilter(admission);
        assertThatThrownBy(() -> filter.doFilter(request("GET", "/api/admin/logs"),
                new MockHttpServletResponse(), (request, response) -> {
                    assertThat(admission.snapshot()).containsEntry("active", 1);
                    response.getWriter().write("partial response");
                    throw new IOException("client disconnected during serialization");
                })).isInstanceOf(IOException.class);
        assertThat(admission.snapshot()).containsEntry("active", 0).containsEntry("queued", 0);
        filter.doFilter(request("GET", "/api/admin/logs"), new MockHttpServletResponse(),
                (request, response) -> assertThat(admission.snapshot()).containsEntry("active", 1));
        assertThat(admission.snapshot()).containsEntry("active", 0).containsEntry("admitted", 2L);
    }

    @Test
    void capacityResponseHasRetryAfterAndNeverRunsTheQuery() throws Exception {
        var admission = new AdminLogQueryAdmission(enabled());
        var filter = new AdminLogQueryAdmissionConfiguration.AdmissionFilter(admission);
        try (var held = admission.acquire()) {
            assertThat(held).isNotNull();
            var response = new MockHttpServletResponse();
            filter.doFilter(request("GET", "/api/admin/logs"), response,
                    (req, res) -> { throw new AssertionError("Busy query must not run"); });
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"ADMIN_LOG_QUERY_BUSY\"}");
            assertThat(admission.snapshot()).containsEntry("active", 1).containsEntry("queued", 0);
        }
    }

    @Test
    void disabledProtectionAndUnrelatedPathsRetainTheirBehavior() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var disabledProperties = new AdminLogQueryProperties();
        disabledProperties.setEnabled(false);
        var disabled = new AdminLogQueryAdmission(disabledProperties);
        var filter = new AdminLogQueryAdmissionConfiguration.AdmissionFilter(disabled);
        filter.doFilter(request("GET", "/api/admin/logs"), new MockHttpServletResponse(),
                (req, res) -> calls.incrementAndGet());
        assertThat(disabled.snapshot()).containsEntry("admitted", 0L);
        var admission = new AdminLogQueryAdmission(enabled());
        filter = new AdminLogQueryAdmissionConfiguration.AdmissionFilter(admission);
        for (String path : new String[]{"/api/admin/logs/summary", "/api/admin/logs/1/detail",
                "/api/admin/resources", "/mock/api/admin/logs"}) {
            filter.doFilter(request("GET", path), new MockHttpServletResponse(),
                    (req, res) -> calls.incrementAndGet());
        }
        filter.doFilter(request("DELETE", "/api/admin/logs"), new MockHttpServletResponse(),
                (req, res) -> calls.incrementAndGet());
        assertThat(admission.snapshot()).containsEntry("admitted", 0L);
        assertThat(calls).hasValue(6);
    }

    private static AdminLogQueryProperties enabled() {
        var properties = new AdminLogQueryProperties();
        properties.setEnabled(true);
        properties.setMaxWaitMillis(0);
        return properties;
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }
}
