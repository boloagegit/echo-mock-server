package com.echo.service;

import com.echo.entity.BuiltinUser;
import com.echo.repository.BuiltinUserRepository;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BuiltinUserService 屬性測試（jqwik）
 * <p>
 * 使用程式化 H2 資料庫設定，每個 @Property 方法前重建乾淨的資料庫環境。
 * 每次 try 使用 uniqueName() 產生唯一帳號名稱，避免跨 try 衝突。
 * 涵蓋設計文件中的 Property 1-14。
 */
class BuiltinUserServicePropertyTest {

    private BuiltinUserService service;
    private BuiltinUserRepository repository;
    private EntityManagerFactory emf;
    private TransactionTemplate txTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AtomicInteger counter = new AtomicInteger(0);

    /** Generate a unique username by appending a counter to the base name. */
    private String uniqueName(String base) {
        return base + counter.incrementAndGet();
    }

    /** Execute a service call within a transaction. */
    private <T> T inTx(java.util.function.Supplier<T> action) {
        return txTemplate.execute(status -> action.get());
    }

    /** Execute a void service call within a transaction. */
    private void inTxVoid(Runnable action) {
        txTemplate.executeWithoutResult(status -> action.run());
    }

    @BeforeProperty
    void setUp() {
        counter.set(0);

        DataSource dataSource = DataSourceBuilder.create()
                .driverClassName("org.h2.Driver")
                .url("jdbc:h2:mem:testdb_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .build();

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(true);
        vendorAdapter.setShowSql(false);

        LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setJpaVendorAdapter(vendorAdapter);
        factoryBean.setPackagesToScan("com.echo.entity");

        Properties jpaProps = new Properties();
        jpaProps.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        factoryBean.setJpaProperties(jpaProps);
        factoryBean.afterPropertiesSet();

        emf = factoryBean.getObject();

        EntityManager sharedEm = SharedEntityManagerCreator.createSharedEntityManager(emf);
        JpaTransactionManager txManager = new JpaTransactionManager(emf);
        txTemplate = new TransactionTemplate(txManager);

        JpaRepositoryFactory repoFactory = new JpaRepositoryFactory(sharedEm);
        repository = repoFactory.getRepository(BuiltinUserRepository.class);

        service = new BuiltinUserService(repository);
    }

    @AfterProperty
    void tearDown() {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
    }

    // ========== Generators ==========

    @Provide
    Arbitrary<String> validUsernames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .ofMinLength(3)
                .ofMaxLength(10);
    }

