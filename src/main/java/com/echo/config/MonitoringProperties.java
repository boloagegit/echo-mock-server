package com.echo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Controls only the resource snapshot feature, never resource safety limits. */
@Component
@ConfigurationProperties(prefix = "echo.monitoring")
@Getter
@Setter
public class MonitoringProperties {
    private boolean enabled = true;
    private boolean jvmEnabled = true;
    private boolean cachesEnabled = true;
    private boolean schedulerEnabled = true;
    private boolean jmsEnabled = true;
    private boolean httpEnabled = true;
    private boolean databaseEnabled = true;
    private boolean requestLogEnabled = true;
    private boolean applicationLogEnabled = true;
    private boolean storageEnabled = true;
}
