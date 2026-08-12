package com.echo.service;

import com.echo.dto.HttpTargetConnectionRequest;
import com.echo.entity.HttpTargetConnection;
import com.echo.repository.HttpTargetConnectionRepository;
import com.echo.repository.HttpRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HttpTargetConnectionServiceTest {

    @Mock HttpTargetConnectionRepository repository;
    @Mock HttpRuleRepository httpRuleRepository;
    @Mock JmsCredentialCipher cipher;

    private HttpTargetConnectionService service;

    @BeforeEach
    void setUp() {
        service = new HttpTargetConnectionService(repository, httpRuleRepository, cipher);
    }

    @Test
    void firstEnabledConnectionBecomesDefaultAndTlsVerificationDefaultsOff() {
        when(repository.count()).thenReturn(0L);
        when(repository.findAll()).thenReturn(List.of());
        when(cipher.encrypt(null)).thenReturn(null);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            HttpTargetConnection entity = invocation.getArgument(0);
            entity.setId(1L);
            entity.setVersion(0L);
            return entity;
        });

        var result = service.create(request(null, "Internal", "https://internal.example", false));

        assertThat(result.defaultConnection()).isTrue();
        assertThat(result.tlsVerificationEnabled()).isFalse();
        assertThat(result.baseUrl()).isEqualTo("https://internal.example");
    }

    @Test
    void rejectsNonHttpUrlAndOutOfRangeTimeout() {
        assertThatThrownBy(() -> service.create(request(null, "Bad", "file:///tmp/a", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_HTTP_BASE_URL");

        HttpTargetConnectionRequest request = new HttpTargetConnectionRequest(null, "Bad",
                "https://example.test", "NONE", null, null, false,
                0, 30, false, true, false);
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP_CONNECT_TIMEOUT_OUT_OF_RANGE");
    }

    @Test
    void rejectsTimeoutsThatExceedMvcRequestBudget() {
        HttpTargetConnectionRequest request = new HttpTargetConnectionRequest(null, "Slow",
                "https://example.test", "NONE", null, null, false,
                10, 30, false, true, false);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP_TIMEOUT_BUDGET_EXCEEDED");
    }

    @Test
    void rejectsTimeoutsEqualToMvcRequestBudget() {
        ReflectionTestUtils.setField(service, "requestTimeoutMs", 38_000L);
        HttpTargetConnectionRequest request = new HttpTargetConnectionRequest(null, "No margin",
                "https://example.test", "NONE", null, null, false,
                5, 30, false, true, false);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP_TIMEOUT_BUDGET_EXCEEDED");
    }

    @Test
    void rejectsExistingProfileThatNoLongerFitsConfiguredBudget() {
        HttpTargetConnection connection = entity(8L, true, false);
        connection.setConnectTimeoutSeconds(20);
        connection.setReadTimeoutSeconds(30);
        when(repository.findById(8L)).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.resolveEnabled(8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP_TIMEOUT_BUDGET_EXCEEDED");

        ReflectionTestUtils.setField(service, "requestTimeoutMs", 60_000L);
        when(cipher.decrypt(null)).thenReturn(null);
        assertThat(service.resolveEnabled(8L).readTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    void explicitConnectionMustExistAndBeEnabled() {
        HttpTargetConnection disabled = entity(7L, false, false);
        when(repository.findById(7L)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.validateForwardSelection("CONNECTION", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP_CONNECTION_DISABLED");
        assertThatThrownBy(() -> service.validateForwardSelection("CONNECTION", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP_CONNECTION_REQUIRED");
    }

    @Test
    void refusesToDeleteConnectionReferencedByForwardRule() {
        HttpTargetConnection connection = entity(9L, true, false);
        when(repository.findById(9L)).thenReturn(Optional.of(connection));
        when(httpRuleRepository.countByHttpTargetConnectionId(9L)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP_CONNECTION_IN_USE");
    }

    private static HttpTargetConnectionRequest request(Long version, String name,
                                                        String baseUrl, boolean tls) {
        return new HttpTargetConnectionRequest(version, name, baseUrl, "NONE", null,
                null, false, 5, 30, tls, true, false);
    }

    private static HttpTargetConnection entity(Long id, boolean enabled, boolean defaultConnection) {
        return HttpTargetConnection.builder().id(id).version(0L).name("Target")
                .baseUrl("https://example.test").authType("NONE")
                .connectTimeoutSeconds(5).readTimeoutSeconds(30)
                .tlsVerificationEnabled(false).enabled(enabled)
                .defaultConnection(defaultConnection).build();
    }
}
