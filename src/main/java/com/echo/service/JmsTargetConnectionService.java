package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.dto.JmsTargetConnectionDto;
import com.echo.dto.JmsTargetConnectionRequest;
import com.echo.entity.JmsTargetConnection;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.repository.JmsTargetConnectionRepository;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** CRUD, validation, default selection, and runtime resolution for outbound JMS profiles. */
@Service
@RequiredArgsConstructor
@Slf4j
public class JmsTargetConnectionService {

    public static final String LEGACY_ID = "legacy-yaml";
    private static final Set<String> SUPPORTED_TYPES = Set.of("artemis", "tibco");

    private final JmsTargetConnectionRepository repository;
    private final JmsCredentialCipher credentialCipher;
    private final JmsProperties jmsProperties;
    private final List<JmsTargetFactoryProvider> factoryProviders;

    @Transactional(readOnly = true)
    public List<JmsTargetConnectionDto> list() {
        List<JmsTargetConnectionDto> result = new ArrayList<>();
        repository.findAllByOrderByNameAsc().stream().map(this::toDto).forEach(result::add);
        if (result.isEmpty() && legacyConfigured()) {
            result.add(legacyDto());
        }
        return List.copyOf(result);
    }

    @Transactional
    public JmsTargetConnectionDto create(JmsTargetConnectionRequest request) {
        validate(request);
        if (repository.existsByNameIgnoreCase(request.name().trim())) {
            throw new IllegalArgumentException("JMS_CONNECTION_NAME_EXISTS");
        }
        boolean first = repository.count() == 0;
        boolean makeDefault = Boolean.TRUE.equals(request.defaultConnection())
                || (first && !Boolean.FALSE.equals(request.enabled()));
        if (makeDefault && Boolean.FALSE.equals(request.enabled())) {
            throw new IllegalArgumentException("DEFAULT_JMS_CONNECTION_MUST_BE_ENABLED");
        }
        if (makeDefault) {
            clearDefaultConnections();
        }
        JmsTargetConnection entity = JmsTargetConnection.builder()
                .name(request.name().trim())
                .providerType(normalizeType(request.providerType()))
                .serverUrl(request.serverUrl().trim())
                .username(trimToNull(request.username()))
                .encryptedPassword(credentialCipher.encrypt(request.password()))
                .queueName(request.queueName().trim())
                .timeoutSeconds(request.timeoutSeconds())
                .enabled(!Boolean.FALSE.equals(request.enabled()))
                .defaultConnection(makeDefault)
                .build();
        return toDto(repository.saveAndFlush(entity));
    }

