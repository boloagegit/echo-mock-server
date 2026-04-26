package com.echo.entity;

import com.echo.repository.BuiltinUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BuiltinUser Entity 單元測試
 * <p>
 * 測試 JPA 生命週期回呼（@PrePersist）與唯一約束。
 * Validates: Requirements 8.2
 */
@DataJpaTest
@AutoConfigureTestDatabase
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class BuiltinUserTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BuiltinUserRepository builtinUserRepository;

    @Test
    void prePersist_shouldAutoFillCreatedAtAndUpdatedAt() {
        // Given
        BuiltinUser user = BuiltinUser.builder()
                .username("testuser")
                .password("hashedpwd")
                .build();

        // When
        BuiltinUser saved = entityManager.persistAndFlush(user);

        // Then
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isEqualTo(saved.getUpdatedAt());
    }

    @Test
    void uniqueConstraint_shouldRejectDuplicateUsername() {
        // Given: 先建立一個使用者
        BuiltinUser user1 = BuiltinUser.builder()
                .username("duplicate")
                .password("hashedpwd1")
                .build();
        builtinUserRepository.saveAndFlush(user1);

        // When/Then: 以相同 username 建立第二個使用者應拋出例外
        BuiltinUser user2 = BuiltinUser.builder()
                .username("duplicate")
                .password("hashedpwd2")
                .build();

        assertThatThrownBy(() -> builtinUserRepository.saveAndFlush(user2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
