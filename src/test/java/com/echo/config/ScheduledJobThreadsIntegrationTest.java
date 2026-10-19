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

/** The background schedule cannot borrow the timing thread of the native HTTP. */
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

        // Go to the same schedule entry actually used by @Scheduled to confirm which execution thread it falls on.
        CompletableFuture<String> thread = new CompletableFuture<>();
        registrar.getScheduler().schedule(() -> thread.complete(Thread.currentThread().getName()), Instant.now());

        assertThat(thread.get(5, TimeUnit.SECONDS)).startsWith("echo-scheduled-").doesNotContain("native-http");
    }

    @Test
    void asyncWorkKeepsSpringBootsOwnExecutor() {
        // Neither the scheduler nor the native HTTP timer is a default candidate, so Boot will create the thread pool used by @Async as usual.
        assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
        assertThat(context.getBean("applicationTaskExecutor")).isInstanceOf(ThreadPoolTaskExecutor.class);
    }
}
