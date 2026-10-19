package com.echo.service;

import com.echo.entity.BuiltinUser;
import com.echo.repository.BuiltinUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Built-in account management service
 * <p>
 * Handles core logic such as account creation, query, password verification, etc.
 * Passwords are always stored as BCrypt hash and are never stored in the database in clear text.
 */
@Service
@Slf4j
public class BuiltinUserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "username", "role", "enabled", "createdAt", "updatedAt", "lastLoginAt");

    private final BuiltinUserRepository builtinUserRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Autowired
    public BuiltinUserService(BuiltinUserRepository builtinUserRepository) {
        this(builtinUserRepository, new BCryptPasswordEncoder());
    }

    /** For testing: You can use the lower strength BCrypt instead. The hash format has the same behavior as the verification, but is faster. */
    BuiltinUserService(BuiltinUserRepository builtinUserRepository, BCryptPasswordEncoder passwordEncoder) {
        this.builtinUserRepository = builtinUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Create new built-in account
     *
     * @param username Account name (3-50 characters)
     * @param password Password (at least 6 characters)
     * @return User created
     * @throws IllegalArgumentException Account length does not match, password is too short, account already exists
     */
    @Transactional
    public BuiltinUser createUser(String username, String password) {
        if (username == null || username.length() < 3 || username.length() > 50) {
            throw new IllegalArgumentException("USERNAME_LENGTH");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("PASSWORD_TOO_SHORT");
        }
        if (builtinUserRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("USERNAME_EXISTS");
        }

        BuiltinUser user = BuiltinUser.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .role("ROLE_USER")
                .enabled(true)
                .build();

        BuiltinUser saved = builtinUserRepository.save(user);
        log.info("Created builtin user: {}", saved.getUsername());
        return saved;
    }

    /**
     * Get a list of all built-in accounts
     */
    public List<BuiltinUser> listUsers() {
        return builtinUserRepository.findAll();
    }

    /**
     * Search accounts by keywords (fuzzy comparison of account names, not case sensitive)
     *
     * @param keyword Search keyword
     * @return List of eligible users
     */
    public List<BuiltinUser> searchUsers(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return builtinUserRepository.findAll();
        }
        return builtinUserRepository.findByUsernameContainingIgnoreCase(keyword);
    }

    /** Server-side account query used by the administration table. */
    public Page<BuiltinUser> queryUsers(String keyword, String role, Boolean enabled,
                                        Boolean passwordResetRequested, int page, int size,
                                        String sortField, String direction) {
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        String safeSort = ALLOWED_SORT_FIELDS.contains(sortField) ? sortField : "username";
        Sort.Direction safeDirection = "desc".equalsIgnoreCase(direction)
                ? Sort.Direction.DESC : Sort.Direction.ASC;

        PageRequest request = PageRequest.of(boundedPage, boundedSize,
                Sort.by(safeDirection, safeSort).and(Sort.by(Sort.Direction.ASC, "id")));
        String keywordPattern = keyword == null || keyword.isBlank() ? null
                : "%" + escapeLike(keyword.trim().toLowerCase(java.util.Locale.ROOT)) + "%";
        String roleFilter = role == null || role.isBlank() ? null : role;
        return builtinUserRepository.queryUsers(keywordPattern, roleFilter, enabled,
                passwordResetRequested, request);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static final String TEMP_PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TEMP_PASSWORD_LENGTH = 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Reset the password of the specified account
     * <p>
     * Generate an 8-character random temporary password (including uppercase and lowercase letters and numbers), and store it in BCrypt hash,
     * Set forceChangePassword=true, clear passwordResetRequested, and return the temporary password plaintext.
     *
     * @param id User ID
     * @return Temporary password plain text
     * @throws IllegalArgumentException Account does not exist
     */
    @Transactional
    public String resetPassword(Long id) {
        BuiltinUser user = builtinUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("USER_NOT_FOUND"));

        String tempPassword = generateTempPassword();
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setForceChangePassword(true);
        user.setPasswordResetRequested(false);
        builtinUserRepository.save(user);

        log.info("Password reset for user: {}", user.getUsername());
        return tempPassword;
    }

    /**
     * Forgot password request
     * <p>
     * Set passwordResetRequested=true and request time.
     * Do not throw an exception when the account does not exist to prevent account enumeration attacks.
     *
     * @param username Account name
     */
    @Transactional
    public void requestPasswordReset(String username) {
        builtinUserRepository.findByUsername(username).ifPresent(user -> {
            user.setPasswordResetRequested(true);
            user.setPasswordResetRequestedAt(LocalDateTime.now());
            builtinUserRepository.save(user);
            log.info("Password reset requested for user: {}", user.getUsername());
        });
    }

    /**
     * User can change password by himself
     * <p>
     * After verifying that the old password is correct, store the new password as BCrypt hash and clear the forceChangePassword mark.
     *
     * @param username    Account name
     * @param oldPassword old password
     * @param newPassword New password (at least 6 characters)
     * @throws IllegalArgumentException Account does not exist, old password is incorrect, new password is too short
     */
    @Transactional
    public void changePassword(String username, String oldPassword, String newPassword) {
        BuiltinUser user = builtinUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("USER_NOT_FOUND"));

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("OLD_PASSWORD_INCORRECT");
        }

        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("PASSWORD_TOO_SHORT");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setForceChangePassword(false);
        builtinUserRepository.save(user);

        log.info("Password changed for user: {}", user.getUsername());
    }

    /**
     * Activate account
     *
     * @param id User ID
     * @throws IllegalArgumentException Account does not exist
     */
    @Transactional
    public void enableUser(Long id) {
        BuiltinUser user = builtinUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("USER_NOT_FOUND"));
        user.setEnabled(true);
        builtinUserRepository.save(user);
        log.info("Enabled builtin user: {}", user.getUsername());
    }

    /**
     * Account deactivated
     *
     * @param id User ID
     * @throws IllegalArgumentException Account does not exist
     */
    @Transactional
    public void disableUser(Long id) {
        BuiltinUser user = builtinUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("USER_NOT_FOUND"));
        user.setEnabled(false);
        builtinUserRepository.save(user);
        log.info("Disabled builtin user: {}", user.getUsername());
    }

    /**
     * Delete account
     * <p>
     * If the account is the only Admin in the system, the deletion will be refused.
     *
     * @param id User ID
     * @throws IllegalArgumentException The account does not exist or is unique Admin
     */
    @Transactional
    public void deleteUser(Long id) {
        BuiltinUser user = builtinUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("USER_NOT_FOUND"));

        if ("ROLE_ADMIN".equals(user.getRole()) && builtinUserRepository.countByRole("ROLE_ADMIN") == 1) {
            throw new IllegalArgumentException("CANNOT_DELETE_ONLY_ADMIN");
        }

        builtinUserRepository.delete(user);
        log.info("Deleted builtin user: {}", user.getUsername());
    }

    /**
     * Verify account password
     * <p>
     * Password verification auxiliary method for attribute testing and other scenarios.
     *
     * @param username Account name
     * @param password Password plain text
     * @return true If the account exists, is enabled and the password is correct
     */
    public boolean authenticate(String username, String password) {
        return builtinUserRepository.findByUsername(username)
                .filter(BuiltinUser::getEnabled)
                .map(user -> passwordEncoder.matches(password, user.getPassword()))
                .orElse(false);
    }

    /**
     * Update last login time
     *
     * @param username Account name
     */
    @Transactional
    public void updateLastLogin(String username) {
        builtinUserRepository.findByUsername(username).ifPresent(user -> {
            user.setLastLoginAt(LocalDateTime.now());
            builtinUserRepository.save(user);
        });
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_PASSWORD_CHARS.charAt(SECURE_RANDOM.nextInt(TEMP_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
