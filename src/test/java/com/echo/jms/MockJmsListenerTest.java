package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.pipeline.JmsMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import com.echo.service.ConditionMatcher;
import jakarta.jms.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MockJmsListenerTest {

    @Mock
    private JmsConnectionManager connectionManager;

    @Mock
    private JmsMockPipeline jmsMockPipeline;

    @Mock
    private ConditionMatcher conditionMatcher;

    @Mock
    private JmsTemplate jmsTemplate;

    private MockJmsListener listener;
    private JmsProperties jmsProperties;

    @BeforeEach
    void setUp() {
        jmsProperties = new JmsProperties();
        jmsProperties.setQueue("ECHO.REQUEST");
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline, conditionMatcher);
    }

    @Test
    void shouldReturnMockResponse_whenRuleExists() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("<order>test</order>");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(connectionManager.getJmsTemplate()).thenReturn(jmsTemplate);
        when(conditionMatcher.prepareBody(any())).thenReturn(ConditionMatcher.PreparedBody.empty());

        // Pipeline returns a matched result with response body
        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("<order>OK</order>").matched(true).forwarded(false).build())
                        .ruleId("uuid-1").matched(true).matchTimeMs(1).responseTimeMs(2).delayMs(0).build());

        listener.onMessage(message);

        // Verify pipeline was called
        verify(jmsMockPipeline).execute(any(MockRequest.class));
        // Verify reply was sent
        verify(jmsTemplate).send(eq(replyTo), any(MessageCreator.class));
    }

    @Test
    void shouldForwardToTarget_whenNoRuleAndTargetEnabled() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("<order>test</order>");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(connectionManager.getJmsTemplate()).thenReturn(jmsTemplate);
        when(conditionMatcher.prepareBody(any())).thenReturn(ConditionMatcher.PreparedBody.empty());

        // Pipeline returns a forwarded result
        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("<response>from JMS</response>").matched(false).forwarded(true).build())
                        .matched(false).matchTimeMs(1).responseTimeMs(5).delayMs(0).build());

        listener.onMessage(message);

        verify(jmsMockPipeline).execute(any(MockRequest.class));
        verify(jmsTemplate).send(eq(replyTo), any(MessageCreator.class));
    }

    @Test
    void shouldSendError_whenNoRuleAndTargetDisabled() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("<order>test</order>");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(connectionManager.getJmsTemplate()).thenReturn(jmsTemplate);
        when(conditionMatcher.prepareBody(any())).thenReturn(ConditionMatcher.PreparedBody.empty());

        // Pipeline returns a no-match result
        String errorBody = "<error>No mock rule found for queue: ECHO.REQUEST</error>";
        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body(errorBody).matched(false).forwarded(false).build())
                        .matched(false).matchTimeMs(1).responseTimeMs(2).delayMs(0).build());

        listener.onMessage(message);

        verify(jmsMockPipeline).execute(any(MockRequest.class));
        verify(jmsTemplate).send(eq(replyTo), any(MessageCreator.class));
    }

    @Test
    void shouldUseResponseFromResponseId_whenConfigured() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("<order>test</order>");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(connectionManager.getJmsTemplate()).thenReturn(jmsTemplate);
        when(conditionMatcher.prepareBody(any())).thenReturn(ConditionMatcher.PreparedBody.empty());

        // Pipeline returns a matched result with response body from DB
        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("<response>from-db</response>").matched(true).forwarded(false).build())
                        .ruleId("uuid-2").matched(true).matchTimeMs(1).responseTimeMs(2).delayMs(0).build());

        listener.onMessage(message);

        verify(jmsMockPipeline).execute(any(MockRequest.class));
        verify(jmsTemplate).send(eq(replyTo), any(MessageCreator.class));
    }

    @Test
    void shouldNotSendReply_whenNoReplyTo() throws Exception {
        TextMessage message = mock(TextMessage.class);

        when(message.getText()).thenReturn("<order>test</order>");
        when(message.getJMSReplyTo()).thenReturn(null);
        when(conditionMatcher.prepareBody(any())).thenReturn(ConditionMatcher.PreparedBody.empty());

        // Pipeline returns a matched result
        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("<ok/>").matched(true).forwarded(false).build())
                        .ruleId("uuid-3").matched(true).matchTimeMs(1).responseTimeMs(2).delayMs(0).build());

        listener.onMessage(message);

        verify(jmsMockPipeline).execute(any(MockRequest.class));
        verify(jmsTemplate, never()).send(any(Destination.class), any(MessageCreator.class));
    }
}
