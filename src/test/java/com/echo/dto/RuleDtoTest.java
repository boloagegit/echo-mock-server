package com.echo.dto;

import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.protocol.http.HttpProtocolHandler;
import com.echo.protocol.jms.JmsProtocolHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RuleDtoTest {

    @Mock
    private com.echo.repository.HttpRuleRepository httpRuleRepository;
    @Mock
    private com.echo.repository.JmsRuleRepository jmsRuleRepository;

    @Test
    void httpHandler_toDto_shouldNotIncludeBody_whenIncludeBodyFalse() {
        HttpProtocolHandler handler = new HttpProtocolHandler(httpRuleRepository);
        HttpRule rule = HttpRule.builder()
                .id("test-id")
                .matchKey("/api/test")
                .method("GET")
                .responseId(1L)
                .build();
        Response response = Response.builder().id(1L).body("test body").build();

        RuleDto dto = handler.toDto(rule, response, false);

        assertThat(dto.getId()).isEqualTo("test-id");
        assertThat(dto.getMatchKey()).isEqualTo("/api/test");
        assertThat(dto.getResponseBody()).isNull();
    }

    @Test
    void httpHandler_toDto_shouldIncludeBody_whenIncludeBodyTrue() {
        HttpProtocolHandler handler = new HttpProtocolHandler(httpRuleRepository);
        HttpRule rule = HttpRule.builder()
                .id("test-id")
                .matchKey("/api/test")
                .responseId(1L)
                .build();
        Response response = Response.builder().id(1L).body("test body").build();

        RuleDto dto = handler.toDto(rule, response, true);

        assertThat(dto.getResponseBody()).isEqualTo("test body");
    }

    @Test
    void jmsHandler_toDto_shouldNotIncludeBody_whenIncludeBodyFalse() {
        JmsProtocolHandler handler = new JmsProtocolHandler(jmsRuleRepository, null);
        JmsRule rule = JmsRule.builder()
                .id("test-id")
                .queueName("TEST.QUEUE")
                .responseId(1L)
                .build();
        Response response = Response.builder().id(1L).body("test body").build();

        RuleDto dto = handler.toDto(rule, response, false);

        assertThat(dto.getId()).isEqualTo("test-id");
        assertThat(dto.getProtocol()).isEqualTo(Protocol.JMS);
        assertThat(dto.getResponseBody()).isNull();
    }

    @Test
    void jmsHandler_toDto_shouldIncludeBody_whenIncludeBodyTrue() {
        JmsProtocolHandler handler = new JmsProtocolHandler(jmsRuleRepository, null);
        JmsRule rule = JmsRule.builder()
                .id("test-id")
                .queueName("TEST.QUEUE")
                .responseId(1L)
                .build();
        Response response = Response.builder().id(1L).body("test body").build();

        RuleDto dto = handler.toDto(rule, response, true);

        assertThat(dto.getResponseBody()).isEqualTo("test body");
    }
}
