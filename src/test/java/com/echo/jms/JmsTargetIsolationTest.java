package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.service.JmsTargetConnectionService;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/** Provider calls may stall, but must never hold the map shared by unrelated targets. */
@ExtendWith(MockitoExtension.class)
class JmsTargetIsolationTest {

    @Test
    void closingAnIdleTargetDoesNotBlockAnotherNewTarget() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection closing = fixture.target("7", "db:7:0", "first");
            fixture.target("8", "db:8:0", "healthy");
            fixture.target("9", "db:9:0", "new");
            assertThat(fixture.forward("7")).isEqualTo("first");
            assertThat(fixture.forward("8")).isEqualTo("healthy");

            CountDownLatch entered = new CountDownLatch(1);
            doAnswer(call -> {
                entered.countDown();
                fixture.awaitRelease();
                return null;
            }).when(closing).close();
            var eviction = fixture.workers.submit(() -> fixture.forwarder.evict(7L));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            assertThat(fixture.forward("8")).isEqualTo("healthy");
            assertThat(fixture.workers.submit(() -> fixture.forward("9")).get(2, TimeUnit.SECONDS))
                    .isEqualTo("new");
            fixture.release.countDown();
            eviction.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void creatingAProviderFactoryDoesNotBlockAnotherNewTarget() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.target("7", "db:7:0", "slow");
            fixture.target("9", "db:9:0", "new");
            ConnectionFactory slow = fixture.factories.get("7");
            CountDownLatch entered = new CountDownLatch(1);
            String slowUrl = fixture.targets.get("7").getServerUrl();
            doAnswer(call -> {
                entered.countDown();
                fixture.awaitRelease();
                return slow;
            }).when(fixture.provider).create(argThat(target -> target != null
                    && slowUrl.equals(target.getServerUrl())));
            var connecting = fixture.workers.submit(() -> fixture.forward("7"));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            assertThat(fixture.workers.submit(() -> fixture.forward("9")).get(2, TimeUnit.SECONDS))
                    .isEqualTo("new");
            fixture.release.countDown();
            assertThat(connecting.get(3, TimeUnit.SECONDS)).isEqualTo("slow");
        }
    }

    @Test
    void closingASupersededRevisionDoesNotBlockAnotherNewTarget() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection closing = fixture.target("7", "db:7:0", "old");
            assertThat(fixture.forward("7")).isEqualTo("old");
            fixture.target("7", "db:7:1", "updated");
            fixture.target("9", "db:9:0", "new");
            CountDownLatch entered = new CountDownLatch(1);
            doAnswer(call -> {
                entered.countDown();
                fixture.awaitRelease();
                return null;
            }).when(closing).close();
            var switching = fixture.workers.submit(() -> fixture.forward("7"));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            assertThat(fixture.workers.submit(() -> fixture.forward("9")).get(2, TimeUnit.SECONDS))
                    .isEqualTo("new");
            fixture.release.countDown();
            assertThat(switching.get(3, TimeUnit.SECONDS)).isEqualTo("updated");
        }
    }

    @Test
    void factoryInitializationFailureCanRetryWithoutLeakingAnActiveUse() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = fixture.target("7", "db:7:0", "recovered");
            String url = fixture.targets.get("7").getServerUrl();
            fixture.initializationFailures.put(url, new Exception("temporary factory initialization failure"));

            assertThat(fixture.forward("7")).contains("temporary factory initialization failure");
            assertThat(fixture.forward("7")).isEqualTo("recovered");
            fixture.forwarder.evict(7L);
            verify(connection).close();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final JmsTargetConnectionService service = mock(JmsTargetConnectionService.class);
        private final JmsTargetFactoryProvider provider = mock(JmsTargetFactoryProvider.class);
        private final Map<String, ConnectionFactory> factories = new HashMap<>();
        private final Map<String, ConnectionFactory> factoriesByUrl = new HashMap<>();
        private final Map<String, Exception> initializationFailures = new ConcurrentHashMap<>();
        private final Map<String, JmsProperties.Target> targets = new HashMap<>();
        private final CountDownLatch release = new CountDownLatch(1);
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private final JmsTargetForwarder forwarder;

        private Fixture() throws Exception {
            when(provider.supports("tibco")).thenReturn(true);
            when(provider.create(any())).thenAnswer(call -> {
                String url = ((JmsProperties.Target) call.getArgument(0)).getServerUrl();
                Exception failure = initializationFailures.remove(url);
                if (failure != null) {
                    throw failure;
                }
                return factoriesByUrl.get(url);
            });
            forwarder = new JmsTargetForwarder(service, List.of(provider));
            forwarder.configureResources(new JmsProperties());
        }

        private Connection target(String id, String revision, String reply) throws Exception {
            var target = new JmsProperties.Target();
            target.setType("tibco");
            target.setServerUrl("tcp://test-" + revision + ":7222");
            target.setTimeoutSeconds(1);
            when(service.resolveEnabled(id)).thenReturn(
                    new JmsTargetConnectionService.ResolvedTarget(revision, id, target, false));
            var factory = mock(ConnectionFactory.class);
            var connection = mock(Connection.class);
            when(factory.createConnection()).thenReturn(connection);
            var session = mock(Session.class);
            var destination = mock(Queue.class);
            var replyQueue = mock(TemporaryQueue.class);
            var producer = mock(MessageProducer.class);
            var consumer = mock(MessageConsumer.class);
            var response = mock(TextMessage.class);
            when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
            when(session.createQueue("TARGET.REQUEST")).thenReturn(destination);
            when(session.createTemporaryQueue()).thenReturn(replyQueue);
            when(session.createProducer(destination)).thenReturn(producer);
            when(session.createTextMessage(any())).thenReturn(mock(TextMessage.class));
            when(session.createConsumer(replyQueue)).thenReturn(consumer);
            when(consumer.receive(1_000)).thenReturn(response);
            when(response.getText()).thenReturn(reply);
            factories.put(id, factory);
            factoriesByUrl.put(target.getServerUrl(), factory);
            targets.put(id, target);
            return connection;
        }

        private String forward(String id) {
            return forwarder.forward("request", null, id, false);
        }

        private void awaitRelease() throws InterruptedException {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test to release the provider");
            }
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            workers.shutdown();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
            forwarder.cleanup();
        }
    }
}
