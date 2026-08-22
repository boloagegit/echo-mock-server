package com.echo.controller;

import com.echo.config.LdapConfig;
import com.echo.config.SecurityConfig;
import com.echo.dto.HttpTargetConnectionDto;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.HttpOutboundForwarder;
import com.echo.service.HttpTargetConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HttpTargetConnectionController.class)
@Import({SecurityConfig.class, LdapConfig.class})
class HttpTargetConnectionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HttpTargetConnectionService service;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private HttpOutboundForwarder forwarder;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private BuiltinUserRepository builtinUserRepository;

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/http-target-connections")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void listAllowsAuthenticatedUserWithoutSecretMaterial() throws Exception {
        when(service.list()).thenReturn(List.of(dto()));

        mockMvc.perform(get("/api/admin/http-target-connections")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Primary HTTP"))
                .andExpect(jsonPath("$[0].secretConfigured").value(true))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].encryptedSecret").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "USER")
    void createStillRequiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/admin/http-target-connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void metricsStillRequireAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/http-target-connections/metrics")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    private static HttpTargetConnectionDto dto() {
        return new HttpTargetConnectionDto(
                1L, 0L, "Primary HTTP", "https://downstream.example", "BASIC",
                "service-user", true, 5, 30, true, true, true,
                LocalDateTime.now(), LocalDateTime.now());
    }
}
