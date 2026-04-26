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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JmsTargetForwarderTest {

    private JmsTargetForwarder forwarder;
    private JmsProperties jmsProperties;
    private final List<JmsTargetFactoryProvider> providers = List.of(
            new ArtemisFactoryProvider(), new TibcoFactoryProvider());

    @Mock
    private Message originalMessage;

    @BeforeEach
    void setUp() {
        jmsProperties = new JmsProperties();
        jmsProperties.getTarget().setEnabled(true);
        jmsProperties.getTarget().setType("artemis");
        jmsProperties.getTarget().setServerUrl("tcp://localhost:61617");
        jmsProperties.getTarget().setTimeoutSeconds(5);
        
        forwarder = new JmsTargetForwarder(jmsProperties, providers);
    }

    @Test
    void forward_shouldReturnError_whenConnectionFails() {
        // 使用無效的 server URL 測試錯誤處理（port 在合法範圍內）
        jmsProperties.getTarget().setServerUrl("tcp://invalid-host:19999");
        jmsProperties.getTarget().setQueue("TARGET.REQUEST");
        
        String result = forwarder.forward("<test>body</test>", originalMessage);
        
        assertThat(result).contains("<error>");
    }

    @Test
    void forward_shouldReturnError_whenTibcoJarNotFound() {
        jmsProperties.getTarget().setType("tibco");
        jmsProperties.getTarget().setServerUrl("tcp://tibco-server:7222");
        jmsProperties.getTarget().setQueue("TARGET.REQUEST");
        
        String result = forwarder.forward("<test>body</test>", originalMessage);
        
        assertThat(result).contains("<error>");
        // TIBCO jar 不存在時會拋出 IllegalStateException
    }

    @Test
    void cleanup_shouldNotThrow_whenNoConnection() {
        // 沒有連線時 cleanup 應該安全執行
        forwarder.cleanup();
    }

    @Test
    void jmsProperties_targetShouldHaveCorrectDefaults() {
        JmsProperties props = new JmsProperties();
        
        assertThat(props.getTarget().isEnabled()).isFalse();
        assertThat(props.getTarget().getType()).isEqualTo("tibco");
        assertThat(props.getTarget().getTimeoutSeconds()).isEqualTo(30);
    }
}
