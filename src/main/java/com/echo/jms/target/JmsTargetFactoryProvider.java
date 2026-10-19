package com.echo.jms.target;

import com.echo.config.JmsProperties;
import jakarta.jms.ConnectionFactory;

/**
 * JMS Target ConnectionFactory Strategy interface.
 * Implement separately for each JMS provider, such as Artemis, TIBCO, or IBM MQ.
 */
public interface JmsTargetFactoryProvider {

    /** Whether the specified type name is supported (case-insensitive) */
    boolean supports(String type);

    /** Create corresponding ConnectionFactory */
    ConnectionFactory create(JmsProperties.Target target) throws Exception;

    /** Outbound-specific limits. Source listener reconnection settings retain their own policy. */
    default ConnectionFactory createOutbound(JmsProperties.Target target) throws Exception {
        return create(target);
    }
}
