package com.echo.config;

import com.echo.diagnostics.TransactionDiagnostics;
import com.echo.pipeline.HttpMockPipeline;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.ServletContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Installs the bounded HTTP/1 adapter without replacing Boot's TLS, compression or shutdown setup. */
@Configuration(proxyBeanMethods = false)
public final class HttpTransportConfiguration {
    public HttpTransportConfiguration(@Value("${echo.http.transport:native}") String transport) {
        if (!Set.of("servlet", "native").contains(transport)) {
            throw new IllegalArgumentException("echo.http.transport must be servlet or native");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "echo.http.transport", havingValue = "native", matchIfMissing = true)
    static class NativeConfiguration {
        @Bean(destroyMethod = "close")
        NativeHttpResources nativeHttpResources(
                @Value("${echo.http.native.workers:${server.undertow.threads.worker:32}}") int workers,
                @Value("${echo.http.native.max-requests:512}") int maxRequests,
                @Value("${echo.http.native.max-body-bytes:10485760}") int maxBodyBytes,
                @Value("${echo.http.native.max-buffered-bytes:134217728}") long maxBufferedBytes,
                @Value("${echo.http.native.worker-idle-timeout-seconds:60}") long workerIdleSeconds) {
            return new NativeHttpResources(workers, maxRequests, maxBodyBytes, maxBufferedBytes,
                    java.time.Duration.ofSeconds(workerIdleSeconds));
        }

        // Do not make this a default candidate: otherwise Spring Boot sees an existing ScheduledExecutorService/Executor and skips creating its own
        // scheduler and applicationTaskExecutor, causing all @Scheduled work to run on these timer threads.
        // Expose it by name only, for the native HTTP entry point below.
        @Bean(destroyMethod = "shutdownNow", defaultCandidate = false)
        ScheduledThreadPoolExecutor nativeHttpScheduler() {
            var scheduler = new ScheduledThreadPoolExecutor(8, runnable -> {
                var thread = new Thread(runnable, "native-http-timer");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            return scheduler;
        }

        @Bean
        WebServerFactoryCustomizer<UndertowServletWebServerFactory> nativeHttpCustomizer(
                ObjectProvider<HttpMockPipeline> pipeline,
                ObjectProvider<TransactionDiagnostics> diagnostics,
                ObjectProvider<MeterRegistry> metrics,
                ObjectProvider<ObservationRegistry> observations,
                ApplicationContext applicationContext,
                ObjectProvider<ServletContext> servletContexts,
                Environment environment,
                NativeHttpResources resources,
                @Qualifier("nativeHttpScheduler") ScheduledThreadPoolExecutor nativeHttpScheduler,
                @Value("${echo.http.request-timeout-ms:40000}") long timeoutMs,
                @Value("${echo.http.native.segmented-forwarding-enabled:true}") boolean segmentedForwarding) {
            if (timeoutMs < 1) throw new IllegalArgumentException("HTTP request timeout must be positive");
            return factory -> factory.addDeploymentInfoCustomizers(info -> info.addInitialHandlerChainWrapper(next ->
                    new NativeMockHttpHandler(next, pipeline, diagnostics, metrics, observations,
                            () -> new NativeHttpCompatibility(applicationContext, servletContexts.getObject(), environment),
                            nativeHttpScheduler, resources, timeoutMs, "native", segmentedForwarding)));
        }
    }
}