    @Transactional
    public JmsTargetConnectionDto update(Long id, JmsTargetConnectionRequest request) {
        validate(request);
        JmsTargetConnection entity = findEntity(id);
        if (request.version() != null && !request.version().equals(entity.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(JmsTargetConnection.class, id);
        }
        if (repository.existsByNameIgnoreCaseAndIdNot(request.name().trim(), id)) {
            throw new IllegalArgumentException("JMS_CONNECTION_NAME_EXISTS");
        }

        boolean requestedEnabled = !Boolean.FALSE.equals(request.enabled());
        if (Boolean.TRUE.equals(entity.getDefaultConnection()) && !requestedEnabled) {
            throw new IllegalArgumentException("DEFAULT_JMS_CONNECTION_CANNOT_BE_DISABLED");
        }
        if (Boolean.TRUE.equals(entity.getDefaultConnection())
                && Boolean.FALSE.equals(request.defaultConnection())) {
            throw new IllegalArgumentException("USE_ANOTHER_CONNECTION_AS_DEFAULT_FIRST");
        }
        if (Boolean.TRUE.equals(request.defaultConnection())) {
            if (!requestedEnabled) {
                throw new IllegalArgumentException("DEFAULT_JMS_CONNECTION_MUST_BE_ENABLED");
            }
            clearDefaultConnections();
            entity.setDefaultConnection(true);
        }

        entity.setName(request.name().trim());
        entity.setProviderType(normalizeType(request.providerType()));
        entity.setServerUrl(request.serverUrl().trim());
        entity.setUsername(trimToNull(request.username()));
        entity.setQueueName(request.queueName().trim());
        entity.setTimeoutSeconds(request.timeoutSeconds());
        entity.setEnabled(requestedEnabled);
        if (request.clearPassword()) {
            entity.setEncryptedPassword(null);
        } else if (request.password() != null && !request.password().isEmpty()) {
            entity.setEncryptedPassword(credentialCipher.encrypt(request.password()));
        }
        return toDto(repository.saveAndFlush(entity));
    }

    @Transactional
    public JmsTargetConnectionDto setDefault(Long id) {
        JmsTargetConnection entity = findEntity(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalArgumentException("DEFAULT_JMS_CONNECTION_MUST_BE_ENABLED");
        }
        clearDefaultConnections();
        entity.setDefaultConnection(true);
        return toDto(repository.saveAndFlush(entity));
    }

    @Transactional
    public void delete(Long id) {
        JmsTargetConnection entity = findEntity(id);
        if (Boolean.TRUE.equals(entity.getDefaultConnection())) {
            throw new IllegalArgumentException("DEFAULT_JMS_CONNECTION_CANNOT_BE_DELETED");
        }
        repository.delete(entity);
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedTarget> resolveActive() {
        Optional<JmsTargetConnection> stored = repository
                .findFirstByDefaultConnectionTrueAndEnabledTrue();
        if (stored.isPresent()) {
            JmsTargetConnection entity = stored.get();
            return Optional.of(new ResolvedTarget(
                    "db:" + entity.getId() + ":" + entity.getVersion(),
                    entity.getName(), toTarget(entity), false));
        }
        if (repository.count() == 0 && legacyConfigured()) {
            return Optional.of(new ResolvedTarget(
                    LEGACY_ID, "Legacy application.yml", copyLegacyTarget(), true));
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public ConnectionTestResult test(String id) {
        long started = System.nanoTime();
        try {
            JmsProperties.Target target;
            if (LEGACY_ID.equals(id)) {
                if (!legacyConfigured()) {
                    throw new IllegalArgumentException("JMS_CONNECTION_NOT_FOUND");
                }
                target = copyLegacyTarget();
            } else {
                target = toTarget(findEntity(parseId(id)));
            }
            ConnectionFactory factory = findProvider(target.getType()).create(target);
            try (Connection connection = factory.createConnection()) {
                connection.start();
            } finally {
                if (factory instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception e) {
                        log.debug("Error closing tested JMS factory: {}", e.getMessage());
                    }
                }
            }
            return new ConnectionTestResult(true, elapsedMillis(started), null);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            return new ConnectionTestResult(false, elapsedMillis(started), safeError(e));
        }
    }

    public JmsTargetFactoryProvider findProvider(String type) {
        return factoryProviders.stream()
                .filter(provider -> provider.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("UNSUPPORTED_JMS_PROVIDER"));
    }

    private void validate(JmsTargetConnectionRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("JMS_CONNECTION_NAME_REQUIRED");
        }
        if (request.name().trim().length() > 100) {
            throw new IllegalArgumentException("JMS_CONNECTION_NAME_TOO_LONG");
        }
        String type = normalizeType(request.providerType());
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("UNSUPPORTED_JMS_PROVIDER");
        }
        if (request.serverUrl() == null || request.serverUrl().isBlank()) {
            throw new IllegalArgumentException("JMS_SERVER_URL_REQUIRED");
        }
        if (request.queueName() == null || request.queueName().isBlank()) {
            throw new IllegalArgumentException("JMS_TARGET_QUEUE_REQUIRED");
        }
        if (request.timeoutSeconds() == null
                || request.timeoutSeconds() < 1 || request.timeoutSeconds() > 300) {
            throw new IllegalArgumentException("JMS_TIMEOUT_OUT_OF_RANGE");
        }
    }

    private JmsTargetConnection findEntity(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("JMS_CONNECTION_NOT_FOUND"));
    }

