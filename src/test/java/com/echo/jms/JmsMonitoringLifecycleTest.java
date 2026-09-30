package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.config.MonitoringProperties;
import com.echo.jms.target.JmsTargetFactoryProvider;
import jakarta.jms.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JmsMonitoringLifecycleTest {
    private JmsRuntimeMetrics metrics;
    private JmsTargetForwarder forwarder;
    private Session session;
    private TemporaryQueue replyQueue;
    private MessageConsumer consumer;
    private MessageProducer producer;
    private Message request;

    @BeforeEach
    void setup() throws Exception {
        metrics = new JmsRuntimeMetrics(new MonitoringProperties());
        var properties = new JmsProperties();
        properties.getTarget().setEnabled(true);
        properties.getTarget().setType("artemis");
        properties.getTarget().setServerUrl("vm://test");
        var provider = mock(JmsTargetFactoryProvider.class);
        var factory = mock(ConnectionFactory.class);
        var connection = mock(Connection.class);
        session = mock(Session.class);
        replyQueue = mock(TemporaryQueue.class);
        consumer = mock(MessageConsumer.class);
        producer = mock(MessageProducer.class);
        request = mock(Message.class);
        when(provider.supports("artemis")).thenReturn(true);
        when(provider.create(any())).thenReturn(factory);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
        when(session.createTemporaryQueue()).thenReturn(replyQueue);
        when(session.createProducer(any())).thenReturn(producer);
        when(session.createTextMessage(anyString())).thenReturn(mock(TextMessage.class));
        when(session.createConsumer(replyQueue)).thenReturn(consumer);
        forwarder = new JmsTargetForwarder(properties, List.of(provider), metrics);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() { forwarder.cleanup(); }

    @Test
    void nullReplyAndNonTextReplyKeepBehaviorButHaveDistinctCounters() throws Exception {
        when(consumer.receive(anyLong())).thenReturn(null, mock(Message.class));
        assertThat(forwarder.forward("{}", request)).contains("JMS response timeout");
        assertThat(forwarder.forward("{}", request)).contains("JMS response timeout");
        assertThat(metrics.snapshot()).containsEntry("receiveTimeouts", 1L).containsEntry("invalidReplies", 1L)
                .containsEntry("forwardExits", 2L).containsEntry("forwardActive", 0);
        verify(replyQueue, times(2)).delete();
    }

    @Test
    void cleanupFailureDoesNotOverwriteReply() throws Exception {
        TextMessage reply = mock(TextMessage.class);
        when(reply.getText()).thenReturn("original-reply");
        when(consumer.receive(anyLong())).thenReturn(reply);
        doThrow(new JMSException("delete failed")).when(replyQueue).delete();
        assertThat(forwarder.forward("{}", request)).isEqualTo("original-reply");
        assertThat(metrics.snapshot()).containsEntry("cleanupFailures", 1L).containsEntry("forwardActive", 0);
    }

    @Test
    void activeCountPersistsUntilCleanupActuallyFinishes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("cleanup latch timed out");
            return null;
        }).when(replyQueue).delete();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> forwarder.forward("{}", request));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(metrics.snapshot()).containsEntry("forwardActive", 1).containsEntry("forwardExits", 0L);
            verify(session, never()).close();
            release.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS)).contains("JMS response timeout");
            assertThat(metrics.snapshot()).containsEntry("forwardActive", 0).containsEntry("forwardExits", 1L);
            verify(session).close();
        } finally { release.countDown(); worker.shutdownNow(); assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void sendFailureFinishesCountersWithoutChangingResetBehavior() throws Exception {
        doThrow(new JMSException("send failed")).when(producer).send(any(Message.class));
        assertThat(forwarder.forward("{}", request)).contains("JMS forward error");
        assertThat(metrics.snapshot()).containsEntry("forwardFailures", 1L).containsEntry("forwardActive", 0);
        verify(replyQueue).delete();
        verify(session).close();
    }

    @Test
    void sessionCloseFailureFinishesCountersAfterQueueDeletion() throws Exception {
        TextMessage reply = mock(TextMessage.class);
        when(reply.getText()).thenReturn("original-reply");
        when(consumer.receive(anyLong())).thenReturn(reply);
        doThrow(new JMSException("session close failed")).when(session).close();
        assertThat(forwarder.forward("{}", request)).contains("JMS forward error");
        assertThat(metrics.snapshot()).containsEntry("forwardFailures", 1L)
                .containsEntry("forwardActive", 0).containsEntry("forwardExits", 1L)
                .containsEntry("cleanupFailures", 0L);
        verify(replyQueue).delete();
        verify(session).close();
    }

    @Test
    void defaultResolverFailureIsCountedOnceAndRethrownUnchanged() {
        var service = mock(com.echo.service.JmsTargetConnectionService.class);
        var failure = new java.lang.IllegalStateException("database unavailable");
        when(service.resolveActive()).thenThrow(failure);
        var monitored = new JmsTargetForwarder(service, List.of(), metrics);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> monitored.forward("{}", request))
                    .isSameAs(failure);
            assertThat(metrics.snapshot()).containsEntry("forwardFailures", 1L)
                    .containsEntry("forwardExits", 1L).containsEntry("forwardActive", 0);
        } finally { monitored.cleanup(); }
    }

    @Test
    void pureMockReplyFailureIsNotReportedAsReplySent() throws Exception {
        var manager = mock(JmsConnectionManager.class);
        var pipeline = mock(com.echo.pipeline.JmsMockPipeline.class);
        var template = mock(org.springframework.jms.core.JmsTemplate.class);
        var message = mock(TextMessage.class);
        var destination = mock(Queue.class);
        when(message.getText()).thenReturn("{}");
        when(message.getJMSReplyTo()).thenReturn(destination);
        when(manager.getJmsTemplate()).thenReturn(template);
        when(pipeline.execute(any())).thenReturn(com.echo.pipeline.PipelineResult.builder().response(
                com.echo.pipeline.MockResponse.builder().body("{}").status(200).build()).build());
        doThrow(new java.lang.IllegalStateException("send failed")).when(template).send(eq(destination), any(org.springframework.jms.core.MessageCreator.class));
        new MockJmsListener(manager, new JmsProperties(), pipeline, new JmsEndpointExtractor(),
                new JmsMessageMemoryBudget(1024 * 1024, 1), metrics).onMessage(message);
        assertThat(metrics.snapshot()).containsEntry("listenerActive", 0).containsEntry("listenerExits", 1L)
                .containsEntry("replyFailures", 1L).containsEntry("replySent", 0L);
    }
}
