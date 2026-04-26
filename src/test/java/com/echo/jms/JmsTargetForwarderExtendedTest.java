package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.ArtemisFactoryProvider;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.jms.target.TibcoFactoryProvider;
import jakarta.jms.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JmsTargetForwarderExtendedTest {

    private JmsTargetForwarder forwarder;
    private JmsProperties jmsProperties;
    private final List<JmsTargetFactoryProvider> providers = List.of(
            new ArtemisFactoryProvider(), new TibcoFactoryProvider());

    @Mock private ConnectionFactory mockFactory;
    @Mock private Connection mockConnection;
    @Mock private Session mockSession;
    @Mock private Queue mockQueue;
    @Mock private TemporaryQueue mockTempQueue;
    @Mock private MessageProducer mockProducer;
    @Mock private MessageConsumer mockConsumer;
    @Mock private TextMessage mockForwardMsg;
    @Mock private Message originalMessage;

    @BeforeEach
    void setUp() {
        jmsProperties = new JmsProperties();
        jmsProperties.getTarget().setEnabled(true);
        jmsProperties.getTarget().setType("artemis");
        jmsProperties.getTarget().setServerUrl("tcp://localhost:61617");
        jmsProperties.getTarget().setQueue("TARGET.REQUEST");
        jmsProperties.getTarget().setTimeoutSeconds(5);

        forwarder = new JmsTargetForwarder(jmsProperties, providers);
    }

    @Test
    void forward_shouldReturnResponse_whenSuccessful() throws Exception {
        ReflectionTestUtils.setField(forwarder, "targetFactory", mockFactory);
        ReflectionTestUtils.setField(forwarder, "targetConnection", mockConnection);

        when(mockConnection.createSession(anyBoolean(), anyInt())).thenReturn(mockSession);
        when(mockSession.createQueue("TARGET.REQUEST")).thenReturn(mockQueue);
        when(mockSession.createTemporaryQueue()).thenReturn(mockTempQueue);
        when(mockSession.createProducer(mockQueue)).thenReturn(mockProducer);
        when(mockSession.createTextMessage("<request/>")).thenReturn(mockForwardMsg);
        when(mockSession.createConsumer(mockTempQueue)).thenReturn(mockConsumer);

        TextMessage responseMsg = mock(TextMessage.class);
        when(responseMsg.getText()).thenReturn("<response>OK</response>");
        when(mockConsumer.receive(5000)).thenReturn(responseMsg);

        String result = forwarder.forward("<request/>", originalMessage);

        assertThat(result).isEqualTo("<response>OK</response>");
        verify(mockProducer).send(mockForwardMsg);
    }

    @Test
    void forward_shouldReturnTimeout_whenNoResponse() throws Exception {
        ReflectionTestUtils.setField(forwarder, "targetFactory", mockFactory);
        ReflectionTestUtils.setField(forwarder, "targetConnection", mockConnection);

        when(mockConnection.createSession(anyBoolean(), anyInt())).thenReturn(mockSession);
        when(mockSession.createQueue("TARGET.REQUEST")).thenReturn(mockQueue);
        when(mockSession.createTemporaryQueue()).thenReturn(mockTempQueue);
        when(mockSession.createProducer(mockQueue)).thenReturn(mockProducer);
        when(mockSession.createTextMessage(any())).thenReturn(mockForwardMsg);
        when(mockSession.createConsumer(mockTempQueue)).thenReturn(mockConsumer);
        when(mockConsumer.receive(5000)).thenReturn(null);

        String result = forwarder.forward("<request/>", originalMessage);

        assertThat(result).isEqualTo("<error>JMS response timeout</error>");
    }

    @Test
    void forward_shouldResetConnection_onJMSException() throws Exception {
        ReflectionTestUtils.setField(forwarder, "targetFactory", mockFactory);
        ReflectionTestUtils.setField(forwarder, "targetConnection", mockConnection);

        when(mockConnection.createSession(anyBoolean(), anyInt())).thenThrow(new JMSException("broken"));

        String result = forwarder.forward("<request/>", originalMessage);

        assertThat(result).contains("<error>").contains("JMS forward error");
        assertThat(ReflectionTestUtils.getField(forwarder, "targetConnection")).isNull();
    }

    @Test
    void forward_shouldSetCorrelationId() throws Exception {
        ReflectionTestUtils.setField(forwarder, "targetFactory", mockFactory);
        ReflectionTestUtils.setField(forwarder, "targetConnection", mockConnection);

        when(mockConnection.createSession(anyBoolean(), anyInt())).thenReturn(mockSession);
        when(mockSession.createQueue("TARGET.REQUEST")).thenReturn(mockQueue);
        when(mockSession.createTemporaryQueue()).thenReturn(mockTempQueue);
        when(mockSession.createProducer(mockQueue)).thenReturn(mockProducer);
        when(mockSession.createTextMessage(any())).thenReturn(mockForwardMsg);
        when(mockSession.createConsumer(mockTempQueue)).thenReturn(mockConsumer);
        when(originalMessage.getJMSMessageID()).thenReturn("ID:msg-123");

        TextMessage responseMsg = mock(TextMessage.class);
        when(responseMsg.getText()).thenReturn("<ok/>");
        when(mockConsumer.receive(anyLong())).thenReturn(responseMsg);

        forwarder.forward("<body/>", originalMessage);

        verify(mockForwardMsg).setJMSCorrelationID("ID:msg-123");
    }

    @Test
    void forward_shouldHandleCorrelationIdException() throws Exception {
        ReflectionTestUtils.setField(forwarder, "targetFactory", mockFactory);
        ReflectionTestUtils.setField(forwarder, "targetConnection", mockConnection);

        when(mockConnection.createSession(anyBoolean(), anyInt())).thenReturn(mockSession);
        when(mockSession.createQueue("TARGET.REQUEST")).thenReturn(mockQueue);
        when(mockSession.createTemporaryQueue()).thenReturn(mockTempQueue);
        when(mockSession.createProducer(mockQueue)).thenReturn(mockProducer);
        when(mockSession.createTextMessage(any())).thenReturn(mockForwardMsg);
        when(mockSession.createConsumer(mockTempQueue)).thenReturn(mockConsumer);
        when(originalMessage.getJMSMessageID()).thenThrow(new JMSException("no id"));

        TextMessage responseMsg = mock(TextMessage.class);
        when(responseMsg.getText()).thenReturn("<ok/>");
        when(mockConsumer.receive(anyLong())).thenReturn(responseMsg);

        String result = forwarder.forward("<body/>", originalMessage);

        assertThat(result).isEqualTo("<ok/>");
    }

    @Test
    void cleanup_shouldCloseActiveConnection() throws Exception {
        ReflectionTestUtils.setField(forwarder, "targetConnection", mockConnection);

        forwarder.cleanup();

        verify(mockConnection).close();
    }

    @Test
    void cleanup_shouldHandleCloseException() throws Exception {
        ReflectionTestUtils.setField(forwarder, "targetConnection", mockConnection);
        doThrow(new JMSException("close error")).when(mockConnection).close();

        forwarder.cleanup();

        verify(mockConnection).close();
    }

    @Test
    void forward_shouldCreateArtemisFactory_withCredentials() {
        jmsProperties.getTarget().setType("artemis");
        jmsProperties.getTarget().setServerUrl("tcp://localhost:61617");
        jmsProperties.getTarget().setUsername("jmsuser");
        jmsProperties.getTarget().setPassword("jmspass");

        String result = forwarder.forward("<test/>", originalMessage);

        assertThat(result).contains("<error>");
    }

    @Test
    void forward_shouldCreateArtemisFactory_withoutCredentials() {
        jmsProperties.getTarget().setType("artemis");
        jmsProperties.getTarget().setServerUrl("tcp://invalid:99999");
        jmsProperties.getTarget().setUsername(null);
        jmsProperties.getTarget().setPassword(null);

        String result = forwarder.forward("<test/>", originalMessage);

        assertThat(result).contains("<error>");
    }

    @Test
    void resetConnection_shouldBeIdempotent() throws Exception {
        ReflectionTestUtils.setField(forwarder, "targetConnection", mockConnection);

        // 第一次 reset
        java.lang.reflect.Method resetMethod = JmsTargetForwarder.class.getDeclaredMethod("resetConnection");
        resetMethod.setAccessible(true);
        resetMethod.invoke(forwarder);

        assertThat(ReflectionTestUtils.getField(forwarder, "targetConnection")).isNull();

        // 第二次 reset（已經是 null）
        resetMethod.invoke(forwarder);

        verify(mockConnection, times(1)).close();
    }

    @Test
    void forward_shouldReturnNonTextMessageResponse_asTimeout() throws Exception {
        ReflectionTestUtils.setField(forwarder, "targetFactory", mockFactory);
        ReflectionTestUtils.setField(forwarder, "targetConnection", mockConnection);

        when(mockConnection.createSession(anyBoolean(), anyInt())).thenReturn(mockSession);
        when(mockSession.createQueue("TARGET.REQUEST")).thenReturn(mockQueue);
        when(mockSession.createTemporaryQueue()).thenReturn(mockTempQueue);
        when(mockSession.createProducer(mockQueue)).thenReturn(mockProducer);
        when(mockSession.createTextMessage(any())).thenReturn(mockForwardMsg);
        when(mockSession.createConsumer(mockTempQueue)).thenReturn(mockConsumer);

        BytesMessage bytesResponse = mock(BytesMessage.class);
        when(mockConsumer.receive(anyLong())).thenReturn(bytesResponse);

        String result = forwarder.forward("<request/>", originalMessage);

        assertThat(result).isEqualTo("<error>JMS response timeout</error>");
    }
}
