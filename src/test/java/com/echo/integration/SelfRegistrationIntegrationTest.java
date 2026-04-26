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
 * 自助註冊整合測試（啟用狀態）
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
    @DisplayName("自助註冊啟用時，未登入使用者可建立帳號並登入")
    void selfRegister_shouldCreateAccountAndAllowLogin() throws Exception {
        // 1. 自助註冊
        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "selfuser", "password", "mypass123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("selfuser"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"));

        // 2. 驗證帳號已存在於資料庫
        assertThat(builtinUserRepository.existsByUsername("selfuser")).isTrue();

        // 3. 用新帳號登入
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "selfuser")
                        .param("password", "mypass123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("自助註冊時帳號名稱重複應回傳 409")
    void selfRegister_shouldRejectDuplicateUsername() throws Exception {
        // 先註冊一個帳號
        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "dupuser", "password", "secret123"))))
                .andExpect(status().isCreated());

        // 再用同名註冊
        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "dupuser", "password", "another123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("USERNAME_EXISTS"));
    }
}
