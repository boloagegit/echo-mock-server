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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * BuiltinAccountController 單元測試
 * <p>
 * 使用 @WebMvcTest 搭配 SecurityConfig 測試 API 端點的正常流程、錯誤處理與權限控制。
 */
@WebMvcTest(BuiltinAccountController.class)
@Import({SecurityConfig.class, LdapConfig.class})
class BuiltinAccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BuiltinUserService builtinUserService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private BuiltinUserRepository builtinUserRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========== GET /api/admin/builtin-users — 帳號清單 ==========

    @Test
    void listUsers_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/builtin-users")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void listUsers_shouldReturn403_whenNotAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/builtin-users")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listUsers_shouldReturn200_whenAdmin() throws Exception {
        BuiltinUser user = BuiltinUser.builder()
                .id(1L)
                .username("vendor01")
                .role("ROLE_USER")
                .enabled(true)
                .passwordResetRequested(false)
                .forceChangePassword(false)
                .createdAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .build();
        when(builtinUserService.listUsers()).thenReturn(List.of(user));

        mockMvc.perform(get("/api/admin/builtin-users")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("vendor01"))
                .andExpect(jsonPath("$[0].role").value("ROLE_USER"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    // ========== POST /api/admin/builtin-users — 建立帳號 ==========

    @Test
    @WithMockUser(roles = "ADMIN")
    void createUser_shouldReturn201_onSuccess() throws Exception {
        BuiltinUser created = BuiltinUser.builder()
                .id(1L)
                .username("vendor01")
                .role("ROLE_USER")
                .enabled(true)
                .build();
        when(builtinUserService.createUser("vendor01", "secret123")).thenReturn(created);

        mockMvc.perform(post("/api/admin/builtin-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "vendor01", "password", "secret123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("vendor01"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createUser_shouldReturn409_onDuplicateUsername() throws Exception {
        when(builtinUserService.createUser("vendor01", "secret123"))
                .thenThrow(new IllegalArgumentException("USERNAME_EXISTS"));

        mockMvc.perform(post("/api/admin/builtin-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "vendor01", "password", "secret123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("USERNAME_EXISTS"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createUser_shouldReturn400_onUsernameTooShort() throws Exception {
        when(builtinUserService.createUser("ab", "secret123"))
                .thenThrow(new IllegalArgumentException("USERNAME_LENGTH"));

        mockMvc.perform(post("/api/admin/builtin-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "ab", "password", "secret123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("USERNAME_LENGTH"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createUser_shouldReturn400_onPasswordTooShort() throws Exception {
        when(builtinUserService.createUser("vendor01", "123"))
                .thenThrow(new IllegalArgumentException("PASSWORD_TOO_SHORT"));

        mockMvc.perform(post("/api/admin/builtin-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "vendor01", "password", "123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PASSWORD_TOO_SHORT"));
    }

    @Test
    void createUser_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/admin/builtin-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "vendor01", "password", "secret123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void createUser_shouldReturn403_whenNotAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/builtin-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "vendor01", "password", "secret123"))))
                .andExpect(status().isForbidden());
    }

    // ========== PUT /{id}/enable — 啟用帳號 ==========

    @Test
    @WithMockUser(roles = "ADMIN")
    void enableUser_shouldReturn200_onSuccess() throws Exception {
        doNothing().when(builtinUserService).enableUser(1L);

        mockMvc.perform(put("/api/admin/builtin-users/1/enable")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void enableUser_shouldReturn404_onNotFound() throws Exception {
        doThrow(new IllegalArgumentException("USER_NOT_FOUND"))
                .when(builtinUserService).enableUser(99L);

        mockMvc.perform(put("/api/admin/builtin-users/99/enable")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    // ========== PUT /{id}/disable — 停用帳號 ==========

    @Test
    @WithMockUser(roles = "ADMIN")
    void disableUser_shouldReturn200_onSuccess() throws Exception {
        doNothing().when(builtinUserService).disableUser(1L);

        mockMvc.perform(put("/api/admin/builtin-users/1/disable")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void disableUser_shouldReturn404_onNotFound() throws Exception {
        doThrow(new IllegalArgumentException("USER_NOT_FOUND"))
                .when(builtinUserService).disableUser(99L);

        mockMvc.perform(put("/api/admin/builtin-users/99/disable")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    // ========== DELETE /{id} — 刪除帳號 ==========

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteUser_shouldReturn200_onSuccess() throws Exception {
        doNothing().when(builtinUserService).deleteUser(1L);

        mockMvc.perform(delete("/api/admin/builtin-users/1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteUser_shouldReturn400_onOnlyAdmin() throws Exception {
        doThrow(new IllegalArgumentException("CANNOT_DELETE_ONLY_ADMIN"))
                .when(builtinUserService).deleteUser(1L);

        mockMvc.perform(delete("/api/admin/builtin-users/1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CANNOT_DELETE_ONLY_ADMIN"));
    }

    // ========== POST /{id}/reset-password — 重設密碼 ==========

    @Test
    @WithMockUser(roles = "ADMIN")
    void resetPassword_shouldReturn200_withTempPassword() throws Exception {
        when(builtinUserService.resetPassword(1L)).thenReturn("aB3xK9mQ");

        mockMvc.perform(post("/api/admin/builtin-users/1/reset-password")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tempPassword").value("aB3xK9mQ"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void resetPassword_shouldReturn404_onNotFound() throws Exception {
        when(builtinUserService.resetPassword(99L))
                .thenThrow(new IllegalArgumentException("USER_NOT_FOUND"));

        mockMvc.perform(post("/api/admin/builtin-users/99/reset-password")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    // ========== POST /forgot-password — 忘記密碼（公開） ==========

    @Test
    void forgotPassword_shouldReturn200_always() throws Exception {
        doNothing().when(builtinUserService).requestPasswordReset(anyString());

        mockMvc.perform(post("/api/admin/builtin-users/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "vendor01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));
    }

    @Test
    void forgotPassword_shouldReturn200_evenForNonExistentUser() throws Exception {
        doNothing().when(builtinUserService).requestPasswordReset(anyString());

        mockMvc.perform(post("/api/admin/builtin-users/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "nonexistent"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));
    }

    // ========== POST /register — 自助註冊（預設停用） ==========

    @Test
    void selfRegister_shouldReturn403_whenDisabled() throws Exception {
        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "newuser", "password", "secret123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SELF_REGISTRATION_DISABLED"));
    }
}
