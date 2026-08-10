package com.echo.controller;

import com.echo.config.LdapConfig;
import com.echo.config.SecurityConfig;
import com.echo.dto.JmsTargetConnectionDto;
import com.echo.dto.JmsTargetConnectionRequest;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.JmsTargetConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JmsTargetConnectionController.class)
@Import({SecurityConfig.class, LdapConfig.class})
class JmsTargetConnectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JmsTargetConnectionService service;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private BuiltinUserRepository builtinUserRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/jms-target-connections")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void listRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/jms-target-connections")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listReturnsProfilesWithoutPasswordMaterial() throws Exception {
        when(service.list()).thenReturn(List.of(dto("1", true)));

        mockMvc.perform(get("/api/admin/jms-target-connections")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Primary"))
                .andExpect(jsonPath("$[0].passwordConfigured").value(true))
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].encryptedPassword").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createReturnsCreatedProfile() throws Exception {
        JmsTargetConnectionRequest request = request(null);
        when(service.create(any())).thenReturn(dto("1", true));

        mockMvc.perform(post("/api/admin/jms-target-connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("1"))
                .andExpect(jsonPath("$.defaultConnection").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void validationFailureReturnsBadRequest() throws Exception {
        when(service.create(any())).thenThrow(
                new IllegalArgumentException("JMS_SERVER_URL_REQUIRED"));

        mockMvc.perform(post("/api/admin/jms-target-connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("JMS_SERVER_URL_REQUIRED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void staleUpdateReturnsConflict() throws Exception {
        when(service.update(eq(1L), any())).thenThrow(
                new ObjectOptimisticLockingFailureException("stale", null));

        mockMvc.perform(put("/api/admin/jms-target-connections/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(0L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OPTIMISTIC_LOCK_CONFLICT"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void testSupportsLegacyProfileIdentifier() throws Exception {
        when(service.test(JmsTargetConnectionService.LEGACY_ID)).thenReturn(
                new JmsTargetConnectionService.ConnectionTestResult(true, 12, null));

        mockMvc.perform(post("/api/admin/jms-target-connections/legacy-yaml/test")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.elapsedMs").value(12));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletingMissingProfileReturnsNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("JMS_CONNECTION_NOT_FOUND"))
                .when(service).delete(99L);

        mockMvc.perform(delete("/api/admin/jms-target-connections/99")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteReturnsSuccess() throws Exception {
        mockMvc.perform(delete("/api/admin/jms-target-connections/2")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    private static JmsTargetConnectionRequest request(Long version) {
        return new JmsTargetConnectionRequest(version, "Primary", "artemis",
                "tcp://localhost:61616", "admin", "secret", false,
                "TARGET.REQUEST", 30, true, true);
    }

    private static JmsTargetConnectionDto dto(String id, boolean defaultConnection) {
        return new JmsTargetConnectionDto(id, 0L, "Primary", "artemis",
                "tcp://localhost:61616", "admin", true, "TARGET.REQUEST", 30,
                true, defaultConnection, false, LocalDateTime.now(), LocalDateTime.now());
    }
}
