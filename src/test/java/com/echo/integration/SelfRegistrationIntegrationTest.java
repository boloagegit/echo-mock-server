package com.echo.integration;

import com.echo.repository.BuiltinUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Self-service registration integration test (enabled status)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "echo.builtin-account.self-registration=true")
class SelfRegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BuiltinUserRepository builtinUserRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void cleanup() {
        builtinUserRepository.deleteAll();
    }

    @Test
    @DisplayName("When self-registration is enabled, anonymous users can create accounts and log in")
    void selfRegister_shouldCreateAccountAndAllowLogin() throws Exception {
        // 1. Self-service registration
        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "selfuser", "password", "mypass123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("selfuser"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"));

        // 2. Verify that the account already exists in the database
        assertThat(builtinUserRepository.existsByUsername("selfuser")).isTrue();

        // 3. Log in with new account
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "selfuser")
                        .param("password", "mypass123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("Self-registration with a duplicate username returns 409")
    void selfRegister_shouldRejectDuplicateUsername() throws Exception {
        // Register an account first
        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "dupuser", "password", "secret123"))))
                .andExpect(status().isCreated());

        // Register again with the same name
        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "dupuser", "password", "another123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("USERNAME_EXISTS"));
    }
}
