package com.echo.config;

import com.echo.controller.AuditController;
import com.echo.controller.ImportExportController;
import com.echo.controller.RequestLogController;
import com.echo.controller.ResponseController;
import com.echo.controller.RuleApplyController;
import com.echo.controller.RuleController;
import com.echo.controller.RuleStateController;
import com.echo.controller.ScenarioController;
import com.echo.controller.SystemController;
import com.echo.dto.RuleDto;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.service.HttpRuleService;
import com.echo.service.JmsRuleService;
import com.echo.service.RuleService;
import com.echo.service.RuleQueryService;
import com.echo.service.RequestLogService;
import com.echo.service.RuleAuditService;
import com.echo.service.IssueReportService;
import com.echo.service.RuleApplyMapper;
import com.echo.service.RuleApplyContractService;
import com.echo.service.RuleApplyPersistenceSynchronizer;
import com.echo.service.RuleEditService;
import com.echo.service.RuleImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({SystemController.class, RuleController.class, RuleApplyController.class, RuleStateController.class,
        ImportExportController.class, ResponseController.class, RequestLogController.class, AuditController.class,
        ScenarioController.class})
@Import({SecurityConfig.class, LdapConfig.class, RuleApplyContractService.class, RuleEditService.class,
        com.echo.service.BuildInfoService.class, AdminLogQueryProperties.class,
        AdminLogQueryAdmissionConfiguration.class, com.echo.service.AdminLogQueryAdmission.class})
