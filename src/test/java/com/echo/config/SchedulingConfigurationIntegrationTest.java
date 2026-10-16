package com.echo.config;

import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 背景排程不可借用原生 HTTP 的計時執行緒。 */
class SchedulingConfigurationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledProcessor;

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
}
