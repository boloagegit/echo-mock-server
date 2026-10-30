package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.config.AfterCommit;
import com.echo.dto.JmsTargetConnectionDto;
import com.echo.dto.JmsTargetConnectionRequest;
import com.echo.entity.JmsTargetConnection;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.repository.JmsTargetConnectionRepository;
import com.echo.repository.JmsRuleRepository;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** CRUD, validation, default selection, and runtime resolution for outbound JMS profiles. */
@Service
@RequiredArgsConstructor
@Slf4j
public class JmsTargetConnectionService {

    public static final String LEGACY_ID = "legacy-yaml";
    private static final String YAML_TARGET_NAME = "application.yml";
    private static final Set<String> SUPPORTED_TYPES = Set.of("artemis", "tibco");

    private final JmsTargetConnectionRepository repository;
    private final JmsRuleRepository jmsRuleRepository;
    private final JmsCredentialCipher credentialCipher;
    private final JmsProperties jmsProperties;
    private final List<JmsTargetFactoryProvider> factoryProviders;

    private TransactionTemplate readOnlyLookup;
    private JmsTargetCatalog targetCatalog;

    @Autowired
    void configureRuntimeLookup(EntityManagerFactory factory, PlatformTransactionManager transactions) {
        readOnlyLookup = new TransactionTemplate(transactions);
        readOnlyLookup.setReadOnly(true);
        // Shared external databases retain their existing cross-instance visibility.
        boolean sqlite = factory.unwrap(SessionFactoryImplementor.class)
                .getJdbcServices().getDialect() instanceof SQLiteDialect;
        targetCatalog = sqlite && jmsProperties.isTargetCatalogEnabled()
                ? new JmsTargetCatalog(this::loadCatalog) : null;
    }

    private <T> T readTarget(Supplier<T> lookup) {
        // Direct-construction tests retain their existing repository seam.
        return readOnlyLookup == null ? lookup.get() : readOnlyLookup.execute(status -> lookup.get());
    }

    private JmsTargetCatalog.Snapshot loadCatalog() {
        return readTarget(() -> {
            var profiles = new HashMap<Long, JmsTargetCatalog.Profile>();
            for (JmsTargetConnection entity : repository.findAllByOrderByNameAsc()) {
                String key = "db:" + entity.getId() + ":" + entity.getVersion();
                String name = entity.getName();
                var settings = TargetSettings.from(entity);
                profiles.put(entity.getId(), new JmsTargetCatalog.Profile(Boolean.TRUE.equals(entity.getEnabled()),
                        () -> new ResolvedTarget(key, name, settings.target(credentialCipher), false)));
            }
            // Preserve the repository's default selection, including legacy data
            // with multiple defaults, within the same SQLite read transaction.
            Long defaultId = repository.findFirstByDefaultConnectionTrueAndEnabledTrue()
                    .map(JmsTargetConnection::getId).orElse(null);
            return new JmsTargetCatalog.Snapshot(profiles, defaultId);
        });
    }

    private boolean canUseCatalog() {
        // Rule validation and callers inside a DB transaction must read their
        // own uncommitted changes without ever publishing them to other requests.
        return targetCatalog != null && !TransactionSynchronizationManager.isActualTransactionActive();
    }

    void invalidateTargetCatalog() {
        if (targetCatalog != null) targetCatalog.invalidate();
    }

    private void targetsChanged() {
        if (targetCatalog != null && !AfterCommit.schedule(targetCatalog, this::invalidateTargetCatalog)) {
            invalidateTargetCatalog();
        }
    }

    @Transactional(readOnly = true)
    public List<JmsTargetConnectionDto> list() {
        List<JmsTargetConnectionDto> result = new ArrayList<>();
        if (legacyConfigured()) {
            result.add(legacyDto());
        }
        repository.findAllByOrderByNameAsc().stream().map(this::toDto).forEach(result::add);
        return List.copyOf(result);
    }

    /** Metadata for the actual default, with the same YAML-first precedence as resolveActive(). */
    @Transactional(readOnly = true)
    public Optional<JmsTargetConnectionDto> findActiveSummary() {
        if (legacyConfigured()) {
            return Optional.of(legacyDto());
        }
        return repository.findFirstByDefaultConnectionTrueAndEnabledTrue().map(this::toDto);
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
        JmsTargetConnectionDto saved = toDto(repository.saveAndFlush(entity));
        targetsChanged();
        return saved;
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
        JmsTargetConnectionDto saved = toDto(repository.saveAndFlush(entity));
        targetsChanged();
        return saved;
    }

    @Transactional
    public JmsTargetConnectionDto setDefault(Long id) {
        JmsTargetConnection entity = findEntity(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalArgumentException("DEFAULT_JMS_CONNECTION_MUST_BE_ENABLED");
        }
        clearDefaultConnections();
        entity.setDefaultConnection(true);
        JmsTargetConnectionDto saved = toDto(repository.saveAndFlush(entity));
        targetsChanged();
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        JmsTargetConnection entity = findEntity(id);
        if (Boolean.TRUE.equals(entity.getDefaultConnection())) {
            throw new IllegalArgumentException("DEFAULT_JMS_CONNECTION_CANNOT_BE_DELETED");
        }
        if (jmsRuleRepository.countByJmsTargetConnectionId(String.valueOf(id)) > 0) {
            throw new IllegalArgumentException("JMS_CONNECTION_IN_USE");
        }
        repository.delete(entity);
        targetsChanged();
    }

