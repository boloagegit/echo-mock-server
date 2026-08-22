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
        // 使用本機未監聽的 port 測試錯誤處理，避免測試依賴 DNS timeout。
        jmsProperties.getTarget().setServerUrl("tcp://127.0.0.1:19999");
        jmsProperties.getTarget().setQueue("TARGET.REQUEST");
        
        String result = forwarder.forward("<test>body</test>", originalMessage);
        
        assertThat(result).contains("<error>");
    }

    @Test
    void forwardWithMetadata_shouldRemoveCredentialsAndOptions() {
        jmsProperties.getTarget().setServerUrl(
                "tcp://user:secret@localhost:19999?password=hidden&sslEnabled=true");
        jmsProperties.getTarget().setQueue("TARGET.REQUEST");

        JmsTargetForwarder.ForwardResult result =
                forwarder.forwardWithMetadata("<test>body</test>", originalMessage);

        assertThat(result.body()).contains("<error>");
        assertThat(result.target()).isEqualTo(
                "Legacy application.yml | tcp://localhost:19999 | TARGET.REQUEST");
        assertThat(result.target()).doesNotContain("user", "secret", "password", "hidden");
    }

    @Test
    void sanitizeServerUrl_shouldHandleNullAndFragment() {
        assertThat(JmsTargetForwarder.sanitizeServerUrl(null)).isEqualTo("-");
        assertThat(JmsTargetForwarder.sanitizeServerUrl(
                "tcp://name:key@broker:61616#credentials"))
                .isEqualTo("tcp://broker:61616");
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
