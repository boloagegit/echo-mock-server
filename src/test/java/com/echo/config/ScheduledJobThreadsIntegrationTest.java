package com.echo.config;

import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 背景排程不可借用原生 HTTP 的計時執行緒。 */
class ScheduledJobThreadsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledProcessor;

    @Autowired
    private ApplicationContext context;

    @Test
    void scheduledJobsRunOnTheirOwnThreadsNotTheNativeHttpTimer() throws Exception {
        ScheduledTaskRegistrar registrar = (ScheduledTaskRegistrar) ReflectionTestUtils.getField(scheduledProcessor, "registrar");
        assertThat(registrar).isNotNull();
        assertThat(scheduledProcessor.getScheduledTasks()).isNotEmpty();

        // 走 @Scheduled 實際使用的同一個排程入口，確認落在哪條執行緒。
        CompletableFuture<String> thread = new CompletableFuture<>();
        registrar.getScheduler().schedule(() -> thread.complete(Thread.currentThread().getName()), Instant.now());

        assertThat(thread.get(5, TimeUnit.SECONDS)).startsWith("echo-scheduled-").doesNotContain("native-http");
    }

    @Test
    void asyncWorkKeepsSpringBootsOwnExecutor() {
        // 排程器與原生 HTTP 計時器都不是預設候選，Boot 才會照常建立 @Async 用的執行緒池。
        assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
        assertThat(context.getBean("applicationTaskExecutor")).isInstanceOf(ThreadPoolTaskExecutor.class);
    }
}
