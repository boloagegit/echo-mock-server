package com.echo.controller;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.service.RequestLogService;
import com.echo.service.RequestLogService.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestLogControllerLogToRuleTest {

    @Mock
    private RequestLogService requestLogService;

    private RequestLogController controller;

    @BeforeEach
    void setUp() {
        controller = new RequestLogController(requestLogService);
    }

    @Test
    void logToRule_httpLog_returnsCorrectRuleDto() {
        LogEntry entry = LogEntry.builder()
                .id(1L)
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/users")
                .matched(true)
                .responseTimeMs(50)
                .targetHost("api.example.com")
                .responseStatus(200)
                .responseBody("{\"users\":[]}")
                .requestTime(LocalDateTime.now())
                .build();

        when(requestLogService.findById(1L)).thenReturn(Optional.of(entry));

        ResponseEntity<RuleDto> response = controller.logToRule(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(dto.getMatchKey()).isEqualTo("/api/users");
        assertThat(dto.getMethod()).isEqualTo("GET");
        assertThat(dto.getTargetHost()).isEqualTo("api.example.com");
        assertThat(dto.getStatus()).isEqualTo(200);
        assertThat(dto.getResponseBody()).isEqualTo("{\"users\":[]}");
        assertThat(dto.getDescription()).isEqualTo("[From Log] GET /api/users");
        assertThat(dto.getEnabled()).isTrue();
        assertThat(dto.getPriority()).isEqualTo(0);
        assertThat(dto.getDelayMs()).isEqualTo(0L);
    }

    @Test
    void logToRule_jmsLog_returnsCorrectRuleDto() {
        LogEntry entry = LogEntry.builder()
                .id(2L)
                .protocol(Protocol.JMS)
                .endpoint("ORDER.REQUEST")
                .matched(true)
                .responseTimeMs(30)
                .responseBody("<order>test</order>")
                .requestTime(LocalDateTime.now())
                .build();

        when(requestLogService.findById(2L)).thenReturn(Optional.of(entry));

        ResponseEntity<RuleDto> response = controller.logToRule(2L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.getProtocol()).isEqualTo(Protocol.JMS);
        assertThat(dto.getMatchKey()).isEqualTo("*");
        assertThat(dto.getMethod()).isNull();
        assertThat(dto.getTargetHost()).isNull();
        assertThat(dto.getStatus()).isNull();
        assertThat(dto.getResponseBody()).isEqualTo("<order>test</order>");
        assertThat(dto.getDescription()).isEqualTo("[From Log] ORDER.REQUEST");
    }

    @Test
    void logToRule_notFound_returns404() {
        when(requestLogService.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<RuleDto> response = controller.logToRule(999L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void logToRule_noResponseBody_returnsRuleDtoWithoutBody() {
        LogEntry entry = LogEntry.builder()
                .id(3L)
                .protocol(Protocol.HTTP)
                .method("POST")
                .endpoint("/api/orders")
                .matched(false)
                .responseTimeMs(100)
                .responseStatus(404)
                .requestTime(LocalDateTime.now())
                .build();

        when(requestLogService.findById(3L)).thenReturn(Optional.of(entry));

        ResponseEntity<RuleDto> response = controller.logToRule(3L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.getResponseBody()).isNull();
        assertThat(dto.getStatus()).isEqualTo(404);
        assertThat(dto.getDescription()).isEqualTo("[From Log] POST /api/orders");
    }

    @Test
    void logToRule_nullResponseStatus_defaults200() {
        LogEntry entry = LogEntry.builder()
                .id(4L)
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint("/api/health")
                .matched(true)
                .responseTimeMs(10)
                .responseBody("OK")
                .requestTime(LocalDateTime.now())
                .build();

        when(requestLogService.findById(4L)).thenReturn(Optional.of(entry));

        ResponseEntity<RuleDto> response = controller.logToRule(4L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.getStatus()).isEqualTo(200);
    }

    @Test
    void logToRule_blankResponseBody_notIncluded() {
        LogEntry entry = LogEntry.builder()
                .id(5L)
                .protocol(Protocol.HTTP)
                .method("DELETE")
                .endpoint("/api/items/1")
                .matched(true)
                .responseTimeMs(20)
                .responseStatus(204)
                .responseBody("   ")
                .requestTime(LocalDateTime.now())
                .build();

        when(requestLogService.findById(5L)).thenReturn(Optional.of(entry));

        ResponseEntity<RuleDto> response = controller.logToRule(5L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.getResponseBody()).isNull();
    }
}
