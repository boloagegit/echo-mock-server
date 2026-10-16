package com.echo.protocol.jms;

import com.echo.config.JmsProperties;
import com.echo.entity.JmsRule;
import com.echo.jms.JmsConnectionManager;
import com.echo.repository.JmsRuleRepository;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Exercises real Spring request/reply timeout selection with isolated JMS API resources. */
class JmsProtocolHandlerTimeoutTest {
    private final ConnectionFactory factory = mock(ConnectionFactory.class);
    private final JmsConnectionManager manager = new JmsConnectionManager(new JmsProperties(), factory);
    private final JmsProtocolHandler handler = new JmsProtocolHandler(mock(JmsRuleRepository.class), manager);
    private final JmsRule rule = JmsRule.builder().queueName("TIMEOUT.REQUEST").build();

    @Test
    void concurrentTestsKeepTheirOwnTimeoutAndLeaveSharedTemplateUnchanged() throws Exception {
        var first = new Exchange("first", 1_000);
        var second = new Exchange("second", 2_000);
        when(factory.createConnection()).thenReturn(first.connection, second.connection);
        manager.init();
        manager.getJmsTemplate().setReceiveTimeout(12_345);

        var firstSent = new CountDownLatch(1);
        var resumeFirst = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstSent.countDown();
            assertThat(resumeFirst.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(first.producer).send(first.request);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstCall = executor.submit(() -> handler.testRule(rule, Map.of("body", "first", "timeout", 1)));
            assertThat(firstSent.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                var secondCall = executor.submit(() -> handler.testRule(rule, Map.of("body", "second", "timeout", 2)));
                assertThat(secondCall.get(5, TimeUnit.SECONDS)).containsEntry("status", 200)
                        .containsEntry("body", "reply-second");
            } finally {
                resumeFirst.countDown();
            }
            assertThat(firstCall.get(5, TimeUnit.SECONDS)).containsEntry("status", 200)
                    .containsEntry("body", "reply-first");
            verify(first.consumer).receive(1_000);
            verify(second.consumer).receive(2_000);
            assertThat(manager.getJmsTemplate().getReceiveTimeout()).isEqualTo(12_345);
            first.verifyClosed();
            second.verifyClosed();
        } finally {
            resumeFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void keepsDefaultThirtySecondTimeout() throws Exception {
        var exchange = new Exchange("default", 30_000);
        when(factory.createConnection()).thenReturn(exchange.connection);
        manager.init();

        assertThat(handler.testRule(rule, Map.of("body", "default"))).containsEntry("status", 200)
                .containsEntry("body", "reply-default");

        verify(exchange.consumer).receive(30_000);
        exchange.verifyClosed();
    }

    @Test
    void disconnectedTestsDoNotAcquireJmsResources() {
        assertThat(handler.testRule(rule, Map.of("body", "request", "timeout", 1)))
                .containsEntry("status", 503);
        verifyNoInteractions(factory);
    }

    private static final class Exchange {
        final Connection connection = mock(Connection.class);
        final Session session = mock(Session.class);
        final TemporaryQueue replyQueue = mock(TemporaryQueue.class);
        final MessageProducer producer = mock(MessageProducer.class);
        final MessageConsumer consumer = mock(MessageConsumer.class);
        final TextMessage request = mock(TextMessage.class);

        Exchange(String body, long timeoutMs) throws Exception {
            var destination = mock(Queue.class);
            var reply = mock(TextMessage.class);
            when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
            when(session.createQueue("TIMEOUT.REQUEST")).thenReturn(destination);
            when(session.createTextMessage(body)).thenReturn(request);
            when(session.createTemporaryQueue()).thenReturn(replyQueue);
            when(session.createProducer(destination)).thenReturn(producer);
            when(session.createConsumer(replyQueue)).thenReturn(consumer);
            when(consumer.receive(timeoutMs)).thenReturn(reply);
            when(reply.getText()).thenReturn("reply-" + body);
        }

        void verifyClosed() throws Exception {
            verify(consumer).close();
            verify(producer).close();
            verify(replyQueue).delete();
            verify(session).close();
            verify(connection).close();
        }
    }
}
