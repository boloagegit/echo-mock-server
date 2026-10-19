package com.echo.entity;

import com.echo.repository.BuiltinUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BuiltinUser Entity unit test
 * <p>
 * Testing JPA lifecycle callback (@PrePersist) with unique constraints.
 * Validates: Requirements 8.2
 */
@DataJpaTest
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
        // Given: Create a user first
        BuiltinUser user1 = BuiltinUser.builder()
                .username("duplicate")
                .password("hashedpwd1")
                .build();
        builtinUserRepository.saveAndFlush(user1);

        // When/Then: Creating a second user with the same username should throw an exception
        BuiltinUser user2 = BuiltinUser.builder()
                .username("duplicate")
                .password("hashedpwd2")
                .build();

        assertThatThrownBy(() -> builtinUserRepository.saveAndFlush(user2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
