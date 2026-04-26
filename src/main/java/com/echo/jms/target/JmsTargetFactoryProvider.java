package com.echo.jms.target;

import com.echo.config.JmsProperties;
import jakarta.jms.ConnectionFactory;

/**
 * JMS Target ConnectionFactory 策略介面。
 * 每種 JMS provider（Artemis、TIBCO、IBM MQ…）各自實作。
 */
public interface JmsTargetFactoryProvider {

    /** 是否支援指定的 type 名稱（不區分大小寫） */
    boolean supports(String type);

    /** 建立對應的 ConnectionFactory */
    ConnectionFactory create(JmsProperties.Target target) throws Exception;
}
