package com.echo.integration.jms;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.jms.JmsConnectionManager;
import com.echo.jms.MockJmsListener;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JMS 停用時建立 JMS 規則被拒絕的整合測試
 * <p>
 * 需要獨立的 Spring Context（echo.jms.enabled=false）。
 * 使用真正的服務與 Spring Boot 自動配置，確認停用時沒有內建 broker，HTTP 仍可使用。
 * <p>
 * 此時 ProtocolHandlerRegistry 不包含 JMS handler，
 * 因此 validateProtocolEnabled(JMS) 會拋出 IllegalArgumentException → 400。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "echo.jms.enabled=false")
class JmsDisabledIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ApplicationContext context;

    private TestRestTemplate adminClient() {
        return restTemplate.withBasicAuth("admin", "admin");
    }

    @Test
    @DisplayName("JMS 停用時不啟動內建 broker 與 listener")
    void disabledJmsDoesNotStartEmbeddedBrokerOrListener() {
        assertThat(context.getBeansOfType(EmbeddedActiveMQ.class)).isEmpty();
        assertThat(context.getBeansOfType(JmsConnectionManager.class)).isEmpty();
        assertThat(context.getBeansOfType(MockJmsListener.class)).isEmpty();
    }

    @Test
    @DisplayName("JMS 停用時 HTTP 模擬與 readiness 仍正常")
    void httpMockAndReadinessRemainAvailable() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP).method("GET").matchKey("/jms-disabled/http")
                .status(200).responseBody("http-without-jms").build();

        assertThat(adminClient().postForEntity("/api/admin/rules", dto, RuleDto.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> response = restTemplate.getForEntity("/mock/jms-disabled/http", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("http-without-jms");
        assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("JMS 停用時 POST JMS 規則 → 400，錯誤訊息包含 JMS")
    @SuppressWarnings("unchecked")
    void createJmsRuleWhenDisabled_shouldReturn400() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.JMS)
                .matchKey("ORDER.Q")
                .responseBody("<ok/>")
                .build();

        ResponseEntity<Map> response = adminClient()
                .postForEntity("/api/admin/rules", dto, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).asString().contains("JMS");
    }
}