    private static Long parseId(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("JMS_CONNECTION_NOT_FOUND", e);
        }
    }

    private void clearDefaultConnections() {
        repository.findAll().stream()
                .filter(connection -> Boolean.TRUE.equals(connection.getDefaultConnection()))
                .forEach(connection -> connection.setDefaultConnection(false));
        repository.flush();
    }

    private JmsProperties.Target toTarget(JmsTargetConnection entity) {
        JmsProperties.Target target = new JmsProperties.Target();
        target.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        target.setType(entity.getProviderType());
        target.setServerUrl(entity.getServerUrl());
        target.setUsername(entity.getUsername());
        target.setPassword(credentialCipher.decrypt(entity.getEncryptedPassword()));
        target.setQueue(entity.getQueueName());
        target.setTimeoutSeconds(entity.getTimeoutSeconds());
        return target;
    }

    private JmsProperties.Target copyLegacyTarget() {
        JmsProperties.Target configured = jmsProperties.getTarget();
        JmsProperties.Target copy = new JmsProperties.Target();
        copy.setEnabled(configured.isEnabled());
        copy.setType(configured.getType());
        copy.setServerUrl(configured.getServerUrl());
        copy.setUsername(configured.getUsername());
        copy.setPassword(configured.getPassword());
        copy.setQueue(configured.getQueue());
        copy.setTimeoutSeconds(configured.getTimeoutSeconds());
        return copy;
    }

    private boolean legacyConfigured() {
        JmsProperties.Target target = jmsProperties.getTarget();
        return target.isEnabled() && target.getServerUrl() != null && !target.getServerUrl().isBlank();
    }

    private JmsTargetConnectionDto legacyDto() {
        JmsProperties.Target target = jmsProperties.getTarget();
        return new JmsTargetConnectionDto(
                LEGACY_ID, null, "Legacy application.yml", normalizeType(target.getType()),
                target.getServerUrl(), target.getUsername(),
                target.getPassword() != null && !target.getPassword().isEmpty(),
                target.getQueue(), target.getTimeoutSeconds(), target.isEnabled(),
                target.isEnabled(), true, null, null);
    }

    private JmsTargetConnectionDto toDto(JmsTargetConnection entity) {
        return new JmsTargetConnectionDto(
                String.valueOf(entity.getId()), entity.getVersion(), entity.getName(),
                entity.getProviderType(), entity.getServerUrl(), entity.getUsername(),
                entity.getEncryptedPassword() != null && !entity.getEncryptedPassword().isBlank(),
                entity.getQueueName(), entity.getTimeoutSeconds(),
                Boolean.TRUE.equals(entity.getEnabled()),
                Boolean.TRUE.equals(entity.getDefaultConnection()), false,
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    /** Runtime selection whose mutable provider configuration is returned as a defensive copy. */
    public static final class ResolvedTarget {
        private final String cacheKey;
        private final String name;
        private final JmsProperties.Target target;
        private final boolean legacy;

        public ResolvedTarget(String cacheKey, String name, JmsProperties.Target target,
                              boolean legacy) {
            this.cacheKey = cacheKey;
            this.name = name;
            this.target = copyOf(target);
            this.legacy = legacy;
        }

        public String cacheKey() {
            return cacheKey;
        }

        public String name() {
            return name;
        }

        public JmsProperties.Target target() {
            return copyOf(target);
        }

        public boolean legacy() {
            return legacy;
        }

        private static JmsProperties.Target copyOf(JmsProperties.Target source) {
            JmsProperties.Target copy = new JmsProperties.Target();
            copy.setEnabled(source.isEnabled());
            copy.setType(source.getType());
            copy.setServerUrl(source.getServerUrl());
            copy.setUsername(source.getUsername());
            copy.setPassword(source.getPassword());
            copy.setQueue(source.getQueue());
            copy.setTimeoutSeconds(source.getTimeoutSeconds());
            return copy;
        }
    }

    public record ConnectionTestResult(boolean success, long elapsedMs, String error) {
    }
}
