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
 * Built-in account management integration test
 * <p>
 * Use @SpringBootTest + @AutoConfigureMockMvc to test the complete end-to-end process,
 * Contains authentication, API permission control, database persistence and unique index.
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

    // ========== Test 1: Log in successfully after creating an account ==========

    @Test
    @DisplayName("A newly created built-in account can log in with its credentials")
    void createUserThenLogin_shouldSucceed() throws Exception {
        // Create an account through API (as admin)
        mockMvc.perform(post("/api/admin/builtin-users")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "vendor01", "password", "secret123"))))
                .andExpect(status().isCreated());

        // Use the new account to log in via form login
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "vendor01")
                        .param("password", "secret123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    // ========== Test 2: Login failed after deactivating the account ==========

    @Test
    @DisplayName("A disabled account cannot log in")
    void disabledUser_loginShouldFail() throws Exception {
        // Create account
        BuiltinUser user = builtinUserService.createUser("vendor02", "secret123");

        // Account deactivated
        builtinUserService.disableUser(user.getId());

        // Attempt to log in - should fail
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "vendor02")
                        .param("password", "secret123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login.html?error=true"));
    }

    // ========== Test 3: Appear in the list after creating an account API ==========

    @Test
    @DisplayName("The account-list API includes newly created accounts")
    void createUser_shouldAppearInListApi() throws Exception {
        // Create account
        builtinUserService.createUser("vendor03", "secret123");

        // Query the list as admin
        mockMvc.perform(get("/api/admin/builtin-users")
                        .with(user("admin").roles("ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("vendor03"))
                .andExpect(jsonPath("$[0].role").value("ROLE_USER"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    @DisplayName("The paginated account API supports search, status filters, and sorting")
    void accountPage_shouldFilterSortAndPaginate() throws Exception {
        BuiltinUser alpha = builtinUserService.createUser("alpha-user", "secret123");
        builtinUserService.createUser("beta-user", "secret123");
        builtinUserService.createUser("gamma-admin", "secret123");
        builtinUserService.disableUser(alpha.getId());

        mockMvc.perform(get("/api/admin/builtin-users/page")
                        .with(user("admin").roles("ADMIN"))
                        .param("keyword", "user")
                        .param("enabled", "true")
                        .param("page", "0")
                        .param("size", "1")
                        .param("sort", "username")
                        .param("direction", "desc")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.results[0].username").value("beta-user"))
                .andExpect(jsonPath("$.results[0].password").doesNotExist());
    }

    // ========== Test 4: Complete password reset process ==========

    @Test
    @DisplayName("Password reset: create an account, reset its password, log in with the temporary password, then force a password change")
    void fullPasswordResetFlow() throws Exception {
        // 1. Create an account
        BuiltinUser user = builtinUserService.createUser("vendor04", "original123");

        // 2. Reset password (obtain temporary password)
        MvcResult resetResult = mockMvc.perform(
                        post("/api/admin/builtin-users/" + user.getId() + "/reset-password")
                                .with(user("admin").roles("ADMIN"))
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tempPassword").isNotEmpty())
                .andReturn();

        String tempPassword = objectMapper.readTree(resetResult.getResponse().getContentAsString())
                .get("tempPassword").asText();

        // 3. Verify that forceChangePassword has been set to true
        BuiltinUser afterReset = builtinUserRepository.findByUsername("vendor04").orElseThrow();
        assertThat(afterReset.getForceChangePassword()).isTrue();
        assertThat(afterReset.getPasswordResetRequested()).isFalse();

        // 4. Login with temporary password should be successful
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "vendor04")
                        .param("password", tempPassword)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        // 5. Force password change
        mockMvc.perform(put("/api/account/change-password")
                        .with(user("vendor04").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", tempPassword, "newPassword", "newPass123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));

        // 6. Verify forceChangePassword cleared
        BuiltinUser afterChange = builtinUserRepository.findByUsername("vendor04").orElseThrow();
        assertThat(afterChange.getForceChangePassword()).isFalse();

        // 7. Login with new password should be successful
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "vendor04")
                        .param("password", "newPass123")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    // ========== Test 5: Forgot password API Do not reveal the existence of the account ==========

    @Test
    @DisplayName("The forgot-password API returns 200 for both existing and nonexistent accounts")
    void forgotPassword_shouldReturn200ForBothExistingAndNonExisting() throws Exception {
        // Create an account
        builtinUserService.createUser("vendor05", "secret123");

        // Send a forgotten password request to an existing account
        mockMvc.perform(post("/api/admin/builtin-users/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "vendor05"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));

        // Sending a forgotten password request to a non-existent account - the response format should be exactly the same
        mockMvc.perform(post("/api/admin/builtin-users/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "nonexistent"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));

        // Verify that the existing account has been configured passwordResetRequested
        BuiltinUser user = builtinUserRepository.findByUsername("vendor05").orElseThrow();
        assertThat(user.getPasswordResetRequested()).isTrue();
        assertThat(user.getPasswordResetRequestedAt()).isNotNull();
    }

    // ========== Test 6: Non-Admin cannot access account management API ==========

    @Test
    @DisplayName("Non-admin users cannot access /api/admin/builtin-users")
    void nonAdminUser_cannotAccessBuiltinUsersApi() throws Exception {
        // Not certified → 401
        mockMvc.perform(get("/api/admin/builtin-users")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // USER role → 403
        mockMvc.perform(get("/api/admin/builtin-users")
                        .with(user("regularuser").roles("USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // USER character tries to create an account → 403
        mockMvc.perform(post("/api/admin/builtin-users")
                        .with(user("regularuser").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "hacker", "password", "secret123"))))
                .andExpect(status().isForbidden());
    }

    // ========== Test 7: Logged in users can change their password ==========

    @Test
    @DisplayName("Signed-in built-in users can change their password through the change-password API")
    void authenticatedUser_canChangePassword() throws Exception {
        // Create account
        builtinUserService.createUser("vendor07", "oldPass123");

        // Change password
        mockMvc.perform(put("/api/account/change-password")
                        .with(user("vendor07").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", "oldPass123", "newPassword", "newPass456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"));

        // Verify that the new password has been saved as BCrypt
        BuiltinUser updated = builtinUserRepository.findByUsername("vendor07").orElseThrow();
        assertThat(bCryptEncoder.matches("newPass456", updated.getPassword())).isTrue();
        assertThat(bCryptEncoder.matches("oldPass123", updated.getPassword())).isFalse();

        // Login with new password should succeed
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "vendor07")
                        .param("password", "newPass456")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    // ========== Test 8: Database unique account name constraint ==========

    @Test
    @DisplayName("The database username uniqueness constraint rejects duplicate accounts")
    void uniqueUsernameConstraint_shouldPreventDuplicates() throws Exception {
        // Create your first account
        builtinUserService.createUser("uniqueuser", "secret123");

        // Attempt to create an account with the same name - should be rejected by layer Service
        mockMvc.perform(post("/api/admin/builtin-users")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "uniqueuser", "password", "another123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("USERNAME_EXISTS"));

        // There is only one record in the verification database
        assertThat(builtinUserRepository.findAll()).hasSize(1);
    }

    // ========== Test 9: Self-service registration is disabled by default ==========

    @Test
    @DisplayName("Self-registration is disabled by default and returns 403")
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
