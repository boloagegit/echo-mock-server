package com.echo.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JmsCredentialCipherTest {

    @Test
    void encryptsWithRandomIvAndDecryptsWithoutExposingPlaintext() {
        var environment = new MockEnvironment().withProperty("echo.jms.credential-key", "test-key-123");
        var cipher = new JmsCredentialCipher(environment);

        String first = cipher.encrypt("密碼-secret");
        String second = cipher.encrypt("密碼-secret");

        assertThat(first).startsWith("v1:").doesNotContain("secret");
        assertThat(second).isNotEqualTo(first);
        assertThat(cipher.decrypt(first)).isEqualTo("密碼-secret");
        assertThat(cipher.decrypt(second)).isEqualTo("密碼-secret");
    }

    @Test
    void rejectsCiphertextEncryptedWithAnotherKey() {
        var first = new JmsCredentialCipher(
                new MockEnvironment().withProperty("echo.jms.credential-key", "first-key"));
        var second = new JmsCredentialCipher(
                new MockEnvironment().withProperty("echo.jms.credential-key", "second-key"));

        assertThatThrownBy(() -> second.decrypt(first.encrypt("secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ECHO_JMS_CREDENTIAL_KEY");
    }
}
