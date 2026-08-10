package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.dto.JmsTargetConnectionRequest;
import com.echo.entity.JmsTargetConnection;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.repository.JmsTargetConnectionRepository;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JmsTargetConnectionServiceTest {

    @Mock JmsTargetConnectionRepository repository;
    @Mock JmsCredentialCipher cipher;
    @Mock JmsTargetFactoryProvider provider;
    @Mock ConnectionFactory factory;
    @Mock Connection connection;

    private JmsProperties properties;
    private JmsTargetConnectionService service;

    @BeforeEach
    void setUp() {
        properties = new JmsProperties();
        service = new JmsTargetConnectionService(repository, cipher, properties, List.of(provider));
    }

    @Test
    void firstEnabledConnectionAutomaticallyBecomesDefaultAndEncryptsPassword() {
        when(repository.count()).thenReturn(0L);
        when(repository.existsByNameIgnoreCase("SIT Artemis")).thenReturn(false);
        when(cipher.encrypt("secret")).thenReturn("encrypted");
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            JmsTargetConnection entity = invocation.getArgument(0);
            entity.setId(1L);
            entity.setVersion(0L);
            return entity;
        });

        var result = service.create(request(null, "SIT Artemis", true, false));

        assertThat(result.defaultConnection()).isTrue();
        assertThat(result.passwordConfigured()).isTrue();
        verify(cipher).encrypt("secret");
    }

    @Test
    void disabledConnectionCannotBeCreatedAsDefault() {
        when(repository.existsByNameIgnoreCase("SIT Artemis")).thenReturn(false);
        when(repository.count()).thenReturn(0L);

        assertThatThrownBy(() -> service.create(
                request(null, "SIT Artemis", false, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DEFAULT_JMS_CONNECTION_MUST_BE_ENABLED");
    }

    @Test
    void defaultConnectionCannotBeDeletedOrDisabled() {
        JmsTargetConnection entity = entity(1L, true, true);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DEFAULT_JMS_CONNECTION_CANNOT_BE_DELETED");
        assertThatThrownBy(() -> service.update(1L,
                request(0L, "SIT Artemis", false, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DEFAULT_JMS_CONNECTION_CANNOT_BE_DISABLED");
    }

    @Test
    void nonDefaultConnectionCanBeDeleted() {
        JmsTargetConnection entity = entity(2L, true, false);
        when(repository.findById(2L)).thenReturn(Optional.of(entity));

        service.delete(2L);

        verify(repository).delete(entity);
    }

    @Test
    void staleVersionReturnsOptimisticLockConflict() {
        JmsTargetConnection entity = entity(1L, true, true);
        entity.setVersion(5L);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(1L,
                request(4L, "SIT Artemis", true, true)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void resolvesStoredDefaultAndDecryptsCredential() {
        JmsTargetConnection entity = entity(7L, true, true);
        entity.setEncryptedPassword("encrypted");
        when(repository.findFirstByDefaultConnectionTrueAndEnabledTrue())
                .thenReturn(Optional.of(entity));
        when(cipher.decrypt("encrypted")).thenReturn("secret");

        var resolved = service.resolveActive().orElseThrow();

        assertThat(resolved.cacheKey()).isEqualTo("db:7:0");
        assertThat(resolved.target().getPassword()).isEqualTo("secret");
        assertThat(resolved.target().getQueue()).isEqualTo("TARGET.REQUEST");
    }

    @Test
    void fallsBackToLegacyYamlOnlyWhenNoStoredProfilesExist() {
        properties.getTarget().setEnabled(true);
        properties.getTarget().setType("artemis");
        properties.getTarget().setServerUrl("tcp://legacy:61616");
        when(repository.findFirstByDefaultConnectionTrueAndEnabledTrue()).thenReturn(Optional.empty());
        when(repository.count()).thenReturn(0L);

        var resolved = service.resolveActive().orElseThrow();

        assertThat(resolved.legacy()).isTrue();
        assertThat(resolved.target().getServerUrl()).isEqualTo("tcp://legacy:61616");
    }

    @Test
    void connectionTestClosesConnectionAndReturnsElapsedTime() throws Exception {
        JmsTargetConnection entity = entity(2L, true, true);
        when(repository.findById(2L)).thenReturn(Optional.of(entity));
        when(provider.supports("artemis")).thenReturn(true);
        when(provider.create(any())).thenReturn(factory);
        when(factory.createConnection()).thenReturn(connection);

        var result = service.test("2");

        assertThat(result.success()).isTrue();
        assertThat(result.elapsedMs()).isNotNegative();
        verify(connection).start();
        verify(connection).close();
    }

    private static JmsTargetConnectionRequest request(Long version, String name,
                                                       boolean enabled, boolean defaultConnection) {
        return new JmsTargetConnectionRequest(version, name, "artemis",
                "tcp://localhost:61616", "user", "secret", false,
                "TARGET.REQUEST", 30, enabled, defaultConnection);
    }

    private static JmsTargetConnection entity(Long id, boolean enabled, boolean defaultConnection) {
        return JmsTargetConnection.builder()
                .id(id).version(0L).name("SIT Artemis").providerType("artemis")
                .serverUrl("tcp://localhost:61616").username("user")
                .queueName("TARGET.REQUEST").timeoutSeconds(30)
                .enabled(enabled).defaultConnection(defaultConnection).build();
    }
}
