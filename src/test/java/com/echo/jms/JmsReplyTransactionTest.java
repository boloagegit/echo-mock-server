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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JmsReplyTransactionTest {
    enum Policy { DEFAULT, DISABLED, ENABLED }

    static Stream<Arguments> replyFailures() {
        return Stream.of(Policy.values()).flatMap(policy ->
                Stream.of("transient", "permanent", "unknown", "template", "errorReply", "empty")
                        .map(scenario -> Arguments.of(policy, scenario)));
    }

    static Stream<Arguments> successfulReplies() {
        return Stream.of(Policy.values()).flatMap(policy ->
                Stream.of("success", "oneway", "reset", "empty", "errorReply")
                        .map(scenario -> Arguments.of(policy, scenario)));
    }

    @ParameterizedTest
    @MethodSource("replyFailures")
    void replyFailureRollsBackOnlyWhenEnabled(Policy policy, String scenario) throws Exception {
        var manager = mock(JmsConnectionManager.class);
        var template = mock(JmsTemplate.class);
        when(manager.getJmsTemplate()).thenReturn(scenario.equals("template") ? null : template);
        var pipeline = mock(JmsMockPipeline.class);
        if (scenario.equals("errorReply")) when(pipeline.execute(any())).thenThrow(new RuntimeException("processing failed"));
        else when(pipeline.execute(any())).thenReturn(PipelineResult.builder().response(
                MockResponse.builder().status(200).body("reply").build())
                .faultType(scenario.equals("empty") ? "EMPTY_RESPONSE" : null).build());
        JMSException cause = switch (scenario) {
            case "permanent" -> new jakarta.jms.InvalidDestinationException("deleted destination");
            case "transient" -> new jakarta.jms.ResourceAllocationException("temporary capacity failure");
            default -> new JMSException("send outcome unknown");
        };
        doThrow(new UncategorizedJmsException(cause)).when(template)
                .send(any(Destination.class), any(MessageCreator.class));
        var request = mock(TextMessage.class);
        when(request.getText()).thenReturn("request");
        when(request.getJMSReplyTo()).thenReturn(mock(Queue.class));
        var budget = new JmsMessageMemoryBudget(1048576, 8);
        var listener = new MockJmsListener(manager, properties(policy), pipeline,
                new JmsEndpointExtractor(), budget);
        var session = mock(Session.class);
        when(session.getTransacted()).thenReturn(true);
        new TestContainer(listener::onMessage).deliver(session, request);
        verify(session, times(policy == Policy.ENABLED ? 1 : 0)).rollback();
        verify(session, times(policy == Policy.ENABLED ? 0 : 1)).commit();
        verify(template, times(scenario.equals("template") ? 0 : 1))
                .send(any(Destination.class), any(MessageCreator.class));
        assertThat(budget.reservedBytes()).isZero();
    }

    @ParameterizedTest
    @MethodSource("successfulReplies")
    void successfulReplyAndIntentionalNoReplyKeepExistingCommitPolicy(Policy policy, String scenario) throws Exception {
        var manager = mock(JmsConnectionManager.class);
        var template = mock(JmsTemplate.class);
        when(manager.getJmsTemplate()).thenReturn(template);
        var pipeline = mock(JmsMockPipeline.class);
        if (scenario.equals("errorReply")) when(pipeline.execute(any())).thenThrow(new RuntimeException("processing failed"));
        else when(pipeline.execute(any())).thenReturn(PipelineResult.builder().response(
                MockResponse.builder().status(200).body("reply").build())
                .faultType(scenario.equals("reset") ? "CONNECTION_RESET" : scenario.equals("empty") ? "EMPTY_RESPONSE" : null)
                .build());
        var request = mock(TextMessage.class);
        when(request.getText()).thenReturn("request");
        when(request.getJMSReplyTo()).thenReturn(scenario.equals("oneway") ? null : mock(Queue.class));
        var budget = new JmsMessageMemoryBudget(1048576, 8);
        var listener = new MockJmsListener(manager, properties(policy), pipeline,
                new JmsEndpointExtractor(), budget);
        var session = mock(Session.class);
        when(session.getTransacted()).thenReturn(true);
        new TestContainer(listener::onMessage).deliver(session, request);
        verify(session).commit();
        verify(session, never()).rollback();
        verify(template, times(scenario.equals("oneway") || scenario.equals("reset") ? 0 : 1))
                .send(any(Destination.class), any(MessageCreator.class));
        assertThat(budget.reservedBytes()).isZero();
    }

    private static JmsProperties properties(Policy policy) {
        var properties = new JmsProperties();
        if (policy != Policy.DEFAULT) {
            new Binder(new MapConfigurationPropertySource(Map.of(
                    "echo.jms.rollback-on-reply-failure", policy == Policy.ENABLED)))
                    .bind("echo.jms", Bindable.ofInstance(properties));
        }
        return properties;
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
