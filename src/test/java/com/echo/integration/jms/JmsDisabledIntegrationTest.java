package com.echo.integration.jms;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.service.JmsRuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * 使用 @MockitoBean 提供 JmsRuleService 以避免因 JmsProtocolHandler
 * 不存在而導致 context 啟動失敗（JmsRuleService 對其有硬依賴）。
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

    /** Mock JmsRuleService 以避免 context 啟動失敗 */
    @MockitoBean
    @SuppressWarnings("unused")
    private JmsRuleService jmsRuleService;

    private TestRestTemplate adminClient() {
        return restTemplate.withBasicAuth("admin", "admin");
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
