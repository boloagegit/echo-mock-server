package com.echo.controller;

import com.echo.dto.RuleDto;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.entity.ResponseContentType;
import com.echo.entity.RuleAuditLog;
import com.echo.service.ResponseContentValidatorRegistry;
import com.echo.service.ResponseContentValidator;
import com.echo.service.RuleService;
import com.echo.service.RequestLogService;
import com.echo.service.RuleAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private RuleService ruleService;

    @Mock
    private RequestLogService requestLogService;

    @Mock
    private RuleAuditService ruleAuditService;

    @Mock
    private com.echo.service.ResponseService responseService;

    @Mock
    private com.echo.service.ExcelImportService excelImportService;

    @Mock
    private com.echo.service.OpenApiImportService openApiImportService;

    @Mock
    private com.echo.protocol.ProtocolHandlerRegistry protocolHandlerRegistry;

    @Mock
    private ResponseContentValidatorRegistry responseContentValidatorRegistry;

    @Mock
    private com.echo.repository.BuiltinUserRepository builtinUserRepository;

    @Mock
    private com.echo.agent.AgentRegistry agentRegistry;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private com.echo.service.ScenarioService scenarioService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(ruleService, protocolHandlerRegistry, responseService, requestLogService, Optional.of(ruleAuditService), Optional.empty(), Optional.empty(), excelImportService, openApiImportService, Optional.empty(), responseContentValidatorRegistry, builtinUserRepository, agentRegistry, cacheManager, scenarioService);
        ReflectionTestUtils.setField(controller, "ldapEnabled", false);
        ReflectionTestUtils.setField(controller, "ldapUrl", "");
        ReflectionTestUtils.setField(controller, "sessionTimeout", "180d");
        ReflectionTestUtils.setField(controller, "serverPort", 8080);
        ReflectionTestUtils.setField(controller, "jmsPort", 61616);
        ReflectionTestUtils.setField(controller, "datasourceUrl", "jdbc:h2:mem:test");
        ReflectionTestUtils.setField(controller, "httpAlias", "HTTP");
        ReflectionTestUtils.setField(controller, "jmsAlias", "JMS");
        // 預設 HTTP 啟用，JMS 停用
        lenient().when(protocolHandlerRegistry.isEnabled(Protocol.HTTP)).thenReturn(true);
        lenient().when(protocolHandlerRegistry.isEnabled(Protocol.JMS)).thenReturn(false);
    }

    @Test
    void getStatus_shouldReturnSystemInfo() {
        var response = controller.getStatus(null);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("serverPort");
        assertThat(response.getBody().get("isLoggedIn")).isEqualTo(false);
    }

    @Test
    void getStatus_shouldShowLoggedInUser() {
        var user = new User("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        
        var response = controller.getStatus(user);
        
        assertThat(response.getBody().get("isLoggedIn")).isEqualTo(true);
        assertThat(response.getBody().get("isAdmin")).isEqualTo(true);
        assertThat(response.getBody().get("username")).isEqualTo("admin");
    }

    @Test
    void listRules_shouldReturnAllRules() {
        when(protocolHandlerRegistry.findAllRules()).thenReturn(List.of(new HttpRule(), new HttpRule()));
        
        var response = controller.listRules();
        
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void getRule_shouldReturnRule_whenExists() {
        HttpRule rule = new HttpRule();
        doReturn(Optional.of(rule)).when(protocolHandlerRegistry).findById(any(String.class));
        when(protocolHandlerRegistry.toDto(any(), any(), anyBoolean())).thenReturn(RuleDto.builder().build());
        
        var response = controller.getRule("uuid-1");
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getRule_shouldReturn404_whenNotExists() {
        when(protocolHandlerRegistry.findById(any(String.class))).thenReturn(Optional.empty());
        
        var response = controller.getRule("uuid-1");
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createRule_shouldCreateHttpRule() {
        RuleDto rule = RuleDto.builder().protocol(Protocol.HTTP).matchKey("/test").build();
        when(responseService.save(any())).thenReturn(com.echo.entity.Response.builder().id(1L).build());
        var handler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getHandler(Protocol.HTTP)).thenReturn(Optional.of(handler));
        HttpRule savedRule = HttpRule.builder().matchKey("/test").responseId(1L).build();
        when(handler.fromDto(any())).thenReturn(savedRule);
        when(handler.save(any())).thenReturn(savedRule);
        when(handler.toDto(any(), any(), anyBoolean())).thenReturn(RuleDto.builder().matchKey("/test").build());
        
        var response = controller.createRule(rule);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void createRule_shouldRejectJmsRule_whenJmsDisabled() {
        RuleDto rule = RuleDto.builder().protocol(Protocol.JMS).matchKey("QUEUE").build();
        
        assertThatThrownBy(() -> controller.createRule(rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JMS is not enabled");
    }

    @Test
    void updateRule_shouldUpdateExistingRule() {
        HttpRule existing = HttpRule.builder().id("uuid-1").version(0L).responseId(1L).build();
        RuleDto updated = RuleDto.builder().protocol(Protocol.HTTP).matchKey("/updated").responseId(1L).build();
        doReturn(Optional.of(existing)).when(protocolHandlerRegistry).findById(any(String.class));
        when(responseService.findById(1L)).thenReturn(Optional.of(com.echo.entity.Response.builder().id(1L).build()));
        var handler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getHandler(Protocol.HTTP)).thenReturn(Optional.of(handler));
        HttpRule savedRule = HttpRule.builder().matchKey("/updated").responseId(1L).build();
        when(handler.fromDto(any())).thenReturn(savedRule);
        when(handler.save(any())).thenReturn(savedRule);
        when(handler.toDto(any(), any(), anyBoolean())).thenReturn(RuleDto.builder().matchKey("/updated").build());
        
        var response = controller.updateRule("uuid-1", updated);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateRule_shouldReturn404_whenNotExists() {
        when(protocolHandlerRegistry.findById(any(String.class))).thenReturn(Optional.empty());
        
        var response = controller.updateRule("uuid-1", RuleDto.builder().protocol(Protocol.HTTP).build());
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteRule_shouldDeleteExistingRule() {
        HttpRule existing = HttpRule.builder().id("uuid-1").build();
        doReturn(Optional.of(existing)).when(protocolHandlerRegistry).findById(any(String.class));
        var handler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getHandler(Protocol.HTTP)).thenReturn(Optional.of(handler));
        
        var response = controller.deleteRule("uuid-1");
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(handler).deleteById(any(String.class));
    }

    @Test
    void deleteRule_shouldReturn404_whenNotExists() {
        when(protocolHandlerRegistry.findById(any(String.class))).thenReturn(Optional.empty());
        
        var response = controller.deleteRule("uuid-1");
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void queryLogs_shouldReturnLogs() {
        when(requestLogService.querySummary(any())).thenReturn(
                RequestLogService.SummaryQueryResult.builder().results(List.of()).build());
        
        var response = controller.queryLogs(null, null, null, null);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getResults()).isEmpty();
    }

    @Test
    void queryLogs_shouldPassFilters() {
        when(requestLogService.querySummary(any())).thenReturn(
                RequestLogService.SummaryQueryResult.builder().results(List.of()).build());
        
        controller.queryLogs("uuid-1", "HTTP", true, "/api");
        
        verify(requestLogService).querySummary(argThat(f -> 
                f.getRuleId().equals("uuid-1") && 
                f.getProtocol() == Protocol.HTTP && 
                f.getMatched() == true &&
                f.getEndpoint().equals("/api")));
    }

    @Test
    void getLogSummary_shouldReturnSummary() {
        when(requestLogService.getSummary()).thenReturn(
                RequestLogService.Summary.builder().totalRequests(100).matchedRequests(90).matchRate(90.0).build());
        
        var response = controller.getLogSummary();
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalRequests()).isEqualTo(100);
    }

    @Test
    void deleteAllLogs_shouldReturnDeletedCount() {
        when(requestLogService.deleteAll()).thenReturn(42L);

        var response = controller.deleteAllLogs();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("deleted")).isEqualTo(42L);
        verify(requestLogService).deleteAll();
    }

    @Test
    void getRuleAuditLogs_shouldReturnLogs() {
        when(ruleAuditService.getAuditLogs(eq("uuid-1"), eq(50))).thenReturn(List.of(new RuleAuditLog()));
        
        var response = controller.getRuleAuditLogs("uuid-1", 50);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void getAllAuditLogs_shouldReturnLogs() {
        when(ruleAuditService.getAllAuditLogs(eq(1000))).thenReturn(List.of(new RuleAuditLog()));
        
        var response = controller.getAllAuditLogs(1000);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void exportAllRules_shouldReturnAllRules() {
        when(protocolHandlerRegistry.findAllRules()).thenReturn(List.of(new HttpRule(), new HttpRule()));
        
        var response = controller.exportAllRules();
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void importRules_shouldImportMultipleRules() {
        when(responseService.save(any())).thenReturn(com.echo.entity.Response.builder().id(1L).build());
        var handler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getHandler(Protocol.HTTP)).thenReturn(Optional.of(handler));
        when(handler.save(any())).thenAnswer(i -> i.getArgument(0));
        List<RuleDto> rules = List.of(
            RuleDto.builder().protocol(Protocol.HTTP).matchKey("/a").build(),
            RuleDto.builder().protocol(Protocol.HTTP).matchKey("/b").build()
        );
        
        var response = controller.importRules(rules);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((java.util.Map<String, Object>) response.getBody()).get("imported")).isEqualTo(2);
        verify(handler, times(2)).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void importRules_shouldSkipJmsRules_whenJmsDisabled() {
        when(responseService.save(any())).thenReturn(com.echo.entity.Response.builder().id(1L).build());
        var handler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getHandler(Protocol.HTTP)).thenReturn(Optional.of(handler));
        when(handler.save(any())).thenAnswer(i -> i.getArgument(0));
        List<RuleDto> rules = List.of(
            RuleDto.builder().protocol(Protocol.HTTP).matchKey("/a").build(),
            RuleDto.builder().protocol(Protocol.JMS).matchKey("QUEUE").build()
        );
        
        var response = controller.importRules(rules);
        
        assertThat(((java.util.Map<String, Object>) response.getBody()).get("imported")).isEqualTo(1);
        verify(handler, times(1)).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteRules_shouldDeleteSelectedRules() {
        HttpRule existing = HttpRule.builder().id("uuid-1").build();
        RuleDto existingDto = RuleDto.builder().id("uuid-1").protocol(Protocol.HTTP).build();
        doReturn(Optional.of(existing)).when(protocolHandlerRegistry).findById(any(String.class));
        when(protocolHandlerRegistry.toDto(any(), any(), anyBoolean())).thenReturn(existingDto);
        var handler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getHandler(Protocol.HTTP)).thenReturn(Optional.of(handler));
        
        var response = controller.deleteRules(List.of("uuid-1", "uuid-2"));
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((java.util.Map<String, Object>) response.getBody()).get("deleted")).isEqualTo(2);
        verify(handler, times(2)).deleteById(any(String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteAllRules_shouldDeleteAllRules() {
        var httpHandler = mock(com.echo.protocol.ProtocolHandler.class);
        var jmsHandler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getAllHandlers()).thenReturn(List.of(httpHandler, jmsHandler));
        when(httpHandler.deleteAll()).thenReturn(2);
        when(jmsHandler.deleteAll()).thenReturn(1);
        
        var response = controller.deleteAllRules();
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((java.util.Map<String, Object>) response.getBody()).get("deleted")).isEqualTo(3);
    }

    @Test
    void enableRule_shouldEnableRule() {
        HttpRule rule = HttpRule.builder().id("uuid-1").enabled(false).build();
        doReturn(Optional.of(rule)).when(protocolHandlerRegistry).findById("uuid-1");
        when(protocolHandlerRegistry.toDto(any(), any(), anyBoolean())).thenReturn(RuleDto.builder().id("uuid-1").protocol(Protocol.HTTP).build());
        when(ruleService.updateEnabled(anyList(), anyBoolean())).thenReturn(1);

        var response = controller.enableRule("uuid-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void enableByTag_shouldEnableRulesByTag() {
        when(ruleService.findIdsByTag("env", "prod")).thenReturn(List.of("uuid-1", "uuid-2"));
        when(ruleService.updateEnabled(anyList(), anyBoolean())).thenReturn(2);

        var response = controller.enableByTag("env", "prod");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((java.util.Map<String, Object>) response.getBody()).get("updated")).isEqualTo(2);
    }

    // ========== saveRule() SSE validation tests ==========

    @Test
    void createRule_sseWithValidBody_shouldReturn201() {
        String validSseBody = "[{\"data\":\"hello\",\"delayMs\":0}]";
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/sse")
                .method("GET")
                .sseEnabled(true)
                .status(200)
                .responseBody(validSseBody)
                .build();

        ResponseContentValidator mockValidator = mock(ResponseContentValidator.class);
        when(responseContentValidatorRegistry.getValidator(ResponseContentType.SSE_EVENTS)).thenReturn(mockValidator);
        when(responseService.save(any())).thenReturn(com.echo.entity.Response.builder().id(1L).build());
        var handler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getHandler(Protocol.HTTP)).thenReturn(Optional.of(handler));
        HttpRule savedRule = HttpRule.builder().matchKey("/api/sse").responseId(1L).sseEnabled(true).build();
        when(handler.fromDto(any())).thenReturn(savedRule);
        when(handler.save(any())).thenReturn(savedRule);
        when(handler.toDto(any(), any(), anyBoolean())).thenReturn(RuleDto.builder().matchKey("/api/sse").build());

        var response = controller.createRule(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(mockValidator).validate(validSseBody);
    }

    @Test
    void createRule_sseWithInvalidBody_shouldReturn400() {
        String invalidSseBody = "not json";
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/sse-invalid")
                .method("GET")
                .sseEnabled(true)
                .status(200)
                .responseBody(invalidSseBody)
                .build();

        ResponseContentValidator mockValidator = mock(ResponseContentValidator.class);
        doThrow(new IllegalArgumentException("SSE 回應內容必須為 JSON 陣列格式"))
                .when(mockValidator).validate(invalidSseBody);
        when(responseContentValidatorRegistry.getValidator(ResponseContentType.SSE_EVENTS)).thenReturn(mockValidator);

        var errorResponse = controller.handleIllegalArgument(
                new IllegalArgumentException("SSE 回應內容必須為 JSON 陣列格式"));
        assertThat(errorResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorResponse.getBody().get("error")).contains("SSE 回應內容必須為 JSON 陣列格式");

        assertThatThrownBy(() -> controller.createRule(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSE 回應內容必須為 JSON 陣列格式");
    }

    @Test
    void createRule_nonSseRule_shouldNotTriggerSseValidation() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/plain")
                .method("GET")
                .sseEnabled(false)
                .status(200)
                .responseBody("plain text response")
                .build();

        ResponseContentValidator textValidator = mock(ResponseContentValidator.class);
        when(responseContentValidatorRegistry.getValidator(ResponseContentType.TEXT)).thenReturn(textValidator);
        when(responseService.save(any())).thenReturn(com.echo.entity.Response.builder().id(1L).build());
        var handler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getHandler(Protocol.HTTP)).thenReturn(Optional.of(handler));
        HttpRule savedRule = HttpRule.builder().matchKey("/api/plain").responseId(1L).build();
        when(handler.fromDto(any())).thenReturn(savedRule);
        when(handler.save(any())).thenReturn(savedRule);
        when(handler.toDto(any(), any(), anyBoolean())).thenReturn(RuleDto.builder().matchKey("/api/plain").build());

        var response = controller.createRule(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(textValidator).validate("plain text response");
        verify(responseContentValidatorRegistry, never()).getValidator(ResponseContentType.SSE_EVENTS);
    }

    @Test
    void createRule_existingResponseId_shouldNotValidateBody() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/existing-resp")
                .method("GET")
                .sseEnabled(true)
                .status(200)
                .responseId(99L)
                .responseBody(null)
                .build();

        when(responseService.findById(99L)).thenReturn(
                Optional.of(com.echo.entity.Response.builder().id(99L).body("[{\"data\":\"cached\"}]").build()));
        var handler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getHandler(Protocol.HTTP)).thenReturn(Optional.of(handler));
        HttpRule savedRule = HttpRule.builder().matchKey("/api/existing-resp").responseId(99L).sseEnabled(true).build();
        when(handler.fromDto(any())).thenReturn(savedRule);
        when(handler.save(any())).thenReturn(savedRule);
        when(handler.toDto(any(), any(), anyBoolean())).thenReturn(RuleDto.builder().matchKey("/api/existing-resp").build());

        var response = controller.createRule(dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(responseContentValidatorRegistry, never()).getValidator(any());
    }
}
