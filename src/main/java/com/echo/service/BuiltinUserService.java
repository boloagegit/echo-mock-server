package com.echo.service;

import com.echo.entity.BuiltinUser;
import com.echo.repository.BuiltinUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 內建帳號管理服務
 * <p>
 * 處理帳號建立、查詢、密碼驗證等核心邏輯。
 * 密碼一律以 BCrypt 雜湊儲存，永不以明文存於資料庫。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuiltinUserService {

    private final BuiltinUserRepository builtinUserRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 建立新的內建帳號
     *
     * @param username 帳號名稱（3-50 字元）
     * @param password 密碼（至少 6 字元）
     * @return 建立的使用者
     * @throws IllegalArgumentException 帳號長度不符、密碼太短、帳號已存在
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
     * 取得所有內建帳號清單
     */
    public List<BuiltinUser> listUsers() {
        return builtinUserRepository.findAll();
    }

    /**
     * 依關鍵字搜尋帳號（模糊比對帳號名稱，不分大小寫）
     *
     * @param keyword 搜尋關鍵字
     * @return 符合條件的使用者清單
     */
    public List<BuiltinUser> searchUsers(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return builtinUserRepository.findAll();
        }
        return builtinUserRepository.findByUsernameContainingIgnoreCase(keyword);
    }

    private static final String TEMP_PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TEMP_PASSWORD_LENGTH = 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 重設指定帳號的密碼
     * <p>
     * 產生 8 字元隨機臨時密碼（包含大小寫字母和數字），以 BCrypt 雜湊儲存，
     * 設定 forceChangePassword=true，清除 passwordResetRequested，回傳臨時密碼明文。
     *
     * @param id 使用者 ID
     * @return 臨時密碼明文
     * @throws IllegalArgumentException 帳號不存在
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
     * 忘記密碼請求
     * <p>
     * 設定 passwordResetRequested=true 與請求時間。
     * 帳號不存在時不拋例外，防止帳號列舉攻擊。
     *
     * @param username 帳號名稱
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
     * 使用者自行修改密碼
     * <p>
     * 驗證舊密碼正確後，將新密碼以 BCrypt 雜湊儲存，並清除 forceChangePassword 標記。
     *
     * @param username    帳號名稱
     * @param oldPassword 舊密碼
     * @param newPassword 新密碼（至少 6 字元）
     * @throws IllegalArgumentException 帳號不存在、舊密碼不正確、新密碼太短
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
     * 啟用帳號
     *
     * @param id 使用者 ID
     * @throws IllegalArgumentException 帳號不存在
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
     * 停用帳號
     *
     * @param id 使用者 ID
     * @throws IllegalArgumentException 帳號不存在
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
     * 刪除帳號
     * <p>
     * 若該帳號為系統中唯一的 Admin，則拒絕刪除。
     *
     * @param id 使用者 ID
     * @throws IllegalArgumentException 帳號不存在或為唯一 Admin
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
     * 驗證帳號密碼
     * <p>
     * 用於屬性測試等場景的密碼驗證輔助方法。
     *
     * @param username 帳號名稱
     * @param password 密碼明文
     * @return true 若帳號存在、已啟用且密碼正確
     */
    public boolean authenticate(String username, String password) {
        return builtinUserRepository.findByUsername(username)
                .filter(BuiltinUser::getEnabled)
                .map(user -> passwordEncoder.matches(password, user.getPassword()))
                .orElse(false);
    }

    /**
     * 更新最後登入時間
     *
     * @param username 帳號名稱
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