@TestPropertySource(properties = {"echo.features.bulk-import-export-enabled=true",
        "echo.admin.log-query-admission.enabled=true", "echo.admin.log-query-admission.max-wait-millis=0"})
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.echo.service.AdminLogQueryAdmission logQueryAdmission;

    @MockitoBean
    private RuleService ruleService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private RuleQueryService ruleQueryService;

    @MockitoBean
    private com.echo.protocol.ProtocolHandlerRegistry protocolHandlerRegistry;

    @MockitoBean
    private HttpRuleService httpRuleService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private JmsRuleService jmsRuleService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private RequestLogService requestLogService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private RuleAuditService ruleAuditService;

    @MockitoBean
    private com.echo.service.ResponseService responseService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private com.echo.service.ExcelImportService excelImportService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private com.echo.service.ResponseContentValidatorRegistry responseContentValidatorRegistry;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private com.echo.repository.BuiltinUserRepository builtinUserRepository;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private com.echo.agent.AgentRegistry agentRegistry;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private CacheManager cacheManager;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private IssueReportService issueReportService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private RuleApplyMapper ruleApplyMapper;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private RuleApplyPersistenceSynchronizer ruleApplyPersistenceSynchronizer;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private com.echo.service.OpenApiImportService openApiImportService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private RuleImportService ruleImportService;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // required for Spring context
    private com.echo.service.ScenarioService scenarioService;

    @MockitoBean
    private com.echo.service.RequestLogTrafficService requestLogTrafficService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appLogo_shouldRemainPublicForTheLoginPage() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/x-icon"));
    }

    // ========== Export API ==========

    @Test
    void pagedRules_shouldReturn200_whenNotAuthenticated() throws Exception {
        when(ruleQueryService.query(any())).thenReturn(new RuleQueryService.RuleQueryResult(
                List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/admin/rules/page").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void groupedRules_shouldReturn200_whenNotAuthenticated() throws Exception {
        when(ruleQueryService.queryGroupSummary(any())).thenReturn(
                new RuleQueryService.RuleGroupSummary(java.util.Map.of(), java.util.Map.of(), 0));
        when(ruleQueryService.queryGroup(any(), any(), isNull(), anyInt())).thenReturn(
                new RuleQueryService.RuleGroupContent(List.of(), 0));

        mockMvc.perform(get("/api/admin/rules/groups").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/rules/group").param("key", "_untagged")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void requestLogs_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/logs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/logs/summary").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/logs/1/detail").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/logs/traffic").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void capacityGateRunsAfterAuthenticationAndDoesNotBlockSummary() throws Exception {
        var rejectedBefore = logQueryAdmission.snapshot().get("rejected");
        try (var held = logQueryAdmission.acquire()) {
            org.assertj.core.api.Assertions.assertThat(held).isNotNull();
            mockMvc.perform(get("/api/admin/logs").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
            org.assertj.core.api.Assertions.assertThat(logQueryAdmission.snapshot().get("rejected"))
                    .isEqualTo(rejectedBefore);
            mockMvc.perform(get("/api/admin/logs").accept(MediaType.APPLICATION_JSON)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "1"));
            mockMvc.perform(get("/api/admin/logs/summary").accept(MediaType.APPLICATION_JSON)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader")))
                    .andExpect(status().isOk());
        }
        org.assertj.core.api.Assertions.assertThat(logQueryAdmission.snapshot()).containsEntry("active", 0);
    }

    @Test
    void headRequestsShareListCapacityAndPreserveTheHeadResponseContract() throws Exception {
        try (var held = logQueryAdmission.acquire()) {
            org.assertj.core.api.Assertions.assertThat(held).isNotNull();
            mockMvc.perform(head("/api/admin/logs").accept(MediaType.APPLICATION_JSON)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "1"))
                    .andExpect(content().string(""));
        }
        org.assertj.core.api.Assertions.assertThat(logQueryAdmission.snapshot()).containsEntry("active", 0);
        mockMvc.perform(head("/api/admin/logs").accept(MediaType.APPLICATION_JSON)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader")))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
        org.mockito.Mockito.verify(requestLogService).querySummary(any());
    }

    @Test
    @WithMockUser(roles = "USER")
    void requestLogTraffic_shouldUseTheRequestedRangeAndRejectUnknownRanges() throws Exception {
        when(requestLogTrafficService.traffic(com.echo.service.RequestLogTrafficService.Range.HOUR))
                .thenReturn(new com.echo.service.RequestLogTrafficService.Traffic("1h",
                        java.time.LocalDateTime.of(2026, 10, 12, 13, 2), java.time.LocalDateTime.of(2026, 10, 12, 14, 2),
                        2, List.of(), new com.echo.service.RequestLogTrafficService.Totals(0, 0, 0, 0, 0),
                        new com.echo.service.RequestLogTrafficService.ResponseTime(null, null), List.of(),
                        null, false, 10000, java.time.LocalDateTime.of(2026, 10, 12, 14, 1)));

        mockMvc.perform(get("/api/admin/logs/traffic").param("range", "1h").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"from\":\"2026-10-12T13:02:00\"")));
        mockMvc.perform(get("/api/admin/logs/traffic").param("range", "30d").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "USER")
    void requestLogs_shouldPassTheTimeRangeFilter() throws Exception {
        when(requestLogService.querySummary(any())).thenReturn(
                RequestLogService.SummaryQueryResult.builder()
                        .results(List.of()).page(0).size(20).totalElements(0)
                        .totalPages(0).build());

        mockMvc.perform(get("/api/admin/logs").param("from", "2026-10-12T14:00:00")
                        .param("to", "2026-10-12T14:30:00").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(requestLogService).querySummary(org.mockito.ArgumentMatchers.argThat(f ->
                java.time.LocalDateTime.of(2026, 10, 12, 14, 0).equals(f.getFrom())
                        && java.time.LocalDateTime.of(2026, 10, 12, 14, 30).equals(f.getTo())));
    }

    @Test
    @WithMockUser(roles = "USER")
    void requestLogs_shouldReturn200_whenAuthenticated() throws Exception {
        when(requestLogService.querySummary(any())).thenReturn(
                RequestLogService.SummaryQueryResult.builder()
                        .results(List.of()).page(0).size(20).totalElements(0)
                        .totalPages(0).build());

        mockMvc.perform(get("/api/admin/logs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void exportRules_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/rules/export").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void exportRules_shouldReturn403_whenNotAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/rules/export").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void exportRules_shouldReturn200_whenAdmin() throws Exception {
        when(httpRuleService.findAllHttpRules()).thenReturn(List.of());
        mockMvc.perform(get("/api/admin/rules/export").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ========== Batch import API ==========

    @Test
    void importBatch_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/admin/rules/import-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void importBatch_shouldReturn403_whenNotAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/rules/import-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void portableRulePreviewAndConfirmation_shouldReturn403_whenNotAdmin() throws Exception {
        for (String endpoint : List.of("preview", "confirm")) {
            mockMvc.perform(post("/api/admin/rules/import/" + endpoint)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rules\":[],\"connectionMappings\":{}}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void importBatch_shouldReturn200_whenAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/rules/import-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());
    }

    // ========== Batch deletion API ==========

    @Test
    void deleteBatch_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(delete("/api/admin/rules/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void deleteBatch_shouldReturn403_whenNotAdmin() throws Exception {
        mockMvc.perform(delete("/api/admin/rules/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteBatch_shouldReturn200_whenAdmin() throws Exception {
        mockMvc.perform(delete("/api/admin/rules/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());
    }

    // ========== Delete all API ==========

    @Test
    void deleteAll_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(delete("/api/admin/rules/all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void deleteAll_shouldReturn403_whenNotAdmin() throws Exception {
        mockMvc.perform(delete("/api/admin/rules/all"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteAll_shouldReturn200_whenAdmin() throws Exception {
        when(httpRuleService.findAllHttpRules()).thenReturn(List.of());
        mockMvc.perform(delete("/api/admin/rules/all").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void deleteAllResponses_shouldReturn403_whenNotAdmin() throws Exception {
        mockMvc.perform(delete("/api/admin/responses/all").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteAllResponses_shouldReturn200_whenAdmin() throws Exception {
        when(responseService.deleteAll()).thenReturn(new com.echo.service.ResponseService.DeleteAllResult(0, 0));
        mockMvc.perform(delete("/api/admin/responses/all").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void deleteAllAudit_shouldReturn403_whenNotAdmin() throws Exception {
        mockMvc.perform(delete("/api/admin/audit/all").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ========== General Rules CRUD ==========

    @Test
    @WithMockUser(roles = "USER")
    void createRule_shouldReturn201_whenUser() throws Exception {
        RuleDto rule = RuleDto.builder().protocol(Protocol.HTTP).matchKey("/test").method("GET").build();
        when(protocolHandlerRegistry.isEnabled(Protocol.HTTP)).thenReturn(true);
        when(protocolHandlerRegistry.generateDescription(any())).thenReturn("GET /test");
        when(responseService.save(any())).thenReturn(com.echo.entity.Response.builder().id(1L).build());
        var handler = mock(com.echo.protocol.ProtocolHandler.class);
        when(protocolHandlerRegistry.getHandler(Protocol.HTTP)).thenReturn(java.util.Optional.of(handler));
        HttpRule savedRule = HttpRule.builder().matchKey("/test").responseId(1L).build();
        when(handler.fromDto(any())).thenReturn(savedRule);
        when(handler.save(any())).thenReturn(savedRule);
        when(handler.toDto(any(), any(), anyBoolean())).thenReturn(RuleDto.builder().matchKey("/test").build());
        
        mockMvc.perform(post("/api/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rule)))
                .andExpect(status().isCreated());
    }

    @Test
    void fullListsRequireLogin_whilePagedViewsStayOpen() throws Exception {
        mockMvc.perform(get("/api/admin/rules").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/responses").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        when(ruleQueryService.query(any())).thenReturn(new RuleQueryService.RuleQueryResult(List.of(), 0, 20, 0, 0));
        mockMvc.perform(get("/api/admin/rules/page").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        when(responseService.findSummaryPage(any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(null);
        mockMvc.perform(get("/api/admin/responses/summary").param("page", "0").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void fullListsReturn200_whenAuthenticated() throws Exception {
        when(protocolHandlerRegistry.findAllRules()).thenReturn(List.of());
        when(responseService.search(isNull())).thenReturn(List.of());
        mockMvc.perform(get("/api/admin/rules").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/responses").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void applyRule_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/admin/rules/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void applyRule_shouldReachValidation_whenUser() throws Exception {
        mockMvc.perform(post("/api/admin/rules/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateAppliedRule_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(put("/api/admin/rules/997a9b59-e57a-4777-9522-580cf38a5422/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void updateAppliedRule_shouldReachValidation_whenUser() throws Exception {
        mockMvc.perform(put("/api/admin/rules/997a9b59-e57a-4777-9522-580cf38a5422/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ruleManifest_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/rules/997a9b59-e57a-4777-9522-580cf38a5422/manifest"))
                .andExpect(status().isUnauthorized());
    }

    // ========== Batch enable/disable API ==========

    @Test
    @WithMockUser(roles = "USER")
    void batchEnable_shouldReturn403_whenNotAdmin() throws Exception {
        mockMvc.perform(put("/api/admin/rules/batch/enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void batchEnable_shouldReturn200_whenAdmin() throws Exception {
        when(ruleService.updateEnabled(anyList(), anyBoolean())).thenReturn(0);
        mockMvc.perform(put("/api/admin/rules/batch/enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());
    }

    // ========== Tag Batch operation API ==========

    @Test
    @WithMockUser(roles = "USER")
    void enableByTag_shouldReturn403_whenNotAdmin() throws Exception {
        mockMvc.perform(put("/api/admin/rules/tag/env/prod/enable"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void enableByTag_shouldReturn200_whenAdmin() throws Exception {
        when(ruleService.findIdsByTag(any(), any())).thenReturn(List.of());
        when(ruleService.updateEnabled(anyList(), anyBoolean())).thenReturn(0);
        mockMvc.perform(put("/api/admin/rules/tag/env/prod/enable").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
