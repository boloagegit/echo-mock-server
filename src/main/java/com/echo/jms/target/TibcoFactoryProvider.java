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

            // 檢查 tibjms.jar 是否支援 jakarta.jms namespace（需要 EMS 10.3+）
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
}
