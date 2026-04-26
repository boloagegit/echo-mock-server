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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class MockJmsListenerExtendedTest {

    @Mock
    private JmsConnectionManager connectionManager;

    @Mock
    private JmsMockPipeline jmsMockPipeline;

    @Mock
    private JmsTemplate jmsTemplate;

    private ConditionMatcher conditionMatcher;
    private MockJmsListener listener;
    private JmsProperties jmsProperties;

    @BeforeEach
    void setUp() {
        conditionMatcher = new ConditionMatcher();
        jmsProperties = new JmsProperties();
        jmsProperties.setQueue("ECHO.REQUEST");
        jmsProperties.setEndpointField("ServiceName");
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline, conditionMatcher);
        lenient().when(connectionManager.getJmsTemplate()).thenReturn(jmsTemplate);
        // Default pipeline mock: return a basic result
        lenient().when(jmsMockPipeline.execute(any())).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("").matched(false).forwarded(false).build())
                        .matched(false).matchTimeMs(0).responseTimeMs(0).delayMs(0).build());
    }

    @Test
    void shouldExtractEndpointFromXmlBody() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("<root><ServiceName>OrderService</ServiceName></root>");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-XML");

        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("<ok/>").matched(true).forwarded(false).build())
                        .ruleId("uuid-xml").matched(true).matchTimeMs(1).responseTimeMs(2).delayMs(0).build());

        listener.onMessage(message);

        // Verify pipeline was called with correct endpointValue
        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(jmsMockPipeline).execute(captor.capture());
        MockRequest captured = captor.getValue();
        assertThat(captured.getEndpointValue()).isEqualTo("OrderService");
        assertThat(captured.getPath()).isEqualTo("ECHO.REQUEST | OrderService");
    }

    @Test
    void shouldExtractEndpointFromJsonBody() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("{\"ServiceName\":\"PaymentService\",\"amount\":100}");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-JSON");

        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("{\"ok\":true}").matched(true).forwarded(false).build())
                        .ruleId("uuid-json").matched(true).matchTimeMs(1).responseTimeMs(2).delayMs(0).build());

        listener.onMessage(message);

        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(jmsMockPipeline).execute(captor.capture());
        MockRequest captured = captor.getValue();
        assertThat(captured.getEndpointValue()).isEqualTo("PaymentService");
        assertThat(captured.getPath()).isEqualTo("ECHO.REQUEST | PaymentService");
    }

    @Test
    void shouldReturnNullEndpoint_whenBodyIsPlainText() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("plain text body");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-PLAIN");

        listener.onMessage(message);

        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(jmsMockPipeline).execute(captor.capture());
        MockRequest captured = captor.getValue();
        assertThat(captured.getEndpointValue()).isNull();
        assertThat(captured.getPath()).isEqualTo("ECHO.REQUEST");
    }

    @Test
    void shouldReturnNullEndpoint_whenEndpointFieldNotInBody() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("{\"otherField\":\"value\"}");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-NOFIELD");

        listener.onMessage(message);

        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(jmsMockPipeline).execute(captor.capture());
        MockRequest captured = captor.getValue();
        assertThat(captured.getEndpointValue()).isNull();
        assertThat(captured.getPath()).isEqualTo("ECHO.REQUEST");
    }

    @Test
    void shouldHandleNonTextMessage() throws Exception {
        BytesMessage message = mock(BytesMessage.class);
        when(message.getJMSReplyTo()).thenReturn(mock(Queue.class));
        when(message.getJMSMessageID()).thenReturn("MSG-BYTES");

        listener.onMessage(message);

        // Non-text message → body is null → pipeline still called
        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(jmsMockPipeline).execute(captor.capture());
        MockRequest captured = captor.getValue();
        assertThat(captured.getBody()).isNull();
        assertThat(captured.getEndpointValue()).isNull();
    }

    @Test
    void shouldHandleExceptionInOnMessage() throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenThrow(new JMSException("read error"));
        when(message.getJMSReplyTo()).thenReturn(mock(Queue.class));
        when(message.getJMSMessageID()).thenReturn("MSG-ERR");

        listener.onMessage(message);

        // Exception path → sendErrorReply called, pipeline NOT called
        verify(jmsMockPipeline, never()).execute(any());
        verify(jmsTemplate).send(any(Destination.class), any(MessageCreator.class));
    }

    @Test
    void shouldHandleDelayInMockResponse() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("<test/>");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-DELAY");

        // Pipeline returns result with delay
        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("<delayed/>").matched(true).forwarded(false).build())
                        .ruleId("uuid-delay").matched(true).matchTimeMs(1).responseTimeMs(2).delayMs(50).build());

        long start = System.currentTimeMillis();
        listener.onMessage(message);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(40);
        verify(jmsTemplate).send(eq(replyTo), any(MessageCreator.class));
    }

    @Test
    void shouldReturnEmptyBody_whenPipelineReturnsEmptyBody() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("<test/>");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-NORESP");

        // Pipeline returns matched result with empty body
        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("").matched(true).forwarded(false).build())
                        .ruleId("uuid-noresp").matched(true).matchTimeMs(1).responseTimeMs(2).delayMs(0).build());

        listener.onMessage(message);

        verify(jmsMockPipeline).execute(any(MockRequest.class));
        verify(jmsTemplate).send(eq(replyTo), any(MessageCreator.class));
    }

    @Test
    void shouldSendForwardedResponse_whenPipelineForwards() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("<test/>");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-FWDERR");

        // Pipeline returns forwarded result with error
        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("<error>connection refused</error>")
                                .matched(false).forwarded(true).proxyError("<error>connection refused</error>").build())
                        .matched(false).matchTimeMs(1).responseTimeMs(5).delayMs(0).build());

        listener.onMessage(message);

        verify(jmsMockPipeline).execute(any(MockRequest.class));
        verify(jmsTemplate).send(eq(replyTo), any(MessageCreator.class));
    }

    @Test
    void shouldNotSendReply_whenJmsTemplateIsNull() throws Exception {
        when(connectionManager.getJmsTemplate()).thenReturn(null);

        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("<test/>");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-NOTPL");

        when(jmsMockPipeline.execute(any(MockRequest.class))).thenReturn(
                PipelineResult.builder()
                        .response(MockResponse.builder().status(200).body("<ok/>").matched(true).forwarded(false).build())
                        .ruleId("uuid-notpl").matched(true).matchTimeMs(1).responseTimeMs(2).delayMs(0).build());

        listener.onMessage(message);

        verify(jmsMockPipeline).execute(any(MockRequest.class));
        verify(jmsTemplate, never()).send(any(Destination.class), any(MessageCreator.class));
    }

    @Test
    void shouldHandleNullEndpointField() throws Exception {
        jmsProperties.setEndpointField(null);

        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("{\"ServiceName\":\"Test\"}");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-NULLFIELD");

        listener.onMessage(message);

        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(jmsMockPipeline).execute(captor.capture());
        MockRequest captured = captor.getValue();
        assertThat(captured.getEndpointValue()).isNull();
    }

    @Test
    void shouldHandleBlankEndpointField() throws Exception {
        jmsProperties.setEndpointField("   ");

        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("{\"ServiceName\":\"Test\"}");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-BLANKFIELD");

        listener.onMessage(message);

        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(jmsMockPipeline).execute(captor.capture());
        MockRequest captured = captor.getValue();
        assertThat(captured.getEndpointValue()).isNull();
    }

    @Test
    void shouldHandleInvalidXmlBody() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("<invalid xml<<<");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-BADXML");

        listener.onMessage(message);

        // Invalid XML → endpoint extraction fails gracefully → pipeline still called
        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(jmsMockPipeline).execute(captor.capture());
        MockRequest captured = captor.getValue();
        assertThat(captured.getEndpointValue()).isNull();
    }

    @Test
    void shouldHandleInvalidJsonBody() throws Exception {
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);

        when(message.getText()).thenReturn("{invalid json}}}");
        when(message.getJMSReplyTo()).thenReturn(replyTo);
        when(message.getJMSMessageID()).thenReturn("MSG-BADJSON");

        listener.onMessage(message);

        // Invalid JSON → endpoint extraction fails gracefully → pipeline still called
        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(jmsMockPipeline).execute(captor.capture());
        MockRequest captured = captor.getValue();
        assertThat(captured.getEndpointValue()).isNull();
    }
}
