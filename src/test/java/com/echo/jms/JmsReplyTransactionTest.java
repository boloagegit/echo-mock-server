package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.pipeline.JmsMockPipeline;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JmsReplyTransactionTest {
    @ParameterizedTest
    @ValueSource(strings = {"permanent", "unknown", "template", "errorReply"})
    void unavailableRepliesAlwaysReachTheTransactionOwner(String scenario) throws Exception {
        var manager = mock(JmsConnectionManager.class);
        var template = mock(JmsTemplate.class);
        when(manager.getJmsTemplate()).thenReturn(scenario.equals("template") ? null : template);
        var pipeline = mock(JmsMockPipeline.class);
        if (scenario.equals("errorReply")) when(pipeline.execute(any())).thenThrow(new RuntimeException("processing failed"));
        else when(pipeline.execute(any())).thenReturn(PipelineResult.builder().response(
                MockResponse.builder().status(200).body("reply").build()).build());
        Exception cause = scenario.equals("permanent")
                ? new jakarta.jms.InvalidDestinationException("deleted destination")
                : new JMSException("send outcome unknown");
        doThrow(new UncategorizedJmsException(cause)).when(template)
                .send(any(Destination.class), any(MessageCreator.class));
        var request = mock(TextMessage.class);
        when(request.getText()).thenReturn("request");
        when(request.getJMSReplyTo()).thenReturn(mock(Queue.class));
        var budget = new JmsMessageMemoryBudget(1048576, 8);
        var listener = new MockJmsListener(manager, new JmsProperties(), pipeline,
                new JmsEndpointExtractor(), budget);
        var session = mock(Session.class);
        when(session.getTransacted()).thenReturn(true);
        new TestContainer(listener::onMessage).deliver(session, request);
        verify(session).rollback();
        verify(session, never()).commit();
        verify(template, times(scenario.equals("template") ? 0 : 1))
                .send(any(Destination.class), any(MessageCreator.class));
        org.assertj.core.api.Assertions.assertThat(budget.reservedBytes()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "oneway", "reset", "empty"})
    void successfulReplyAndIntentionalNoReplyKeepExistingCommitPolicy(String scenario) throws Exception {
        var manager = mock(JmsConnectionManager.class);
        var template = mock(JmsTemplate.class);
        when(manager.getJmsTemplate()).thenReturn(template);
        var pipeline = mock(JmsMockPipeline.class);
        when(pipeline.execute(any())).thenReturn(PipelineResult.builder().response(
                MockResponse.builder().status(200).body("reply").build())
                .faultType(scenario.equals("reset") ? "CONNECTION_RESET" : scenario.equals("empty") ? "EMPTY_RESPONSE" : null)
                .build());
        var request = mock(TextMessage.class);
        when(request.getText()).thenReturn("request");
        when(request.getJMSReplyTo()).thenReturn(scenario.equals("oneway") ? null : mock(Queue.class));
        var listener = new MockJmsListener(manager, new JmsProperties(), pipeline,
                new JmsEndpointExtractor(), new JmsMessageMemoryBudget(1048576, 8));
        var session = mock(Session.class);
        when(session.getTransacted()).thenReturn(true);
        new TestContainer(listener::onMessage).deliver(session, request);
        verify(session).commit();
        verify(session, never()).rollback();
        verify(template, times(scenario.equals("oneway") || scenario.equals("reset") ? 0 : 1))
                .send(any(Destination.class), any(MessageCreator.class));
    }

    @Test
    void transientReplyFailureRollsBackTheSourceInsteadOfCommittingOrSendingAnotherReply() throws Exception {
        var manager = mock(JmsConnectionManager.class);
        var template = mock(JmsTemplate.class);
        when(manager.getJmsTemplate()).thenReturn(template);
        var pipeline = mock(JmsMockPipeline.class);
        when(pipeline.execute(any())).thenReturn(PipelineResult.builder().response(
                MockResponse.builder().status(200).body("reply").build()).build());
        var listener = new MockJmsListener(manager, new JmsProperties(), pipeline,
                new JmsEndpointExtractor(), new JmsMessageMemoryBudget(1048576, 8));
        var request = mock(TextMessage.class);
        when(request.getText()).thenReturn("request");
        when(request.getJMSReplyTo()).thenReturn(mock(Queue.class));
        var failure = new UncategorizedJmsException(new JMSException("injected transient send failure"));
        doThrow(failure).when(template).send(any(Destination.class), any(MessageCreator.class));
        var session = mock(Session.class);
        when(session.getTransacted()).thenReturn(true);
        var container = new TestContainer(listener::onMessage);
        container.deliver(session, request);
        verify(session).rollback();
        verify(session, never()).commit();
        verify(template, times(1)).send(any(Destination.class), any(MessageCreator.class));
    }

    private static final class TestContainer extends DefaultMessageListenerContainer {
        TestContainer(MessageListener listener) {
            setConnectionFactory(mock(jakarta.jms.ConnectionFactory.class));
            setMessageListener(listener);
            setSessionTransacted(true);
            setAcceptMessagesWhileStopping(true);
        }
        void deliver(Session session, Message message) throws JMSException { executeListener(session, message); }
    }
}
