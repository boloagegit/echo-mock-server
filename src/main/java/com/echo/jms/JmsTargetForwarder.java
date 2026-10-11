package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.diagnostics.JmsDiagnosticMetadata;
import com.echo.diagnostics.TransactionDiagnostics.Trace;
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
public final class JmsTargetForwarder {

    private final Supplier<Optional<JmsTargetConnectionService.ResolvedTarget>> targetResolver;
    private final JmsTargetConnectionService connectionService;
    private final List<JmsTargetFactoryProvider> factoryProviders;
    private final JmsRuntimeMetrics metrics;
    private final Map<String, TargetClient> selectedTargetClients = new ConcurrentHashMap<>();
    private volatile ConnectionFactory targetFactory;
    private volatile Connection targetConnection;
    private volatile String activeTargetKey;

    @Autowired
    public JmsTargetForwarder(JmsTargetConnectionService connectionService,
                              List<JmsTargetFactoryProvider> factoryProviders, JmsRuntimeMetrics metrics) {
        this(connectionService::resolveActive, connectionService, factoryProviders, metrics);
    }

    public JmsTargetForwarder(JmsTargetConnectionService connectionService,
                              List<JmsTargetFactoryProvider> factoryProviders) {
        this(connectionService, factoryProviders, null);
    }

    /** Backward-compatible constructor used by existing standalone tests and integrations. */
    public JmsTargetForwarder(JmsProperties jmsProperties,
                              List<JmsTargetFactoryProvider> factoryProviders) {
        this(jmsProperties, factoryProviders, null);
    }

    public JmsTargetForwarder(JmsProperties jmsProperties,
                              List<JmsTargetFactoryProvider> factoryProviders, JmsRuntimeMetrics metrics) {
        this(() -> legacyTarget(jmsProperties), null, factoryProviders, metrics);
    }

    private JmsTargetForwarder(
            Supplier<Optional<JmsTargetConnectionService.ResolvedTarget>> targetResolver,
            JmsTargetConnectionService connectionService,
            List<JmsTargetFactoryProvider> factoryProviders, JmsRuntimeMetrics metrics) {
        this.targetResolver = targetResolver;
        this.connectionService = connectionService;
        this.factoryProviders = factoryProviders;
        this.metrics = metrics;
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
        return forwardWithMetadata(body, originalMessage, Trace.NONE);
    }

    public ForwardResult forwardWithMetadata(String body, Message originalMessage, Trace trace) {
        if (metrics != null) metrics.forwardStarted();
        try {
            return forwardDefaultWithMetadata(body, originalMessage, trace);
        } catch (RuntimeException e) {
            if (metrics != null) metrics.forwardFailed();
            throw e;
        } finally {
            if (metrics != null) metrics.forwardExited();
        }
    }

    private ForwardResult forwardDefaultWithMetadata(String body, Message originalMessage, Trace trace) {
        Optional<JmsTargetConnectionService.ResolvedTarget> selected = targetResolver.get();
        if (selected.isEmpty()) {
            trace.event("FORWARD_UNAVAILABLE", "reason", "NO_DEFAULT_TARGET");
            return new ForwardResult(
                    "<error>No default JMS target connection configured</error>", null);
        }
        JmsTargetConnectionService.ResolvedTarget resolved = selected.get();
        return new ForwardResult(forwardResolved(body, originalMessage, resolved, trace),
                describeTarget(resolved));
    }

