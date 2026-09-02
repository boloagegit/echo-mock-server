package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.pipeline.JmsMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import jakarta.jms.*;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQTextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private MockJmsListener listener;
    private JmsProperties jmsProperties;

    @BeforeEach
    void setUp() {
        jmsProperties = new JmsProperties();
        jmsProperties.setQueue("ECHO.REQUEST");
        jmsProperties.setEndpointField("ServiceName");
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline,
                new JmsEndpointExtractor(), new JmsMessageMemoryBudget(64 * 1024 * 1024L, 8));
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
        assertThat(captured.getPreparedBody()).isNull();
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

    @Test
    void shouldHoldMemoryReservationUntilReplyCompletes() throws Exception {
        JmsMessageMemoryBudget budget = new JmsMessageMemoryBudget(64 * 1024 * 1024L, 8);
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline,
                new JmsEndpointExtractor(), budget);
        TextMessage message = mock(TextMessage.class);
        Queue replyTo = mock(Queue.class);
        when(message.getText()).thenReturn("<root><ServiceName>OrderService</ServiceName></root>");
        when(message.getJMSReplyTo()).thenReturn(replyTo);

        when(jmsMockPipeline.execute(any())).thenAnswer(invocation -> {
            assertThat(budget.reservedBytes()).isPositive();
            return PipelineResult.builder()
                    .response(MockResponse.builder().status(200).body("<ok/>")
                            .matched(true).forwarded(false).build())
                    .matched(true).matchTimeMs(1).responseTimeMs(1).delayMs(0).build();
        });
        doAnswer(invocation -> {
            assertThat(budget.reservedBytes()).isPositive();
            return null;
        }).when(jmsTemplate).send(eq(replyTo), any(MessageCreator.class));

        listener.onMessage(message);

        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void oneWayMessageDoesNotReserveUnusedReplyMemory() throws Exception {
        JmsMessageMemoryBudget tinyBudget = new JmsMessageMemoryBudget(256, 1);
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline,
                new JmsEndpointExtractor(), tinyBudget);
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn("<test/>");
        when(message.getJMSReplyTo()).thenReturn(null);
        when(jmsMockPipeline.execute(any())).thenReturn(PipelineResult.builder()
                .response(MockResponse.builder().status(200).body("x".repeat(1_000))
                        .matched(true).forwarded(false).build())
                .matched(true).matchTimeMs(1).responseTimeMs(1).delayMs(0).build());

        listener.onMessage(message);

        assertThat(tinyBudget.reservedBytes()).isZero();
        verify(jmsTemplate, never()).send(any(Destination.class), any(MessageCreator.class));
    }

    @Test
    void connectionResetDoesNotReserveOrSendLargeReply() throws Exception {
        JmsMessageMemoryBudget tinyBudget = new JmsMessageMemoryBudget(256, 1);
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline,
                new JmsEndpointExtractor(), tinyBudget);
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn("<test/>");
        when(message.getJMSReplyTo()).thenReturn(mock(Queue.class));
        when(jmsMockPipeline.execute(any())).thenReturn(PipelineResult.builder()
                .response(MockResponse.builder().status(200).body("x".repeat(1_000))
                        .matched(true).forwarded(false).build())
                .faultType("CONNECTION_RESET")
                .matched(true).matchTimeMs(1).responseTimeMs(1).delayMs(0).build());

        listener.onMessage(message);

        assertThat(tinyBudget.reservedBytes()).isZero();
        verify(jmsTemplate, never()).send(any(Destination.class), any(MessageCreator.class));
    }

    @Test
    void shouldPropagateTemporaryRequestLogFailureForBrokerRedelivery() {
        TextMessage message = mock(TextMessage.class);
        try {
            when(message.getText()).thenReturn("<test/>");
        } catch (JMSException e) {
            throw new AssertionError(e);
        }
        when(jmsMockPipeline.execute(any()))
                .thenThrow(new com.echo.service.RequestLogUnavailableException("disk full"));

        assertThatThrownBy(() -> listener.onMessage(message))
                .isInstanceOf(com.echo.service.RequestLogUnavailableException.class)
                .hasMessageContaining("disk full");
        verify(jmsTemplate, never()).send(any(Destination.class), any(MessageCreator.class));
    }

    @Test
    void shouldRejectSingleMessageLargerThanMemoryBudgetWithoutCallingPipeline() throws Exception {
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn("<root>" + "x".repeat(100) + "</root>");
        JmsMessageMemoryBudget tinyBudget = new JmsMessageMemoryBudget(128, 1);
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline,
                new JmsEndpointExtractor(), tinyBudget);

        listener.onMessage(message);

        verify(jmsMockPipeline, never()).execute(any());
        assertThat(tinyBudget.reservedBytes()).isZero();
    }

    @Test
    void shouldWaitForCapacityBeforeReadingArtemisTextAndResumeAfterRelease() throws Exception {
        JmsMessageMemoryBudget budget = new JmsMessageMemoryBudget(100, 1);
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline,
                new JmsEndpointExtractor(), budget);
        var held = budget.reserveEncodedBody(90);
        ActiveMQTextMessage message = mock(ActiveMQTextMessage.class);
        ClientMessage coreMessage = mock(ClientMessage.class);
        when(message.getCoreMessage()).thenReturn(coreMessage);
        when(coreMessage.getBodySize()).thenReturn(20);
        when(message.getText()).thenReturn("<test/>");
        when(message.getJMSReplyTo()).thenReturn(null);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                listener.onMessage(message);
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                finished.countDown();
            }
        });
        worker.start();

        awaitWaiting(budget);
        verify(message, never()).getText();
        verify(jmsMockPipeline, never()).execute(any());

        held.close();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        worker.join(1000);
        assertThat(failure.get()).isNull();
        verify(message).getText();
        verify(jmsMockPipeline).execute(any());
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void shouldPropagateInterruptionWhileWaitingWithoutForwardingOrReplying() throws Exception {
        JmsMessageMemoryBudget budget = new JmsMessageMemoryBudget(100, 1);
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline,
                new JmsEndpointExtractor(), budget);
        var held = budget.reserveEncodedBody(90);
        ActiveMQTextMessage message = mock(ActiveMQTextMessage.class);
        ClientMessage coreMessage = mock(ClientMessage.class);
        when(message.getCoreMessage()).thenReturn(coreMessage);
        when(coreMessage.getBodySize()).thenReturn(20);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                listener.onMessage(message);
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                finished.countDown();
            }
        });
        worker.start();

        awaitWaiting(budget);
        worker.interrupt();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        worker.join(1000);
        assertThat(failure.get()).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("JMS processing interrupted");
        verify(message, never()).getText();
        verify(jmsMockPipeline, never()).execute(any());
        verify(jmsTemplate, never()).send(any(Destination.class), any(MessageCreator.class));
        held.close();
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void shouldWakeWaitingListenerWhenBudgetShutsDown() throws Exception {
        JmsMessageMemoryBudget budget = new JmsMessageMemoryBudget(100, 1);
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline,
                new JmsEndpointExtractor(), budget);
        var held = budget.reserveEncodedBody(90);
        ActiveMQTextMessage message = mock(ActiveMQTextMessage.class);
        ClientMessage coreMessage = mock(ClientMessage.class);
        when(message.getCoreMessage()).thenReturn(coreMessage);
        when(coreMessage.getBodySize()).thenReturn(20);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                listener.onMessage(message);
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                finished.countDown();
            }
        });
        worker.start();

        awaitWaiting(budget);
        budget.shutdown();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        worker.join(1000);
        assertThat(failure.get()).isInstanceOf(
                JmsMessageMemoryBudget.JmsMessageMemoryBudgetClosedException.class);
        verify(message, never()).getText();
        verify(jmsMockPipeline, never()).execute(any());
        verify(jmsTemplate, never()).send(any(Destination.class), any(MessageCreator.class));
        held.close();
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void artemisMessage_shouldUseCompleteBodySizeBeforeReadingText() {
        ActiveMQTextMessage message = mock(ActiveMQTextMessage.class);
        ClientMessage coreMessage = mock(ClientMessage.class);
        when(message.getCoreMessage()).thenReturn(coreMessage);
        when(coreMessage.getBodySize()).thenReturn(1024);
        when(coreMessage.getBodyBufferSize()).thenReturn(1);
        JmsMessageMemoryBudget tinyBudget = new JmsMessageMemoryBudget(512, 1);
        listener = new MockJmsListener(connectionManager, jmsProperties, jmsMockPipeline,
                new JmsEndpointExtractor(), tinyBudget);

        listener.onMessage(message);

        verify(message, never()).getText();
        verify(jmsMockPipeline, never()).execute(any());
    }

    private static void awaitWaiting(JmsMessageMemoryBudget budget) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (budget.waitingThreads() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(budget.waitingThreads()).isPositive();
    }

}
