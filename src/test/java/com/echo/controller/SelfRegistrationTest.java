package com.echo.controller;

import com.echo.config.LdapConfig;
import com.echo.config.SecurityConfig;
import com.echo.entity.BuiltinUser;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.BuiltinUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 自助註冊功能測試（啟用狀態）
 * <p>
 * 使用 @TestPropertySource 將 self-registration 設為 true，
 * 測試公開註冊端點的正常流程與錯誤處理。
 */
@WebMvcTest(BuiltinAccountController.class)
@Import({SecurityConfig.class, LdapConfig.class})
@TestPropertySource(properties = "echo.builtin-account.self-registration=true")
class SelfRegistrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BuiltinUserService builtinUserService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private BuiltinUserRepository builtinUserRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void selfRegister_shouldReturn201_whenEnabledAndValid() throws Exception {
        BuiltinUser created = BuiltinUser.builder()
                .id(1L).username("newuser").role("ROLE_USER").enabled(true).build();
        when(builtinUserService.createUser("newuser", "secret123")).thenReturn(created);

        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "newuser", "password", "secret123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"));
    }

    @Test
    void selfRegister_shouldReturn409_whenUsernameExists() throws Exception {
        when(builtinUserService.createUser("existing", "secret123"))
                .thenThrow(new IllegalArgumentException("USERNAME_EXISTS"));

        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "existing", "password", "secret123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("USERNAME_EXISTS"));
    }

    @Test
    void selfRegister_shouldReturn400_whenUsernameTooShort() throws Exception {
        when(builtinUserService.createUser("ab", "secret123"))
                .thenThrow(new IllegalArgumentException("USERNAME_LENGTH"));

        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "ab", "password", "secret123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("USERNAME_LENGTH"));
    }

    @Test
    void selfRegister_shouldReturn400_whenPasswordTooShort() throws Exception {
        when(builtinUserService.createUser("newuser", "123"))
                .thenThrow(new IllegalArgumentException("PASSWORD_TOO_SHORT"));

        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "newuser", "password", "123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PASSWORD_TOO_SHORT"));
    }

    @Test
    void selfRegister_shouldBeAccessibleWithoutAuth() throws Exception {
        // 不帶任何認證，應該也能存取（公開 API）
        BuiltinUser created = BuiltinUser.builder()
                .id(1L).username("anonymous").role("ROLE_USER").enabled(true).build();
        when(builtinUserService.createUser("anonymous", "secret123")).thenReturn(created);

        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "anonymous", "password", "secret123"))))
                .andExpect(status().isCreated());
    }
}
