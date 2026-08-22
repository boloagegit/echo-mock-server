package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.service.JmsTargetConnectionService;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * JMS 轉發器 - 轉發訊息到目標 JMS Server。
 * 透過 {@link JmsTargetFactoryProvider} 策略模式支援多種 JMS provider。
 */
@Component
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
@Slf4j
public class JmsTargetForwarder {

    private final Supplier<Optional<JmsTargetConnectionService.ResolvedTarget>> targetResolver;
    private final JmsTargetConnectionService connectionService;
    private final List<JmsTargetFactoryProvider> factoryProviders;
    private final Map<String, TargetClient> selectedTargetClients = new ConcurrentHashMap<>();
    private volatile ConnectionFactory targetFactory;
    private volatile Connection targetConnection;
    private volatile String activeTargetKey;

    @Autowired
    public JmsTargetForwarder(JmsTargetConnectionService connectionService,
                              List<JmsTargetFactoryProvider> factoryProviders) {
        this(connectionService::resolveActive, connectionService, factoryProviders);
    }

    /** Backward-compatible constructor used by existing standalone tests and integrations. */
    public JmsTargetForwarder(JmsProperties jmsProperties,
                              List<JmsTargetFactoryProvider> factoryProviders) {
        this(() -> legacyTarget(jmsProperties), null, factoryProviders);
    }

    private JmsTargetForwarder(
            Supplier<Optional<JmsTargetConnectionService.ResolvedTarget>> targetResolver,
            JmsTargetConnectionService connectionService,
            List<JmsTargetFactoryProvider> factoryProviders) {
        this.targetResolver = targetResolver;
        this.connectionService = connectionService;
        this.factoryProviders = factoryProviders;
    }

    @jakarta.annotation.PreDestroy
    public synchronized void cleanup() {
        if (targetConnection != null) {
            try {
                targetConnection.close();
            } catch (Exception e) {
                log.debug("Error closing target JMS connection: {}", e.getMessage());
            }
        }
        targetConnection = null;
        closeFactory();
        activeTargetKey = null;
        synchronized (selectedTargetClients) {
            selectedTargetClients.values().forEach(TargetClient::close);
            selectedTargetClients.clear();
        }
    }

    /**
     * 轉發訊息到目標 JMS Server，等待回應
     * 使用 target.queue 作為目標 Queue（非 source queue）
     */
    public String forward(String body, Message originalMessage) {
        return forwardWithMetadata(body, originalMessage).body();
    }

    /** 轉發並回傳不含認證資訊的實際目標，供 Request Log 使用。 */
    public ForwardResult forwardWithMetadata(String body, Message originalMessage) {
        Optional<JmsTargetConnectionService.ResolvedTarget> selected = targetResolver.get();
        if (selected.isEmpty()) {
            return new ForwardResult(
                    "<error>No default JMS target connection configured</error>", null);
        }
        JmsTargetConnectionService.ResolvedTarget resolved = selected.get();
        return new ForwardResult(forwardResolved(body, originalMessage, resolved),
                describeTarget(resolved));
    }