    public Optional<ResolvedTarget> resolveActive() {
        if (legacyConfigured()) {
            return Optional.of(new ResolvedTarget(
                    LEGACY_ID, legacyName(), copyLegacyTarget(), true));
        }
        if (canUseCatalog()) return targetCatalog.snapshot().active();
        return readTarget(this::resolveStoredDefault);
    }

    private Optional<ResolvedTarget> resolveStoredDefault() {
        Optional<JmsTargetConnection> stored = repository
                .findFirstByDefaultConnectionTrueAndEnabledTrue();
        if (stored.isPresent()) {
            JmsTargetConnection entity = stored.get();
            return Optional.of(new ResolvedTarget(
                    "db:" + entity.getId() + ":" + entity.getVersion(),
                    entity.getName(), toTarget(entity), false));
        }
        return Optional.empty();
    }

    public ResolvedTarget resolveEnabled(String id) {
        if (LEGACY_ID.equals(id)) {
            if (!legacyConfigured()) {
                throw new IllegalArgumentException("JMS_CONNECTION_NOT_FOUND");
            }
            return new ResolvedTarget(
                    LEGACY_ID, legacyName(), copyLegacyTarget(), true);
        }
        Long parsedId = parseId(id);
        if (canUseCatalog()) return targetCatalog.snapshot().enabled(parsedId);
        return readTarget(() -> resolveStoredTarget(parsedId));
    }

    private ResolvedTarget resolveStoredTarget(Long id) {
        JmsTargetConnection entity = findEntity(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalArgumentException("JMS_CONNECTION_DISABLED");
        }
        return new ResolvedTarget(
                "db:" + entity.getId() + ":" + entity.getVersion(),
                entity.getName(), toTarget(entity), false);
    }

    @Transactional(readOnly = true)
    public void validateForwardSelection(String mode, String connectionId) {
        String effectiveMode = mode == null || mode.isBlank()
                ? "DEFAULT_CONNECTION" : mode;
        if ("CONNECTION".equals(effectiveMode)) {
            if (connectionId == null || connectionId.isBlank()) {
                throw new IllegalArgumentException("JMS_CONNECTION_REQUIRED");
            }
            resolveEnabled(connectionId);
        } else if ("DEFAULT_CONNECTION".equals(effectiveMode)) {
            if (resolveActive().isEmpty()) {
                throw new IllegalArgumentException("DEFAULT_JMS_CONNECTION_NOT_FOUND");
            }
        } else {
            throw new IllegalArgumentException("INVALID_JMS_FORWARD_TARGET_MODE");
        }
    }

    // Repository reads finish before provider I/O, which may block independently of the database.
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
            JmsTargetFactoryProvider provider = findProvider(target.getType());
            ConnectionFactory factory = jmsProperties.getForward().isBoundedWaitsEnabled()
                    ? provider.createOutbound(target) : provider.create(target);
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
        return TargetSettings.from(entity).target(credentialCipher);
    }

    private record TargetSettings(boolean enabled, String type, String serverUrl, String username,
                                  String encryptedPassword, String queue, Integer timeoutSeconds) {
        static TargetSettings from(JmsTargetConnection entity) {
            return new TargetSettings(Boolean.TRUE.equals(entity.getEnabled()), entity.getProviderType(),
                    entity.getServerUrl(), entity.getUsername(), entity.getEncryptedPassword(),
                    entity.getQueueName(), entity.getTimeoutSeconds());
        }

        JmsProperties.Target target(JmsCredentialCipher cipher) {
            JmsProperties.Target target = new JmsProperties.Target();
            target.setEnabled(enabled);
            target.setType(type);
            target.setServerUrl(serverUrl);
            target.setUsername(username);
            target.setPassword(cipher.decrypt(encryptedPassword));
            target.setQueue(queue);
            target.setTimeoutSeconds(timeoutSeconds);
            return target;
        }
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

    /** The display name of the downstream configuration file is: {@code echo.jms.target.name}. If not set, it is {@code application.yml}. */
    private String legacyName() {
        return configuredLegacyName(jmsProperties.getTarget(), YAML_TARGET_NAME);
    }

    /** {@code echo.jms.target.name} (remove the leading and trailing blanks); return {@code fallback} when blank. */
    public static String configuredLegacyName(JmsProperties.Target target, String fallback) {
        String name = target.getName();
        return name == null || name.isBlank() ? fallback : name.trim();
    }

    private boolean legacyConfigured() {
        JmsProperties.Target target = jmsProperties.getTarget();
        return target.isEnabled() && target.getServerUrl() != null && !target.getServerUrl().isBlank();
    }

    private JmsTargetConnectionDto legacyDto() {
        JmsProperties.Target target = jmsProperties.getTarget();
        return new JmsTargetConnectionDto(
                LEGACY_ID, null, legacyName(), normalizeType(target.getType()),
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
