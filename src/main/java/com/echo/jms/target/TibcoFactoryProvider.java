package com.echo.jms.target;

import com.echo.config.JmsProperties;
import jakarta.jms.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TibcoFactoryProvider implements JmsTargetFactoryProvider {

    @Override
    public boolean supports(String type) {
        return "tibco".equalsIgnoreCase(type);
    }

    @Override
    public ConnectionFactory create(JmsProperties.Target target) throws Exception {
        try {
            Class<?> factoryClass = Class.forName("com.tibco.tibjms.TibjmsConnectionFactory");

            // Check if tibjms.jar supports jakarta.jms namespace (requires EMS 10.3+)
            if (!ConnectionFactory.class.isAssignableFrom(factoryClass)) {
                throw new IllegalStateException(
                        "TIBCO EMS client library (tibjms.jar) uses javax.jms namespace, " +
                        "but this application requires jakarta.jms (Spring Boot 3.x). " +
                        "Please upgrade to TIBCO EMS 10.3+ client library.");
            }

            Object factory = factoryClass.getDeclaredConstructor(String.class)
                    .newInstance(target.getServerUrl());

            if (target.getUsername() != null && !target.getUsername().isBlank()) {
                factoryClass.getMethod("setUserName", String.class).invoke(factory, target.getUsername());
            }
            if (target.getPassword() != null && !target.getPassword().isBlank()) {
                factoryClass.getMethod("setUserPassword", String.class).invoke(factory, target.getPassword());
            }

            log.info("Created TIBCO ConnectionFactory for: {}", target.getServerUrl());
            return (ConnectionFactory) factory;

        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "TIBCO EMS client library (tibjms.jar) not found. Place it in libs/ directory.", e);
        }
    }

    @Override
    public ConnectionFactory createOutbound(JmsProperties.Target target) throws Exception {
        ConnectionFactory factory = create(target);
        try {
            configureOutboundAttempts(factory, target.getTimeoutSeconds() * 1000);
            return factory;
        } catch (Exception failure) {
            if (factory instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    // EMS exposes these per-factory controls. Avoid changing Tibjms' process-wide
    // request timeout, which would also alter the source broker and other factories.
    static void configureOutboundAttempts(Object factory, int timeoutMs) throws ReflectiveOperationException {
        Class<?> type = factory.getClass();
        type.getMethod("setConnAttemptCount", int.class).invoke(factory, 1);
        type.getMethod("setConnAttemptDelay", int.class).invoke(factory, 0);
        type.getMethod("setConnAttemptTimeout", int.class).invoke(factory, timeoutMs);
        type.getMethod("setReconnAttemptCount", int.class).invoke(factory, 1);
        type.getMethod("setReconnAttemptDelay", int.class).invoke(factory, 0);
        type.getMethod("setReconnAttemptTimeout", int.class).invoke(factory, timeoutMs);
    }
}