    private String forwardResolved(String body, Message originalMessage,
                                   JmsTargetConnectionService.ResolvedTarget resolved) {
        JmsProperties.Target target = resolved.target();
        String targetQueue = target.getQueue();
        int timeoutMs = target.getTimeoutSeconds() * 1000;

        try {
            if (connectionService != null) {
                try (TargetClientUse client = acquireSelectedClient(resolved)) {
                    return exchange(body, originalMessage, target, client.connection());
                }
            }
            ConnectionFactory factory = getOrCreateFactory(resolved);
            
            try (Session session = getConnection(factory, resolved.cacheKey())
                    .createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue destQueue = session.createQueue(targetQueue);
                TemporaryQueue replyQueue = session.createTemporaryQueue();

                // 發送訊息
                MessageProducer producer = session.createProducer(destQueue);
                TextMessage forwardMsg = session.createTextMessage(body);
                forwardMsg.setJMSReplyTo(replyQueue);
                
                try {
                    if (originalMessage == null) {
                        throw new IllegalStateException("Original JMS message unavailable");
                    }
                    forwardMsg.setJMSCorrelationID(originalMessage.getJMSMessageID());
                } catch (Exception e) {
                    log.debug("Failed to set JMSCorrelationID: {}", e.getMessage());
                }
                
                producer.send(forwardMsg);
                log.debug("Forwarded message to target queue: {}", targetQueue);

                // 等待回應
                MessageConsumer consumer = session.createConsumer(replyQueue);
                Message response = consumer.receive(timeoutMs);

                if (response instanceof TextMessage textMessage) {
                    String responseBody = textMessage.getText();
                    log.debug("Received response from target JMS");
                    return responseBody;
                } else {
                    log.warn("Target JMS response timeout or invalid type");
                    return "<error>JMS response timeout</error>";
                }
            }

        } catch (JMSException e) {
            if (connectionService == null) {
                resetConnection(resolved.cacheKey());
            } else {
                resetSelectedClientByCacheKey(resolved.cacheKey(), e);
            }
            log.error("Failed to forward to target JMS (connection reset): {}", e.getMessage());
            return "<error>JMS forward error: " + e.getMessage() + "</error>";
        } catch (Exception e) {
            log.error("Failed to forward to target JMS: {}", e.getMessage());
            return "<error>JMS forward error: " + e.getMessage() + "</error>";
        }
    }

    /**
     * Forwards through the connection selected by a matched JMS rule.
     * Default forwarding keeps the existing hot connection path. Explicit profiles use isolated
     * clients so concurrent rules targeting different brokers never close each other's connection.
     */
    public String forward(String body, Message originalMessage, String connectionId,
                          boolean useDefaultConnection) {
        return forwardWithMetadata(body, originalMessage, connectionId, useDefaultConnection).body();
    }

    /** 依規則選定的連線轉發，並回傳安全的目標資訊。 */
    public ForwardResult forwardWithMetadata(String body, Message originalMessage,
                                             String connectionId,
                                             boolean useDefaultConnection) {
        if (useDefaultConnection) {
            return forwardWithMetadata(body, originalMessage);
        }
        if (connectionService == null) {
            return new ForwardResult(
                    "<error>Named JMS target connections are unavailable</error>", null);
        }
        JmsTargetConnectionService.ResolvedTarget resolved = null;
        try {
            resolved = connectionService.resolveEnabled(connectionId);
            try (TargetClientUse client = acquireSelectedClient(resolved)) {
                return new ForwardResult(
                        exchange(body, originalMessage, resolved.target(), client.connection()),
                        describeTarget(resolved));
            }
        } catch (JMSException e) {
            resetSelectedClientByCacheKey(resolved.cacheKey(), e);
            log.error("Failed to forward to selected JMS target (connection reset): {}",
                    e.getMessage());
            return new ForwardResult(
                    "<error>JMS forward error: " + e.getMessage() + "</error>",
                    describeTarget(resolved));
        } catch (Exception e) {
            log.error("Failed to forward to selected JMS target: {}", e.getMessage());
            return new ForwardResult(
                    "<error>JMS forward error: " + e.getMessage() + "</error>",
                    resolved == null ? null : describeTarget(resolved));
        }
    }

    private static String describeTarget(JmsTargetConnectionService.ResolvedTarget resolved) {
        JmsProperties.Target target = resolved.target();
        return resolved.name() + " | " + sanitizeServerUrl(target.getServerUrl())
                + " | " + target.getQueue();
    }

