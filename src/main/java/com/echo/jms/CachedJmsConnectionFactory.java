package com.echo.jms;

import jakarta.jms.ConnectionFactory;
import org.springframework.jms.connection.CachingConnectionFactory;

/**
 * Spring lends each Session exclusively until close(), then caches a bounded number of idle
 * sessions and their fixed-destination producers. Reply consumers/temporary queues are NEVER
 * cached: an uncorrelated late or duplicate reply must not become the next request's answer.
 */
final class CachedJmsConnectionFactory extends CachingConnectionFactory implements AutoCloseable {
    private final ConnectionFactory delegate;

    CachedJmsConnectionFactory(ConnectionFactory delegate, int cacheSize) {
        super(delegate);
        this.delegate = delegate;
        setSessionCacheSize(cacheSize);
        setCacheConsumers(false);
        // The forwarder retires failed clients after their active users finish. An asynchronous
        // Spring reset would instead close sessions underneath those users.
        setReconnectOnException(false);
    }

    @Override
    public void close() throws Exception {
        try {
            destroy();
        } finally {
            if (delegate instanceof AutoCloseable closeable) closeable.close();
        }
    }
}
