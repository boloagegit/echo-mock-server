package com.echo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Optional capacity protection for the request-log list, without changing pagination. */
@Component
@ConfigurationProperties(prefix = "echo.admin.log-query-admission")
@Getter
@Setter
public class AdminLogQueryProperties {
    private boolean enabled = true;
    private int concurrency = 1;
    private int queueCapacity = 8;
    private long maxWaitMillis = 5000;
}
