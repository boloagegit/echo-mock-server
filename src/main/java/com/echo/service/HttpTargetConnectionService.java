package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.dto.HttpTargetConnectionDto;
import com.echo.dto.HttpTargetConnectionRequest;
import com.echo.entity.HttpTargetConnection;
import com.echo.repository.HttpTargetConnectionRepository;
import com.echo.repository.HttpRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** CRUD, validation and runtime resolution for outbound HTTP profiles. */
@Service
@RequiredArgsConstructor
public class HttpTargetConnectionService {

    private static final Set<String> AUTH_TYPES = Set.of("NONE", "BASIC", "BEARER");

    private final HttpTargetConnectionRepository repository;
    private final HttpRuleRepository httpRuleRepository;
    private final JmsCredentialCipher credentialCipher;

    @Value("${echo.http.request-timeout-ms:40000}")
    private long requestTimeoutMs = 40_000L;

    @Value("${echo.http.forward.pool-acquire-timeout-ms:3000}")
    private int poolAcquireTimeoutMs = 3_000;

    @Transactional(readOnly = true)
    public List<HttpTargetConnectionDto> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.HTTP_TARGET_DEFAULT_CACHE, allEntries = true)
    public HttpTargetConnectionDto create(HttpTargetConnectionRequest request) {
        validate(request);
        String name = request.name().trim();
        if (repository.existsByNameIgnoreCase(name)) {
            throw new IllegalArgumentException("HTTP_CONNECTION_NAME_EXISTS");
        }
        boolean first = repository.count() == 0;
        boolean makeDefault = Boolean.TRUE.equals(request.defaultConnection())
                || (first && !Boolean.FALSE.equals(request.enabled()));
        if (makeDefault && Boolean.FALSE.equals(request.enabled())) {
            throw new IllegalArgumentException("DEFAULT_HTTP_CONNECTION_MUST_BE_ENABLED");
        }
        if (makeDefault) clearDefaultConnections();
        HttpTargetConnection entity = HttpTargetConnection.builder()
                .name(name)
                .baseUrl(normalizeBaseUrl(request.baseUrl()))
                .authType(normalizeAuthType(request.authType()))
                .username(trimToNull(request.username()))
                .encryptedSecret(credentialCipher.encrypt(request.secret()))
                .connectTimeoutSeconds(request.connectTimeoutSeconds())
                .readTimeoutSeconds(request.readTimeoutSeconds())
                .tlsVerificationEnabled(Boolean.TRUE.equals(request.tlsVerificationEnabled()))
                .enabled(!Boolean.FALSE.equals(request.enabled()))
                .defaultConnection(makeDefault)
                .build();
        return toDto(repository.saveAndFlush(entity));
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = CacheConfig.HTTP_TARGET_DEFAULT_CACHE, allEntries = true),
        @CacheEvict(cacheNames = CacheConfig.HTTP_TARGET_RESOLVED_CACHE, key = "#id")
    })
    public HttpTargetConnectionDto update(Long id, HttpTargetConnectionRequest request) {
        validate(request);
        HttpTargetConnection entity = findEntity(id);
        if (request.version() != null && !request.version().equals(entity.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(HttpTargetConnection.class, id);
        }
        String name = request.name().trim();
        if (repository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new IllegalArgumentException("HTTP_CONNECTION_NAME_EXISTS");
        }
        boolean requestedEnabled = !Boolean.FALSE.equals(request.enabled());
        if (Boolean.TRUE.equals(entity.getDefaultConnection()) && !requestedEnabled) {
            throw new IllegalArgumentException("DEFAULT_HTTP_CONNECTION_CANNOT_BE_DISABLED");
        }
        if (Boolean.TRUE.equals(entity.getDefaultConnection())
                && Boolean.FALSE.equals(request.defaultConnection())) {
            throw new IllegalArgumentException("USE_ANOTHER_HTTP_CONNECTION_AS_DEFAULT_FIRST");
        }
        if (Boolean.TRUE.equals(request.defaultConnection())) {
            if (!requestedEnabled) {
                throw new IllegalArgumentException("DEFAULT_HTTP_CONNECTION_MUST_BE_ENABLED");
            }
            clearDefaultConnections();
            entity.setDefaultConnection(true);
        }
        entity.setName(name);
        entity.setBaseUrl(normalizeBaseUrl(request.baseUrl()));
        entity.setAuthType(normalizeAuthType(request.authType()));
        entity.setUsername(trimToNull(request.username()));
        entity.setConnectTimeoutSeconds(request.connectTimeoutSeconds());
        entity.setReadTimeoutSeconds(request.readTimeoutSeconds());
        entity.setTlsVerificationEnabled(Boolean.TRUE.equals(request.tlsVerificationEnabled()));
        entity.setEnabled(requestedEnabled);
        if (request.clearSecret()) {
            entity.setEncryptedSecret(null);
        } else if (request.secret() != null && !request.secret().isEmpty()) {
            entity.setEncryptedSecret(credentialCipher.encrypt(request.secret()));
        }
        return toDto(repository.saveAndFlush(entity));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.HTTP_TARGET_DEFAULT_CACHE, allEntries = true)
    public HttpTargetConnectionDto setDefault(Long id) {
        HttpTargetConnection entity = findEntity(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalArgumentException("DEFAULT_HTTP_CONNECTION_MUST_BE_ENABLED");
        }
        clearDefaultConnections();
        entity.setDefaultConnection(true);
        return toDto(repository.saveAndFlush(entity));
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = CacheConfig.HTTP_TARGET_DEFAULT_CACHE, allEntries = true),
        @CacheEvict(cacheNames = CacheConfig.HTTP_TARGET_RESOLVED_CACHE, key = "#id")
    })
    public void delete(Long id) {
        HttpTargetConnection entity = findEntity(id);
        if (Boolean.TRUE.equals(entity.getDefaultConnection())) {
            throw new IllegalArgumentException("DEFAULT_HTTP_CONNECTION_CANNOT_BE_DELETED");
        }
        if (httpRuleRepository.countByHttpTargetConnectionId(id) > 0) {
            throw new IllegalArgumentException("HTTP_CONNECTION_IN_USE");
        }
        repository.delete(entity);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.HTTP_TARGET_DEFAULT_CACHE, key = "'active'")
    public Optional<ResolvedTarget> resolveDefault() {
        return repository.findFirstByDefaultConnectionTrueAndEnabledTrue().map(this::resolve);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.HTTP_TARGET_RESOLVED_CACHE, key = "#id")
    public ResolvedTarget resolveEnabled(Long id) {
        HttpTargetConnection entity = findEntity(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalArgumentException("HTTP_CONNECTION_DISABLED");
        }
        return resolve(entity);
    }

    @Transactional(readOnly = true)
    public void validateForwardSelection(String mode, Long connectionId) {
        String effectiveMode = mode == null || mode.isBlank() ? "ORIGINAL_HOST" : mode;
        if ("CONNECTION".equals(effectiveMode)) {
            if (connectionId == null) throw new IllegalArgumentException("HTTP_CONNECTION_REQUIRED");
            resolveEnabled(connectionId);
        } else if ("DEFAULT_CONNECTION".equals(effectiveMode)) {
            if (resolveDefault().isEmpty()) {
                throw new IllegalArgumentException("DEFAULT_HTTP_CONNECTION_NOT_FOUND");
            }
        } else if (!"ORIGINAL_HOST".equals(effectiveMode)) {
            throw new IllegalArgumentException("INVALID_HTTP_FORWARD_TARGET_MODE");
        }
    }

    private void validate(HttpTargetConnectionRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("HTTP_CONNECTION_NAME_REQUIRED");
        }
        if (request.name().trim().length() > 100) {
            throw new IllegalArgumentException("HTTP_CONNECTION_NAME_TOO_LONG");
        }
        normalizeBaseUrl(request.baseUrl());
        String authType = normalizeAuthType(request.authType());
        if (!AUTH_TYPES.contains(authType)) {
            throw new IllegalArgumentException("UNSUPPORTED_HTTP_AUTH_TYPE");
        }
        if ("BASIC".equals(authType)
                && (request.username() == null || request.username().isBlank())) {
            throw new IllegalArgumentException("HTTP_USERNAME_REQUIRED");
        }
        validateTimeout(request.connectTimeoutSeconds(), "HTTP_CONNECT_TIMEOUT_OUT_OF_RANGE");
        validateTimeout(request.readTimeoutSeconds(), "HTTP_READ_TIMEOUT_OUT_OF_RANGE");
        validateTimeoutBudget(request.connectTimeoutSeconds(), request.readTimeoutSeconds());
    }

    private static void validateTimeout(Integer value, String code) {
        if (value == null || value < 1 || value > 300) throw new IllegalArgumentException(code);
    }

    private void validateTimeoutBudget(int connectTimeoutSeconds, int readTimeoutSeconds) {
        long requiredMillis = poolAcquireTimeoutMs
                + Math.multiplyExact((long) connectTimeoutSeconds + readTimeoutSeconds, 1_000L);
        if (requiredMillis >= requestTimeoutMs) {
            throw new IllegalArgumentException("HTTP_TIMEOUT_BUDGET_EXCEEDED");
        }
    }

    private HttpTargetConnection findEntity(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("HTTP_CONNECTION_NOT_FOUND"));
    }

    private void clearDefaultConnections() {
        repository.findAll().stream()
                .filter(connection -> Boolean.TRUE.equals(connection.getDefaultConnection()))
                .forEach(connection -> connection.setDefaultConnection(false));
        repository.flush();
    }

    private ResolvedTarget resolve(HttpTargetConnection entity) {
        validateTimeoutBudget(entity.getConnectTimeoutSeconds(), entity.getReadTimeoutSeconds());
        return new ResolvedTarget(entity.getId(), entity.getVersion(), entity.getName(),
                entity.getBaseUrl(), entity.getAuthType(), entity.getUsername(),
                credentialCipher.decrypt(entity.getEncryptedSecret()),
                entity.getConnectTimeoutSeconds(), entity.getReadTimeoutSeconds(),
                Boolean.TRUE.equals(entity.getTlsVerificationEnabled()));
    }

    private HttpTargetConnectionDto toDto(HttpTargetConnection entity) {
        return new HttpTargetConnectionDto(entity.getId(), entity.getVersion(), entity.getName(),
                entity.getBaseUrl(), entity.getAuthType(), entity.getUsername(),
                entity.getEncryptedSecret() != null && !entity.getEncryptedSecret().isBlank(),
                entity.getConnectTimeoutSeconds(), entity.getReadTimeoutSeconds(),
                Boolean.TRUE.equals(entity.getTlsVerificationEnabled()),
                Boolean.TRUE.equals(entity.getEnabled()), Boolean.TRUE.equals(entity.getDefaultConnection()),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("HTTP_BASE_URL_REQUIRED");
        String normalized = value.trim();
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("INVALID_HTTP_BASE_URL", e);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("INVALID_HTTP_BASE_URL");
        }
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String normalizeAuthType(String value) {
        return value == null || value.isBlank() ? "NONE" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ResolvedTarget(Long id, Long version, String name, String baseUrl,
                                 String authType, String username, String secret,
                                 int connectTimeoutSeconds, int readTimeoutSeconds,
                                 boolean tlsVerificationEnabled) {
        public String cacheKey() {
            return id + ":" + version;
        }
    }
}
