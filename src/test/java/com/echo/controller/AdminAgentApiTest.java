package com.echo.controller;

import com.echo.agent.AgentRegistry;
import com.echo.agent.AgentStats;
import com.echo.agent.AgentStatus;
import com.echo.agent.EchoAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin Agent API 單元測試。
 * <p>
 * 使用 MockMvc 測試 GET /api/admin/agents 端點。
 */
@ExtendWith(MockitoExtension.class)
class AdminAgentApiTest {

    @Mock
    private com.echo.service.RuleService ruleService;

    @Mock
    private com.echo.protocol.ProtocolHandlerRegistry protocolHandlerRegistry;

    @Mock
    private com.echo.service.ResponseService responseService;

    @Mock
    private com.echo.service.RequestLogService requestLogService;

    @Mock
    private com.echo.service.RuleAuditService ruleAuditService;

    @Mock
    private com.echo.service.ExcelImportService excelImportService;

    @Mock
    private com.echo.service.OpenApiImportService openApiImportService;

    @Mock
    private com.echo.service.ResponseContentValidatorRegistry responseContentValidatorRegistry;

    @Mock
    private com.echo.repository.BuiltinUserRepository builtinUserRepository;

    @Mock
    private AgentRegistry agentRegistry;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private com.echo.service.ScenarioService scenarioService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminController controller = new AdminController(
                ruleService, protocolHandlerRegistry, responseService, requestLogService,
                Optional.of(ruleAuditService), Optional.empty(), Optional.empty(),
                excelImportService, openApiImportService, Optional.empty(), responseContentValidatorRegistry,
                builtinUserRepository, agentRegistry, cacheManager, scenarioService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getAgentStatus_shouldReturnAgentList() throws Exception {
        EchoAgent agent = mockAgent("log-agent", AgentStatus.RUNNING, 10, 1000L, 5L);
        when(agentRegistry.getAll()).thenReturn(List.of(agent));

        mockMvc.perform(get("/api/admin/agents").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("log-agent")))
                .andExpect(jsonPath("$[0].status", is("RUNNING")))
                .andExpect(jsonPath("$[0].queueSize", is(10)))
                .andExpect(jsonPath("$[0].processedCount", is(1000)))
                .andExpect(jsonPath("$[0].droppedCount", is(5)));
    }

    @Test
    void getAgentStatus_shouldReturnEmptyArray_whenNoAgents() throws Exception {
        when(agentRegistry.getAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/agents").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getAgentStatus_shouldReturnNonRunningStatus() throws Exception {
        EchoAgent agent = mockAgent("log-agent", AgentStatus.STOPPED, 0, 500L, 2L);
        when(agentRegistry.getAll()).thenReturn(List.of(agent));

        mockMvc.perform(get("/api/admin/agents").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status", is("STOPPED")))
                .andExpect(jsonPath("$[0].queueSize", is(0)))
                .andExpect(jsonPath("$[0].processedCount", is(500)))
                .andExpect(jsonPath("$[0].droppedCount", is(2)));
    }

    @Test
    void getAgentStatus_shouldReturnMultipleAgents() throws Exception {
        EchoAgent agent1 = mockAgent("log-agent", AgentStatus.RUNNING, 5, 200L, 0L);
        EchoAgent agent2 = mockAgent("other-agent", AgentStatus.STARTING, 0, 0L, 0L);
        when(agentRegistry.getAll()).thenReturn(List.of(agent1, agent2));

        mockMvc.perform(get("/api/admin/agents").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name", is("log-agent")))
                .andExpect(jsonPath("$[1].name", is("other-agent")))
                .andExpect(jsonPath("$[1].status", is("STARTING")));
    }

    private EchoAgent mockAgent(String name, AgentStatus status, int queueSize, long processed, long dropped) {
        EchoAgent agent = org.mockito.Mockito.mock(EchoAgent.class);
        lenient().when(agent.getName()).thenReturn(name);
        lenient().when(agent.getStatus()).thenReturn(status);
        lenient().when(agent.getStats()).thenReturn(AgentStats.builder()
                .queueSize(queueSize)
                .processedCount(processed)
                .droppedCount(dropped)
                .build());
        return agent;
    }
}
