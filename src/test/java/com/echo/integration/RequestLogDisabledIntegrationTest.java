package com.echo.integration;

import com.echo.agent.LogAgent;
import com.echo.agent.RequestLogSpool;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.jms.JmsConnectionManager;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "echo.request-log.enabled=false",
        "echo.request-log.store=database"
})
class RequestLogDisabledIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JmsConnectionManager jmsConnectionManager;

    @Test
    @DisplayName("關閉請求記錄時 HTTP XML 規則正常且不啟動記錄背景元件")
    void disabledLogging_shouldKeepHttpXmlMockWorkingWithoutLogWorkers() {
        assertThat(applicationContext.getBeansOfType(LogAgent.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(RequestLogSpool.class)).isEmpty();

        createHttpRule("/request-log-off", "POST", "<response>matched</response>",
                null, 200, "//type=ORDER", null, null,
                null, null, null, "request log disabled");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> response = restTemplate.exchange(
                "/mock/request-log-off", HttpMethod.POST,
                new HttpEntity<>("<root><type>ORDER</type></root>", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("<response>matched</response>");
        assertThat(queryLogs()).isEmpty();
    }

    @Test
    @DisplayName("關閉請求記錄時 JMS XML 規則仍會回覆且不新增記錄")
    void disabledLogging_shouldKeepJmsXmlReplyWorking() throws Exception {
        createJmsRule("ECHO.REQUEST", "<response>matched</response>",
                "//type=ORDER", null, "request log disabled");

        var jmsTemplate = jmsConnectionManager.getJmsTemplate();
        jmsTemplate.setReceiveTimeout(5000);
        Message reply = jmsTemplate.sendAndReceive("ECHO.REQUEST",
                session -> session.createTextMessage("<root><type>ORDER</type></root>"));

        assertThat(reply).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) reply).getText()).isEqualTo("<response>matched</response>");
        assertThat(queryLogs()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<?> queryLogs() {
        ResponseEntity<Map> response = adminClient().getForEntity(
                "/api/admin/logs?limit=100", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return (List<?>) response.getBody().get("results");
    }
}
