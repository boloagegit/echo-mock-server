package com.echo.controller;

import com.echo.config.LdapConfig;
import com.echo.config.SecurityConfig;
import com.echo.dto.ResourceSnapshotDto;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.ResourceMonitoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ResourceMonitoringController.class)
@Import({SecurityConfig.class, LdapConfig.class})
class ResourceMonitoringControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ResourceMonitoringService monitoring;
    @MockitoBean @SuppressWarnings("UnusedVariable") private BuiltinUserRepository builtinUsers;

    @Test
    void anonymousCannotCollectDiagnostics() throws Exception {
        mvc.perform(get("/api/admin/resources")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/resources/access")).andExpect(status().isUnauthorized());
        verifyNoInteractions(monitoring);
    }

    @Test @WithMockUser(roles = "USER")
    void regularUserCannotCollectDiagnostics() throws Exception {
        mvc.perform(get("/api/admin/resources")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/resources/access")).andExpect(status().isForbidden());
        verifyNoInteractions(monitoring);
    }

    @Test @WithMockUser(roles = "ADMIN")
    void adminGetsNonCacheableSnapshot() throws Exception {
        when(monitoring.snapshot()).thenReturn(new ResourceSnapshotDto(true, Instant.parse("2026-09-30T00:00:00Z"),
                Instant.parse("2026-09-29T00:00:00Z"), Map.of("jvm", new ResourceSnapshotDto.Section(
                        ResourceSnapshotDto.State.AVAILABLE, Map.of("heapUsedBytes", 0L)))));
        mvc.perform(get("/api/admin/resources")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sections.jvm.values.heapUsedBytes").value(0))
                .andExpect(jsonPath("$.sections.jvm.state").value("AVAILABLE"));
        verify(monitoring, times(1)).snapshot();
    }

    @Test @WithMockUser(roles = "ADMIN")
    void accessCheckDoesNotCollectOrDependOnDatabaseStatus() throws Exception {
        mvc.perform(get("/api/admin/resources/access")).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(monitoring, builtinUsers);
    }
}