    @Provide
    Arbitrary<String> validPasswords() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('!', '@', '#', '$')
                .ofMinLength(6)
                .ofMaxLength(16);
    }

    @Provide
    Arbitrary<String> tooShortUsernames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0)
                .ofMaxLength(2);
    }

    @Provide
    Arbitrary<String> tooLongUsernames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(51)
                .ofMaxLength(60);
    }

    @Provide
    Arbitrary<String> tooShortPasswords() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(5);
    }

    @Provide
    Arbitrary<String> searchKeywords() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(5);
    }

    // ========== Property Tests ==========

    /**
     * Feature: builtin-account-management, Property 1: 帳號建立儲存 BCrypt 密碼並指派 ROLE_USER
     * <p>
     * For any valid username and password, after createUser, the stored password
     * should be BCrypt-verifiable and role should be ROLE_USER.
     *
     * **Validates: Requirements 1.1, 1.5**
     */
    @Property(tries = 50)
    void property1_createUserStoresBCryptPasswordAndAssignsRoleUser(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password) {

        String uname = uniqueName(username);
        BuiltinUser user = inTx(() -> service.createUser(uname, password));

        assertThat(user).isNotNull();
        assertThat(user.getRole()).isEqualTo("ROLE_USER");
        assertThat(user.getPassword()).isNotEqualTo(password);
        assertThat(passwordEncoder.matches(password, user.getPassword())).isTrue();
        assertThat(user.getEnabled()).isTrue();
    }

    /**
     * Feature: builtin-account-management, Property 2: 重複帳號名稱拒絕建立
     * <p>
     * Create a user, then try to create another with the same username
     * → should throw IllegalArgumentException("USERNAME_EXISTS").
     *
     * **Validates: Requirements 1.2**
     */
    @Property(tries = 30)
    void property2_duplicateUsernameRejected(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password1,
            @ForAll("validPasswords") String password2) {

        String uname = uniqueName(username);
        inTx(() -> service.createUser(uname, password1));

        assertThatThrownBy(() -> inTx(() -> service.createUser(uname, password2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USERNAME_EXISTS");
    }

    /**
     * Feature: builtin-account-management, Property 3: 帳號名稱長度驗證
     * <p>
     * For any string with length < 3 or > 50, createUser should throw
     * IllegalArgumentException("USERNAME_LENGTH").
     *
     * **Validates: Requirements 1.3**
     */
    @Property(tries = 50)
    void property3_usernameLengthValidation_tooShort(
            @ForAll("tooShortUsernames") String username,
            @ForAll("validPasswords") String password) {

        assertThatThrownBy(() -> inTx(() -> service.createUser(username, password)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USERNAME_LENGTH");
    }

    @Property(tries = 30)
    void property3_usernameLengthValidation_tooLong(
            @ForAll("tooLongUsernames") String username,
            @ForAll("validPasswords") String password) {

        assertThatThrownBy(() -> inTx(() -> service.createUser(username, password)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USERNAME_LENGTH");
    }

    /**
     * Feature: builtin-account-management, Property 4: 密碼最短長度驗證
     * <p>
     * For any string with length < 6, createUser should throw
     * IllegalArgumentException("PASSWORD_TOO_SHORT").
     * Also test changePassword with short new password.
     *
     * **Validates: Requirements 1.4, 4.5**
     */
    @Property(tries = 50)
    void property4_passwordMinLengthValidation_createUser(
            @ForAll("validUsernames") String username,
            @ForAll("tooShortPasswords") String shortPassword) {

        String uname = uniqueName(username);
        assertThatThrownBy(() -> inTx(() -> service.createUser(uname, shortPassword)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PASSWORD_TOO_SHORT");
    }

    @Property(tries = 30)
    void property4_passwordMinLengthValidation_changePassword(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String originalPassword,
            @ForAll("tooShortPasswords") String shortNewPassword) {

        String uname = uniqueName(username);
        inTx(() -> service.createUser(uname, originalPassword));

        assertThatThrownBy(() -> inTxVoid(() ->
                service.changePassword(uname, originalPassword, shortNewPassword)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PASSWORD_TOO_SHORT");
    }

    /**
     * Feature: builtin-account-management, Property 5: 認證結果與密碼匹配一致性
     * <p>
     * For any created user, authenticate(username, correctPassword) returns true,
     * authenticate(username, wrongPassword) returns false.
     *
     * **Validates: Requirements 2.1, 2.2**
     */
    @Property(tries = 30)
    void property5_authenticationConsistency(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password) {

        String uname = uniqueName(username);
        inTx(() -> service.createUser(uname, password));

        boolean correctAuth = inTx(() -> service.authenticate(uname, password));
        boolean wrongAuth = inTx(() -> service.authenticate(uname, password + "WRONG"));
        boolean nonExistentAuth = inTx(() -> service.authenticate("nonexistent_xyz", password));

        assertThat(correctAuth).isTrue();
        assertThat(wrongAuth).isFalse();
        assertThat(nonExistentAuth).isFalse();
    }

    /**
     * Feature: builtin-account-management, Property 6: 停用帳號拒絕認證
     * <p>
     * For any created and then disabled user, authenticate should return false
     * even with correct password.
     *
     * **Validates: Requirements 2.5**
     */
    @Property(tries = 30)
    void property6_disabledUserCannotAuthenticate(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password) {

        String uname = uniqueName(username);
        BuiltinUser user = inTx(() -> service.createUser(uname, password));
        inTxVoid(() -> service.disableUser(user.getId()));

        boolean auth = inTx(() -> service.authenticate(uname, password));
        assertThat(auth).isFalse();
    }

    /**
     * Feature: builtin-account-management, Property 7: 搜尋結果僅包含匹配帳號
     * <p>
     * For any keyword, all returned users from searchUsers should have username
     * containing the keyword (case-insensitive).
     *
     * **Validates: Requirements 3.5**
     */
    @Property(tries = 30)
    void property7_searchResultsContainOnlyMatchingUsers(
            @ForAll("searchKeywords") String keyword) {

        int id = counter.incrementAndGet();
        inTx(() -> service.createUser("match" + id + keyword + "tail", "password123"));
        inTx(() -> service.createUser("nomtch" + id + "zzz", "password123"));

        List<BuiltinUser> results = inTx(() -> service.searchUsers(keyword));

        for (BuiltinUser user : results) {
            assertThat(user.getUsername().toLowerCase(Locale.ROOT))
                    .contains(keyword.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Feature: builtin-account-management, Property 8: 密碼重設產生有效臨時密碼並更新標記
     * <p>
     * After resetPassword, the returned temp password should BCrypt-match the stored hash,
     * passwordResetRequested should be false, forceChangePassword should be true.
     *
     * **Validates: Requirements 4.1, 4.3, 4.4**
     */
    @Property(tries = 30)
    void property8_resetPasswordProducesValidTempPassword(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password) {

        String uname = uniqueName(username);
        BuiltinUser user = inTx(() -> service.createUser(uname, password));

        inTxVoid(() -> service.requestPasswordReset(uname));

        String tempPassword = inTx(() -> service.resetPassword(user.getId()));

        assertThat(tempPassword).isNotNull();
        assertThat(tempPassword).hasSize(8);

        BuiltinUser updated = inTx(() -> repository.findByUsername(uname).orElseThrow());
        assertThat(passwordEncoder.matches(tempPassword, updated.getPassword())).isTrue();
        assertThat(updated.getPasswordResetRequested()).isFalse();
        assertThat(updated.getForceChangePassword()).isTrue();
    }

    /**
     * Feature: builtin-account-management, Property 9: 忘記密碼回應不揭露帳號存在性
     * <p>
     * requestPasswordReset should not throw for non-existent usernames.
     *
     * **Validates: Requirements 5.2, 5.3**
     */
    @Property(tries = 50)
    void property9_forgotPasswordDoesNotRevealAccountExistence(
            @ForAll("validUsernames") String nonExistentUsername) {

        String uname = uniqueName(nonExistentUsername);
        // Should not throw any exception for non-existent usernames
        inTxVoid(() -> service.requestPasswordReset(uname));
    }

    /**
     * Feature: builtin-account-management, Property 10: 啟用/停用狀態切換 round-trip
     * <p>
     * After disableUser, enabled should be false. After enableUser, enabled should be true.
     *
     * **Validates: Requirements 6.1, 6.2**
     */
    @Property(tries = 30)
    void property10_enableDisableRoundTrip(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password) {

        String uname = uniqueName(username);
        BuiltinUser user = inTx(() -> service.createUser(uname, password));
        assertThat(user.getEnabled()).isTrue();

        inTxVoid(() -> service.disableUser(user.getId()));
        BuiltinUser disabled = inTx(() -> repository.findById(user.getId()).orElseThrow());
        assertThat(disabled.getEnabled()).isFalse();

        inTxVoid(() -> service.enableUser(user.getId()));
        BuiltinUser enabled = inTx(() -> repository.findById(user.getId()).orElseThrow());
        assertThat(enabled.getEnabled()).isTrue();
    }

    /**
     * Feature: builtin-account-management, Property 11: 刪除帳號移除記錄
     * <p>
     * After deleteUser, the user should not exist in the repository.
     *
     * **Validates: Requirements 6.3**
     */
    @Property(tries = 30)
    void property11_deleteUserRemovesRecord(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password) {

        String uname = uniqueName(username);
        BuiltinUser user = inTx(() -> service.createUser(uname, password));

        boolean exists = Boolean.TRUE.equals(
                inTx(() -> repository.findById(user.getId()).isPresent()));
        assertThat(exists).isTrue();

        inTxVoid(() -> service.deleteUser(user.getId()));

        boolean existsAfter = Boolean.TRUE.equals(
                inTx(() -> repository.findById(user.getId()).isPresent()));
        assertThat(existsAfter).isFalse();

        boolean existsByName = Boolean.TRUE.equals(
                inTx(() -> repository.existsByUsername(uname)));
        assertThat(existsByName).isFalse();
    }

    /**
     * Feature: builtin-account-management, Property 12: 修改密碼 round-trip
     * <p>
     * After changePassword with correct old password and valid new password,
     * authenticate with new password should return true.
     *
     * **Validates: Requirements 7.1**
     */
    @Property(tries = 30)
    void property12_changePasswordRoundTrip(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String oldPassword,
            @ForAll("validPasswords") String newPassword) {

        String uname = uniqueName(username);
        inTx(() -> service.createUser(uname, oldPassword));

        inTxVoid(() -> service.changePassword(uname, oldPassword, newPassword));

        boolean newAuth = inTx(() -> service.authenticate(uname, newPassword));
        assertThat(newAuth).isTrue();

        if (!oldPassword.equals(newPassword)) {
            boolean oldAuth = inTx(() -> service.authenticate(uname, oldPassword));
            assertThat(oldAuth).isFalse();
        }
    }

    /**
     * Feature: builtin-account-management, Property 13: 錯誤舊密碼拒絕修改
     * <p>
     * changePassword with wrong old password should throw
     * IllegalArgumentException("OLD_PASSWORD_INCORRECT").
     *
     * **Validates: Requirements 7.2**
     */
    @Property(tries = 30)
    void property13_wrongOldPasswordRejected(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String correctPassword,
            @ForAll("validPasswords") String newPassword) {

        String uname = uniqueName(username);
        inTx(() -> service.createUser(uname, correctPassword));

        String wrongOldPassword = correctPassword + "WRONG";
        assertThatThrownBy(() -> inTxVoid(() ->
                service.changePassword(uname, wrongOldPassword, newPassword)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OLD_PASSWORD_INCORRECT");

        boolean auth = inTx(() -> service.authenticate(uname, correctPassword));
        assertThat(auth).isTrue();
    }

    /**
     * Feature: builtin-account-management, Property 14: 修改密碼後清除強制修改標記
     * <p>
     * For a user with forceChangePassword=true (after resetPassword),
     * changePassword should set forceChangePassword=false.
     *
     * **Validates: Requirements 7.3**
     */
    @Property(tries = 30)
    void property14_changePasswordClearsForceChangeFlag(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String originalPassword,
            @ForAll("validPasswords") String newPassword) {

        String uname = uniqueName(username);
        BuiltinUser user = inTx(() -> service.createUser(uname, originalPassword));

        String tempPassword = inTx(() -> service.resetPassword(user.getId()));

        BuiltinUser afterReset = inTx(() -> repository.findByUsername(uname).orElseThrow());
        assertThat(afterReset.getForceChangePassword()).isTrue();

        inTxVoid(() -> service.changePassword(uname, tempPassword, newPassword));

        BuiltinUser afterChange = inTx(() -> repository.findByUsername(uname).orElseThrow());
        assertThat(afterChange.getForceChangePassword()).isFalse();

        boolean auth = inTx(() -> service.authenticate(uname, newPassword));
        assertThat(auth).isTrue();
    }
}
