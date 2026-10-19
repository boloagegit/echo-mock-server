package com.echo.jms.target;

import com.echo.config.JmsProperties;
import jakarta.jms.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * IBM MQ ConnectionFactory provider stub.
 * <p>
 * Requires external dependencies: com.ibm.mq:com.ibm.mq.allclient
 * Maven:
 * <pre>
 *   implementation 'com.ibm.mq:com.ibm.mq.allclient:9.3.+'
 * </pre>
 * Alternatively, place the JAR in libs/ and load it through reflection.
 * <p>
 * When creating MQConnectionFactory, you need to set:
 * - hostName / port / channel / queueManager
 * - transportType = WMQConstants.WMQ_CM_CLIENT
 * <p>
 * TODO: To be implemented after obtaining IBM MQ client library
 */
@Component
@Slf4j
public class IbmMqFactoryProvider implements JmsTargetFactoryProvider {

    @Override
    public boolean supports(String type) {
        return "ibm-mq".equalsIgnoreCase(type);
    }

    @Override
    public ConnectionFactory create(JmsProperties.Target target) throws Exception {
        // TODO: Implement IBM MQ ConnectionFactory creation.
        //   Class<?> factoryClass = Class.forName("com.ibm.mq.jms.MQConnectionFactory");
        //   Object factory = factoryClass.getDeclaredConstructor().newInstance();
        //   factoryClass.getMethod("setHostName", String.class).invoke(factory, host);
        //   factoryClass.getMethod("setPort", int.class).invoke(factory, port);
        //   factoryClass.getMethod("setChannel", String.class).invoke(factory, channel);
        //   factoryClass.getMethod("setQueueManager", String.class).invoke(factory, queueManager);
        //   factoryClass.getMethod("setTransportType", int.class).invoke(factory, 1); // WMQ_CM_CLIENT
        throw new UnsupportedOperationException(
                "IBM MQ support not yet implemented. Requires com.ibm.mq.allclient library.");
    }
}
