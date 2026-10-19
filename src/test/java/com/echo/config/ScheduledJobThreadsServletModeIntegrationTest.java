package com.echo.config;

import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** When switching back to Servlet mode, the schedule is the same as the thread arrangement and default mode of @Async. */
@TestPropertySource(properties = "echo.http.transport=servlet")
class ScheduledJobThreadsServletModeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledProcessor;

    @Autowired
    private ApplicationContext context;

    @Test
    void servletModeUsesTheSameSchedulerAndKeepsTheAsyncExecutor() throws Exception {
        assertThat(context.containsBean("nativeHttpScheduler")).isFalse();
        assertThat(context.getBean("applicationTaskExecutor")).isInstanceOf(ThreadPoolTaskExecutor.class);

        ScheduledTaskRegistrar registrar = (ScheduledTaskRegistrar) ReflectionTestUtils.getField(scheduledProcessor, "registrar");
        CompletableFuture<String> thread = new CompletableFuture<>();
        registrar.getScheduler().schedule(() -> thread.complete(Thread.currentThread().getName()), Instant.now());
        assertThat(thread.get(5, TimeUnit.SECONDS)).startsWith("echo-scheduled-");
    }
}
