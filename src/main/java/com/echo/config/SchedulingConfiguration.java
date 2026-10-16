package com.echo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 背景排程（{@code @Scheduled}）專用的執行緒。
 * <p>
 * 沒有這個 bean 時，Spring 會改用容器裡唯一的 {@code ScheduledExecutorService}，
 * 也就是原生 HTTP 用來處理延遲回應與逾時的 {@code native-http-timer}，
 * 備份、清理這類較久的工作就會和模擬回應搶同一組執行緒。
 * 這裡固定給排程工作自己的執行緒，兩種 HTTP 模式行為一致。
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfiguration {

    @Bean(name = "taskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler taskScheduler(@Value("${echo.scheduling.pool-size:4}") int poolSize) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("echo.scheduling.pool-size must be at least 1");
        }
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // 幾個工作（快取同步、健康檢查）每幾秒執行一次，多於一條執行緒讓夜間備份不會擋住它們。
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("echo-scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