    private String forwardResolved(String body, Message originalMessage,
                                   JmsTargetConnectionService.ResolvedTarget resolved, Trace trace) {
        JmsProperties.Target target = resolved.target();
        selected(trace, resolved, target);
        trace.stage("CONNECT");

        try {
            if (connectionService != null) {
                return exchangeSelected(body, originalMessage, resolved, trace);
            }
            ConnectionFactory factory = getOrCreateFactory(resolved);
            return exchange(body, originalMessage, target,
                    getConnection(factory, resolved.cacheKey()),
                    () -> resetConnection(resolved.cacheKey()), trace);

        } catch (JMSException e) {
            trace.failure("FORWARD_FAILED", e);
            if (metrics != null) metrics.forwardFailed();
            if (connectionService == null) {
                resetConnection(resolved.cacheKey());
            }
            if (!trace.enabled()) log.error("Failed to forward to target JMS (connection reset): {}", e.getMessage());
            return "<error>JMS forward error: " + e.getMessage() + "</error>";
        } catch (Exception e) {
            trace.failure("FORWARD_FAILED", e);
            if (metrics != null) metrics.forwardFailed();
            if (!trace.enabled()) log.error("Failed to forward to target JMS: {}", e.getMessage());
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
        return forwardWithMetadata(body, originalMessage, connectionId, useDefaultConnection, Trace.NONE);
    }

    public ForwardResult forwardWithMetadata(String body, Message originalMessage,
                                             String connectionId, boolean useDefaultConnection, Trace trace) {
        if (useDefaultConnection) {
            return forwardWithMetadata(body, originalMessage, trace);
        }
        if (connectionService == null) {
            trace.event("FORWARD_UNAVAILABLE", "reason", "NAMED_TARGET_UNAVAILABLE");
            return new ForwardResult(
                    "<error>Named JMS target connections are unavailable</error>", null);
        }
        JmsTargetConnectionService.ResolvedTarget resolved = null;
        if (metrics != null) metrics.forwardStarted();
        try {
            trace.stage("SELECT_TARGET");
            resolved = connectionService.resolveEnabled(connectionId);
            selected(trace, resolved, resolved.target());
            return new ForwardResult(exchangeSelected(body, originalMessage, resolved, trace),
                    describeTarget(resolved));
        } catch (JMSException e) {
            trace.failure("FORWARD_FAILED", e);
            if (metrics != null) metrics.forwardFailed();
            if (!trace.enabled()) log.error("Failed to forward to selected JMS target (connection reset): {}",
                    e.getMessage());
            return new ForwardResult(
                    "<error>JMS forward error: " + e.getMessage() + "</error>",
                    describeTarget(resolved));
        } catch (Exception e) {
            trace.failure("FORWARD_FAILED", e);
            if (metrics != null) metrics.forwardFailed();
            if (!trace.enabled()) log.error("Failed to forward to selected JMS target: {}", e.getMessage());
            return new ForwardResult(
                    "<error>JMS forward error: " + e.getMessage() + "</error>",
                    resolved == null ? null : describeTarget(resolved));
        } finally {
            if (metrics != null) metrics.forwardExited();
        }
    }

    private static void selected(Trace trace, JmsTargetConnectionService.ResolvedTarget resolved,
                                 JmsProperties.Target target) {
        trace.event("FORWARD_BEGIN", "connectionRevision", resolved.cacheKey(),
                "source", resolved.legacy() ? "CONFIG" : "DATABASE", "provider", target.getType(),
                "queue", target.getQueue(), "timeoutMs", target.getTimeoutSeconds() * 1000L);
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
                            Connection connection, Runnable retireConnection, Trace trace) throws JMSException {
        String targetQueue = target.getQueue();
        long timeoutMs = target.getTimeoutSeconds() * 1000L;
        trace.stage("CREATE_SESSION");
        try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            trace.stage("CREATE_QUEUE");
            Queue destQueue = session.createQueue(targetQueue);
            trace.stage("CREATE_REPLY_QUEUE");
            TemporaryQueue replyQueue = session.createTemporaryQueue();
            trace.stage("CREATE_PRODUCER");
            try (MessageProducer producer = session.createProducer(destQueue)) {
                trace.stage("CREATE_MESSAGE");
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
                trace.stage("SEND");
                producer.send(forwardMsg);
                JmsDiagnosticMetadata.outbound(trace, forwardMsg, replyQueue);
                log.debug("Forwarded message to target queue: {}", targetQueue);
                trace.stage("CREATE_CONSUMER");
                try (MessageConsumer consumer = session.createConsumer(replyQueue)) {
                    trace.stage("RECEIVE_REPLY");
                    long waitStarted = System.nanoTime();
                    Message response = consumer.receive(timeoutMs);
                    long waitMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStarted);
                    if (response instanceof TextMessage textMessage) {
                        JmsDiagnosticMetadata.reply(trace, response, waitMs);
                        log.debug("Received response from target JMS");
                        trace.stage("READ_REPLY");
                        return textMessage.getText();
                    }
                    if (metrics != null) {
                        if (response == null) metrics.receiveTimedOut();
                        else metrics.invalidReply();
                    }
                    if (response == null) {
                        trace.event("REPLY_WAIT_END", "outcome", "NO_REPLY", "waitMs", waitMs,
                                "timeoutMs", timeoutMs, "possibleConsumerClose", waitMs < timeoutMs);
                        if (!trace.enabled()) log.warn("Target JMS receive returned no message (timeout or consumer closed)");
                    } else {
                        JmsDiagnosticMetadata.reply(trace, response, waitMs);
                        if (!trace.enabled()) log.warn("Target JMS returned a non-TextMessage reply");
                    }
                    return "<error>JMS response timeout</error>";
                }
            } finally {
                // Temporary destinations live until the Connection closes, not the Session.
                // Close consumers first, then delete while the creating Session is still open.
                try {
                    replyQueue.delete();
                } catch (JMSException | RuntimeException e) {
                    trace.failure("TEMP_QUEUE_CLEANUP_FAILED", e);
                    if (metrics != null) metrics.cleanupFailed();
                    // Preserve the downstream result/original error. Retiring the connection
                    // releases orphan destinations; selected clients wait for other active uses.
                    if (!trace.enabled()) log.warn("Failed to delete temporary JMS reply queue; retiring connection: {}",
                            e.getMessage());
                    retireConnection.run();
                }
            }
        }
    }

    private void retireSelectedClient(String cacheKey, TargetClientUse client) {
        synchronized (selectedTargetClients) {
            // A late cleanup failure must not retire a replacement for the same profile.
            selectedTargetClients.remove(cacheKey, client.owner);
            client.owner.retire();
        }
    }

    private String exchangeSelected(String body, Message originalMessage,
                                    JmsTargetConnectionService.ResolvedTarget resolved, Trace trace) throws Exception {
        trace.stage("CONNECT");
        try (TargetClientUse client = acquireSelectedClient(resolved)) {
            try {
                return exchange(body, originalMessage, resolved.target(), client.connection(),
                        () -> retireSelectedClient(resolved.cacheKey(), client), trace);
            } catch (JMSException e) {
                // Reset the actual failed client, not a newer replacement with the same cache key.
                retireSelectedClient(resolved.cacheKey(), client);
                throw e;
            }
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