    static String sanitizeServerUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            return "-";
        }
        String sanitized = serverUrl.trim();
        int queryStart = sanitized.indexOf('?');
        if (queryStart >= 0) {
            sanitized = sanitized.substring(0, queryStart);
        }
        int fragmentStart = sanitized.indexOf('#');
        if (fragmentStart >= 0) {
            sanitized = sanitized.substring(0, fragmentStart);
        }
        return sanitized.replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@", "$1");
    }

    public record ForwardResult(String body, String target) {
    }

    private void resetConnection(String expectedTargetKey) {
        synchronized (this) {
            // A request using the retired profile must never reset the newly selected connection.
            if (activeTargetKey != null && !activeTargetKey.equals(expectedTargetKey)) {
                return;
            }
            if (targetConnection != null) {
                try {
                    targetConnection.close();
                } catch (Exception e) {
                    log.debug("Error closing target JMS connection during reset: {}", e.getMessage());
                }
                targetConnection = null;
                log.info("Target JMS connection reset, will reconnect on next forward");
            }
        }
    }

    private ConnectionFactory getOrCreateFactory(
            JmsTargetConnectionService.ResolvedTarget resolved) throws Exception {
        // A null key with an injected factory is retained for existing isolated unit tests.
        if (targetFactory != null && activeTargetKey == null) {
            return targetFactory;
        }
        if (targetFactory == null || !resolved.cacheKey().equals(activeTargetKey)) {
            synchronized (this) {
                if (targetFactory == null || !resolved.cacheKey().equals(activeTargetKey)) {
                    closeActiveForSwitch();
                    targetFactory = createFactory(resolved.target());
                    activeTargetKey = resolved.cacheKey();
                    log.info("Selected outbound JMS connection: {}", resolved.name());
                }
            }
        }
        return targetFactory;
    }

    private synchronized Connection getConnection(ConnectionFactory factory, String expectedTargetKey)
            throws JMSException {
        boolean injectedLegacyFactory = activeTargetKey == null && targetFactory == factory;
        if (!injectedLegacyFactory && !expectedTargetKey.equals(activeTargetKey)) {
            throw new JMSException("Outbound JMS connection changed while forwarding; retry request");
        }
        if (targetConnection == null) {
            Connection conn = factory.createConnection();
            try {
                conn.start();
                targetConnection = conn;
            } catch (JMSException | RuntimeException e) {
                try {
                    conn.close();
                } catch (Exception closeError) {
                    log.debug("Error closing failed target JMS connection: {}",
                            closeError.getMessage());
                }
                throw e;
            }
        }
        return targetConnection;
    }

    public boolean hasActiveTarget() {
        return targetResolver.get().isPresent();
    }

    private ConnectionFactory createFactory(JmsProperties.Target target) throws Exception {
        String type = target.getType();
        return factoryProviders.stream()
                .filter(p -> p.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unsupported JMS target type: " + type +
                        ". Supported: " + factoryProviders.stream()
                                .map(p -> p.getClass().getSimpleName())
                                .toList()))
                .create(target);
    }

    private String exchange(String body, Message originalMessage, JmsProperties.Target target,
                            Connection connection) throws JMSException {
        String targetQueue = target.getQueue();
        int timeoutMs = target.getTimeoutSeconds() * 1000;
        try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            Queue destQueue = session.createQueue(targetQueue);
            TemporaryQueue replyQueue = session.createTemporaryQueue();
            MessageProducer producer = session.createProducer(destQueue);
            TextMessage forwardMsg = session.createTextMessage(body);
            forwardMsg.setJMSReplyTo(replyQueue);
            try {
                if (originalMessage == null) {
                    throw new IllegalStateException("Original JMS message unavailable");
                }
                forwardMsg.setJMSCorrelationID(originalMessage.getJMSMessageID());
            } catch (Exception e) {
                log.debug("Failed to set JMSCorrelationID: {}", e.getMessage());
            }
            producer.send(forwardMsg);
            log.debug("Forwarded message to target queue: {}", targetQueue);
            MessageConsumer consumer = session.createConsumer(replyQueue);
            Message response = consumer.receive(timeoutMs);
            if (response instanceof TextMessage textMessage) {
                log.debug("Received response from target JMS");
                return textMessage.getText();
            }
            log.warn("Target JMS response timeout or invalid type");
            return "<error>JMS response timeout</error>";
        }
    }

    private TargetClientUse acquireSelectedClient(
            JmsTargetConnectionService.ResolvedTarget resolved) throws Exception {
        while (true) {
            TargetClient current = selectedTargetClients.get(resolved.cacheKey());
            TargetClientUse currentUse = current == null ? null : current.tryUse();
            if (currentUse != null) return currentUse;
            synchronized (selectedTargetClients) {
                current = selectedTargetClients.get(resolved.cacheKey());
                currentUse = current == null ? null : current.tryUse();
                if (currentUse != null) return currentUse;
                retireSupersededClients(resolved.cacheKey());
                TargetClient created = new TargetClient(createFactory(resolved.target()));
                selectedTargetClients.put(resolved.cacheKey(), created);
                log.info("Selected outbound JMS connection: {}", resolved.name());
                return created.tryUse();
            }
        }
    }

    private void retireSupersededClients(String cacheKey) {
        int versionSeparator = cacheKey.lastIndexOf(':');
        if (versionSeparator <= 0) return;
        String profilePrefix = cacheKey.substring(0, versionSeparator + 1);
        selectedTargetClients.entrySet().removeIf(entry -> {
            boolean superseded = !entry.getKey().equals(cacheKey)
                    && entry.getKey().startsWith(profilePrefix);
            if (superseded) entry.getValue().retire();
            return superseded;
        });
    }

    private void resetSelectedClientByCacheKey(String cacheKey, Exception error) {
        if (cacheKey == null) return;
        synchronized (selectedTargetClients) {
            TargetClient client = selectedTargetClients.remove(cacheKey);
            if (client != null) client.retire();
        }
        log.info("Selected JMS connection reset after failure: {}", error.getMessage());
    }

    /** Stops new work on a changed/deleted profile and closes it after active forwards finish. */
    public void evict(Long connectionId) {
        if (connectionId == null) return;
        String prefix = "db:" + connectionId + ":";
        synchronized (selectedTargetClients) {
            selectedTargetClients.entrySet().removeIf(entry -> {
                boolean selected = entry.getKey().startsWith(prefix);
                if (selected) entry.getValue().retire();
                return selected;
            });
        }
    }

    private static final class TargetClient {
        private final ConnectionFactory factory;
        private Connection connection;
        private int users;
        private boolean retired;
        private boolean closed;

        private TargetClient(ConnectionFactory factory) {
            this.factory = factory;
        }

        private synchronized TargetClientUse tryUse() {
            if (retired || closed) return null;
            users++;
            return new TargetClientUse(this);
        }

        private synchronized Connection connection() throws JMSException {
            if (closed) {
                throw new JMSException("Outbound JMS connection is no longer active");
            }
            if (connection == null) {
                Connection created = factory.createConnection();
                try {
                    created.start();
                    connection = created;
                } catch (JMSException | RuntimeException e) {
                    try {
                        created.close();
                    } catch (Exception ignored) {
                        // Preserve the original connection failure.
                    }
                    throw e;
                }
            }
            return connection;
        }

        private synchronized void release() {
            users--;
            if (users < 0) {
                users++;
                throw new IllegalStateException("JMS client use released twice");
            }
            if (retired && users == 0) closeResources();
        }

        private synchronized void retire() {
            retired = true;
            if (users == 0) closeResources();
        }

        private synchronized void close() {
            retired = true;
            closeResources();
        }

        private void closeResources() {
            if (closed) return;
            closed = true;
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // Shutdown/reset must remain best-effort.
                }
                connection = null;
            }
            if (factory instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Shutdown/reset must remain best-effort.
                }
            }
        }
    }

    private static final class TargetClientUse implements AutoCloseable {
        private final TargetClient owner;
        private boolean closed;

        private TargetClientUse(TargetClient owner) {
            this.owner = owner;
        }

        private Connection connection() throws JMSException {
            return owner.connection();
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            owner.release();
        }
    }

    private synchronized void closeActiveForSwitch() {
        if (targetConnection != null) {
            try {
                targetConnection.close();
            } catch (Exception e) {
                log.debug("Error closing previous target JMS connection: {}", e.getMessage());
            }
            targetConnection = null;
        }
        closeFactory();
        activeTargetKey = null;
    }

    private void closeFactory() {
        if (targetFactory instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Error closing target JMS factory: {}", e.getMessage());
            }
        }
        targetFactory = null;
    }

    private static Optional<JmsTargetConnectionService.ResolvedTarget> legacyTarget(
            JmsProperties properties) {
        JmsProperties.Target target = properties.getTarget();
        if (!target.isEnabled() || target.getServerUrl() == null || target.getServerUrl().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new JmsTargetConnectionService.ResolvedTarget(
                JmsTargetConnectionService.LEGACY_ID, "Legacy application.yml", target, true));
    }
}
