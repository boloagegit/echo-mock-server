package com.echo.integration;

import com.echo.entity.BuiltinUser;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.BuiltinUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 內建帳號管理整合測試
 * <p>
 * 使用 @SpringBootTest + @AutoConfigureMockMvc 測試完整的端對端流程，
 * 包含認證、API 權限控制、H2 資料庫持久化與唯一索引。
 * <p>
 * Requirements: 2.1-2.5, 3.1-3.5, 4.1-4.5
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BuiltinAccountIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BuiltinUserService builtinUserService;

    @Autowired
    private BuiltinUserRepository builtinUserRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BCryptPasswordEncoder bCryptEncoder = new BCryptPasswordEncoder();

    @AfterEach
    void cleanup() {
        builtinUserRepository.deleteAll();
    }

    // ========== Test 1: 建立帳號後登入成功 ==========

    @Test
    @DisplayName("建立內建帳號後，使用該帳號密碼登入應成功")
    void createUserThenLogin_shouldSucceed() throws Exception {
        // 透過 API 建立帳號（以 admin 身份）
        mockMvc.perform(post("/api/admin/builtin-users")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "vendor01", "password", "secret123"))))
                .andExpect(status().isCreated());

        // 使用新建帳號透過 form login 登入
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "vendor01")
                        .param("password", "secret123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    // ========== Test 2: 停用帳號後登入失敗 ==========

    @Test
    @DisplayName("停用帳號後，使用該帳號登入應失敗")
    void disabledUser_loginShouldFail() throws Exception {
        // 建立帳號
        BuiltinUser user = builtinUserService.createUser("vendor02", "secret123");

        // 停用帳號
        builtinUserService.disableUser(user.getId());

        // 嘗試登入 — 應失敗
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "vendor02")
                        .param("password", "secret123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login.html?error=true"));
    }

    // ========== Test 3: 建立帳號後出現在清單 API ==========

    @Test
    @DisplayName("建立帳號後，帳號清單 API 應包含該帳號")
    void createUser_shouldAppearInListApi() throws Exception {
        // 建立帳號
        builtinUserService.createUser("vendor03", "secret123");

        // 以 admin 身份查詢清單
        mockMvc.perform(get("/api/admin/builtin-users")
                        .with(user("admin").roles("ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("vendor03"))
                .andExpect(jsonPath("$[0].role").value("ROLE_USER"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    // ========== Test 4: 完整密碼重設流程 ==========

    @Test
    @DisplayName("密碼重設流程：建立帳號 → 重設密碼 → 用臨時密碼登入 → 強制改密碼")
    void fullPasswordResetFlow() throws Exception {
        // 1. 建立帳號
        BuiltinUser user = builtinUserService.createUser("vendor04", "original123");

        // 2. 重設密碼（取得臨時密碼）
        MvcResult resetResult = mockMvc.perform(
                        post("/api/admin/builtin-users/" + user.getId() + "/reset-password")
                                .with(user("admin").roles("ADMIN"))
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tempPassword").isNotEmpty())
                .andReturn();

        String tempPassword = objectMapper.readTree(resetResult.getResponse().getContentAsString())
                .get("tempPassword").asText();

        // 3. 驗證 forceChangePassword 已設為 true
        BuiltinUser afterReset = builtinUserRepository.findByUsername("vendor04").orElseThrow();
        assertThat(afterReset.getForceChangePassword()).isTrue();
        assertThat(afterReset.getPasswordResetRequested()).isFalse();

        // 4. 用臨時密碼登入應成功
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "vendor04")
                        .param("password", tempPassword)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        // 5. 強制改密碼
        mockMvc.perform(put("/api/account/change-password")
                        .with(user("vendor04").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", tempPassword, "newPassword", "newPass123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));

        // 6. 驗證 forceChangePassword 已清除
        BuiltinUser afterChange = builtinUserRepository.findByUsername("vendor04").orElseThrow();
        assertThat(afterChange.getForceChangePassword()).isFalse();

        // 7. 用新密碼登入應成功
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "vendor04")
                        .param("password", "newPass123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    // ========== Test 5: 忘記密碼 API 不揭露帳號存在性 ==========

    @Test
    @DisplayName("忘記密碼 API 對存在與不存在的帳號都回傳 200")
    void forgotPassword_shouldReturn200ForBothExistingAndNonExisting() throws Exception {
        // 建立一個帳號
        builtinUserService.createUser("vendor05", "secret123");

        // 對存在的帳號送忘記密碼請求
        mockMvc.perform(post("/api/admin/builtin-users/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "vendor05"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));

        // 對不存在的帳號送忘記密碼請求 — 回應格式應完全相同
        mockMvc.perform(post("/api/admin/builtin-users/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "nonexistent"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));

        // 驗證存在的帳號已設定 passwordResetRequested
        BuiltinUser user = builtinUserRepository.findByUsername("vendor05").orElseThrow();
        assertThat(user.getPasswordResetRequested()).isTrue();
        assertThat(user.getPasswordResetRequestedAt()).isNotNull();
    }

    // ========== Test 6: 非 Admin 無法存取帳號管理 API ==========

    @Test
    @DisplayName("非 Admin 使用者無法存取 /api/admin/builtin-users")
    void nonAdminUser_cannotAccessBuiltinUsersApi() throws Exception {
        // 未認證 → 401
        mockMvc.perform(get("/api/admin/builtin-users")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // USER 角色 → 403
        mockMvc.perform(get("/api/admin/builtin-users")
                        .with(user("regularuser").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // USER 角色嘗試建立帳號 → 403
        mockMvc.perform(post("/api/admin/builtin-users")
                        .with(user("regularuser").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "hacker", "password", "secret123"))))
                .andExpect(status().isForbidden());
    }

    // ========== Test 7: 已登入使用者可修改密碼 ==========

    @Test
    @DisplayName("已登入的內建帳號使用者可透過 change-password API 修改密碼")
    void authenticatedUser_canChangePassword() throws Exception {
        // 建立帳號
        builtinUserService.createUser("vendor07", "oldPass123");

        // 修改密碼
        mockMvc.perform(put("/api/account/change-password")
                        .with(user("vendor07").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", "oldPass123", "newPassword", "newPass456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));

        // 驗證新密碼已儲存為 BCrypt
        BuiltinUser updated = builtinUserRepository.findByUsername("vendor07").orElseThrow();
        assertThat(bCryptEncoder.matches("newPass456", updated.getPassword())).isTrue();
        assertThat(bCryptEncoder.matches("oldPass123", updated.getPassword())).isFalse();

        // 用新密碼登入應成功
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "vendor07")
                        .param("password", "newPass456")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    // ========== Test 8: 資料庫唯一帳號名稱約束 ==========

    @Test
    @DisplayName("資料庫層級的 username 唯一約束應阻止重複帳號")
    void uniqueUsernameConstraint_shouldPreventDuplicates() throws Exception {
        // 建立第一個帳號
        builtinUserService.createUser("uniqueuser", "secret123");

        // 嘗試建立同名帳號 — 應被 Service 層拒絕
        mockMvc.perform(post("/api/admin/builtin-users")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "uniqueuser", "password", "another123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("USERNAME_EXISTS"));

        // 驗證資料庫中只有一筆記錄
        assertThat(builtinUserRepository.findAll()).hasSize(1);
    }

    // ========== Test 9: 自助註冊預設停用 ==========

    @Test
    @DisplayName("自助註冊預設停用，應回傳 403")
    void selfRegister_shouldReturn403_whenDisabledByDefault() throws Exception {
        mockMvc.perform(post("/api/admin/builtin-users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "selfuser", "password", "secret123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SELF_REGISTRATION_DISABLED"));
    }
}
