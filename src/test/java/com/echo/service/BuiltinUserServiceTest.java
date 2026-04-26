package com.echo.service;

import com.echo.entity.BuiltinUser;
import com.echo.repository.BuiltinUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BuiltinUserServiceTest {

    @Mock
    private BuiltinUserRepository builtinUserRepository;

    private BuiltinUserService service;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        service = new BuiltinUserService(builtinUserRepository);
    }

    // ==================== createUser ====================

    @Test
    void createUser_happyPath_shouldSaveWithBCryptAndRoleUser() {
        when(builtinUserRepository.existsByUsername("vendor01")).thenReturn(false);
        when(builtinUserRepository.save(any(BuiltinUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        BuiltinUser result = service.createUser("vendor01", "secret123");

        assertThat(result.getUsername()).isEqualTo("vendor01");
        assertThat(result.getRole()).isEqualTo("ROLE_USER");
        assertThat(result.getEnabled()).isTrue();
        assertThat(passwordEncoder.matches("secret123", result.getPassword())).isTrue();

        verify(builtinUserRepository).save(any(BuiltinUser.class));
    }

    @Test
    void createUser_usernameTooShort_shouldThrow() {
        assertThatThrownBy(() -> service.createUser("ab", "secret123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USERNAME_LENGTH");

        verify(builtinUserRepository, never()).save(any());
    }

    @Test
    void createUser_usernameTooLong_shouldThrow() {
        String longName = "a".repeat(51);

        assertThatThrownBy(() -> service.createUser(longName, "secret123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USERNAME_LENGTH");

        verify(builtinUserRepository, never()).save(any());
    }

    @Test
    void createUser_passwordTooShort_shouldThrow() {
        assertThatThrownBy(() -> service.createUser("vendor01", "12345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PASSWORD_TOO_SHORT");

        verify(builtinUserRepository, never()).save(any());
    }

    @Test
    void createUser_duplicateUsername_shouldThrow() {
        when(builtinUserRepository.existsByUsername("vendor01")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser("vendor01", "secret123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USERNAME_EXISTS");

        verify(builtinUserRepository, never()).save(any());
    }

    // ==================== listUsers ====================

    @Test
    void listUsers_shouldReturnAll() {
        List<BuiltinUser> users = List.of(
                BuiltinUser.builder().username("user1").build(),
                BuiltinUser.builder().username("user2").build()
        );
        when(builtinUserRepository.findAll()).thenReturn(users);

        List<BuiltinUser> result = service.listUsers();

        assertThat(result).hasSize(2);
        verify(builtinUserRepository).findAll();
    }

    // ==================== searchUsers ====================

    @Test
    void searchUsers_withKeyword_shouldCallContaining() {
        List<BuiltinUser> matched = List.of(
                BuiltinUser.builder().username("vendor01").build()
        );
        when(builtinUserRepository.findByUsernameContainingIgnoreCase("vendor"))
                .thenReturn(matched);

        List<BuiltinUser> result = service.searchUsers("vendor");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUsername()).isEqualTo("vendor01");
    }

    @Test
    void searchUsers_withBlankKeyword_shouldReturnAll() {
        List<BuiltinUser> all = List.of(
                BuiltinUser.builder().username("user1").build(),
                BuiltinUser.builder().username("user2").build()
        );
        when(builtinUserRepository.findAll()).thenReturn(all);

        List<BuiltinUser> result = service.searchUsers("  ");

        assertThat(result).hasSize(2);
        verify(builtinUserRepository).findAll();
    }

    // ==================== resetPassword ====================

    @Test
    void resetPassword_happyPath_shouldReturnTempPasswordAndSetFlags() {
        BuiltinUser user = BuiltinUser.builder()
                .id(1L).username("vendor01")
                .password("oldHash")
                .passwordResetRequested(true)
                .forceChangePassword(false)
                .build();
        when(builtinUserRepository.findById(1L)).thenReturn(Optional.of(user));
        when(builtinUserRepository.save(any(BuiltinUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        String tempPassword = service.resetPassword(1L);

        assertThat(tempPassword).hasSize(8);
        assertThat(user.getForceChangePassword()).isTrue();
        assertThat(user.getPasswordResetRequested()).isFalse();
        assertThat(passwordEncoder.matches(tempPassword, user.getPassword())).isTrue();

        verify(builtinUserRepository).save(user);
    }

    @Test
    void resetPassword_userNotFound_shouldThrow() {
        when(builtinUserRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USER_NOT_FOUND");
    }

    // ==================== requestPasswordReset ====================

    @Test
    void requestPasswordReset_existingUser_shouldSetFlag() {
        BuiltinUser user = BuiltinUser.builder()
                .id(1L).username("vendor01")
                .passwordResetRequested(false)
                .build();
        when(builtinUserRepository.findByUsername("vendor01")).thenReturn(Optional.of(user));

        service.requestPasswordReset("vendor01");

        assertThat(user.getPasswordResetRequested()).isTrue();
        assertThat(user.getPasswordResetRequestedAt()).isNotNull();
        verify(builtinUserRepository).save(user);
    }

    @Test
    void requestPasswordReset_nonExistingUser_shouldNotThrow() {
        when(builtinUserRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        // Should not throw — prevents account enumeration
        service.requestPasswordReset("ghost");

        verify(builtinUserRepository, never()).save(any());
    }

    // ==================== changePassword ====================

    @Test
    void changePassword_happyPath_shouldUpdatePasswordAndClearFlag() {
        String oldEncoded = passwordEncoder.encode("oldPass1");
        BuiltinUser user = BuiltinUser.builder()
                .id(1L).username("vendor01")
                .password(oldEncoded)
                .forceChangePassword(true)
                .build();
        when(builtinUserRepository.findByUsername("vendor01")).thenReturn(Optional.of(user));

        service.changePassword("vendor01", "oldPass1", "newPass1");

        assertThat(user.getForceChangePassword()).isFalse();
        assertThat(passwordEncoder.matches("newPass1", user.getPassword())).isTrue();
        verify(builtinUserRepository).save(user);
    }

    @Test
    void changePassword_wrongOldPassword_shouldThrow() {
        String oldEncoded = passwordEncoder.encode("correctPwd");
        BuiltinUser user = BuiltinUser.builder()
                .id(1L).username("vendor01")
                .password(oldEncoded)
                .build();
        when(builtinUserRepository.findByUsername("vendor01")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword("vendor01", "wrongPwd", "newPass1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OLD_PASSWORD_INCORRECT");

        verify(builtinUserRepository, never()).save(any());
    }

    @Test
    void changePassword_newPasswordTooShort_shouldThrow() {
        String oldEncoded = passwordEncoder.encode("oldPass1");
        BuiltinUser user = BuiltinUser.builder()
                .id(1L).username("vendor01")
                .password(oldEncoded)
                .build();
        when(builtinUserRepository.findByUsername("vendor01")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword("vendor01", "oldPass1", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PASSWORD_TOO_SHORT");

        verify(builtinUserRepository, never()).save(any());
    }

    // ==================== enableUser / disableUser ====================

    @Test
    void enableUser_shouldSetEnabledTrue() {
        BuiltinUser user = BuiltinUser.builder()
                .id(1L).username("vendor01").enabled(false).build();
        when(builtinUserRepository.findById(1L)).thenReturn(Optional.of(user));

        service.enableUser(1L);

        assertThat(user.getEnabled()).isTrue();
        verify(builtinUserRepository).save(user);
    }

    @Test
    void disableUser_shouldSetEnabledFalse() {
        BuiltinUser user = BuiltinUser.builder()
                .id(1L).username("vendor01").enabled(true).build();
        when(builtinUserRepository.findById(1L)).thenReturn(Optional.of(user));

        service.disableUser(1L);

        assertThat(user.getEnabled()).isFalse();
        verify(builtinUserRepository).save(user);
    }

    // ==================== deleteUser ====================

    @Test
    void deleteUser_happyPath_shouldDelete() {
        BuiltinUser user = BuiltinUser.builder()
                .id(1L).username("vendor01").role("ROLE_USER").build();
        when(builtinUserRepository.findById(1L)).thenReturn(Optional.of(user));

        service.deleteUser(1L);

        verify(builtinUserRepository).delete(user);
    }

    @Test
    void deleteUser_onlyAdmin_shouldThrow() {
        BuiltinUser admin = BuiltinUser.builder()
                .id(1L).username("admin01").role("ROLE_ADMIN").build();
        when(builtinUserRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(builtinUserRepository.countByRole("ROLE_ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteUser(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CANNOT_DELETE_ONLY_ADMIN");

        verify(builtinUserRepository, never()).delete(any());
    }

    @Test
    void deleteUser_userNotFound_shouldThrow() {
        when(builtinUserRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUser(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USER_NOT_FOUND");
    }
}
