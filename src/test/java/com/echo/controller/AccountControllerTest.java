package com.echo.controller;

import com.echo.config.LdapConfig;
import com.echo.config.SecurityConfig;
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

import java.util.Map;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AccountController 單元測試
 * <p>
 * 測試使用者自行修改密碼端點的正常流程、錯誤處理與權限控制。
 */
@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, LdapConfig.class})
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BuiltinUserService builtinUserService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private BuiltinUserRepository builtinUserRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========== PUT /api/account/change-password ==========

    @Test
    @WithMockUser(username = "vendor01", roles = "USER")
    void changePassword_shouldReturn200_onSuccess() throws Exception {
        doNothing().when(builtinUserService)
                .changePassword("vendor01", "oldPwd123", "newPwd456");

        mockMvc.perform(put("/api/account/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", "oldPwd123", "newPassword", "newPwd456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));
    }

    @Test
    @WithMockUser(username = "vendor01", roles = "USER")
    void changePassword_shouldReturn400_onWrongOldPassword() throws Exception {
        doThrow(new IllegalArgumentException("OLD_PASSWORD_INCORRECT"))
                .when(builtinUserService)
                .changePassword("vendor01", "wrongPwd", "newPwd456");

        mockMvc.perform(put("/api/account/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", "wrongPwd", "newPassword", "newPwd456"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("OLD_PASSWORD_INCORRECT"));
    }

    @Test
    @WithMockUser(username = "vendor01", roles = "USER")
    void changePassword_shouldReturn400_onShortNewPassword() throws Exception {
        doThrow(new IllegalArgumentException("PASSWORD_TOO_SHORT"))
                .when(builtinUserService)
                .changePassword("vendor01", "oldPwd123", "123");

        mockMvc.perform(put("/api/account/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", "oldPwd123", "newPassword", "123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PASSWORD_TOO_SHORT"));
    }

    @Test
    void changePassword_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(put("/api/account/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", "oldPwd123", "newPassword", "newPwd456"))))
                .andExpect(status().isUnauthorized());
    }
}
