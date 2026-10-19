package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.entity.RuleAuditLog;
import com.echo.repository.RuleAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleAuditServiceTest {

    @Mock
    private RuleAuditLogRepository repository;

    @Mock
    private SystemConfigService configService;

    private RuleAuditService service;

    @BeforeEach
    void setUp() {
        service = new RuleAuditService(repository, configService);
    }

    @Test
    void logCreate_shouldSaveAuditLog() {
        HttpRule rule = HttpRule.builder().id("uuid-1").matchKey("/api").build();

        service.logCreate(rule);

        ArgumentCaptor<RuleAuditLog> captor = ArgumentCaptor.forClass(RuleAuditLog.class);
        verify(repository).save(captor.capture());
        
        RuleAuditLog log = captor.getValue();
        assertThat(log.getRuleId()).isEqualTo("uuid-1");
        assertThat(log.getAction()).isEqualTo(RuleAuditLog.Action.CREATE);
        assertThat(log.getBeforeJson()).isNull();
        assertThat(log.getAfterJson()).contains("/api");
    }

    @Test
    void logUpdate_shouldSaveBeforeAndAfter() throws Exception {
        String beforeJson = "{\"id\":\"uuid-1\",\"matchKey\":\"/old\"}";
        String afterJson = "{\"id\":\"uuid-1\",\"matchKey\":\"/new\"}";

        service.logUpdate(beforeJson, afterJson);

        ArgumentCaptor<RuleAuditLog> captor = ArgumentCaptor.forClass(RuleAuditLog.class);
        verify(repository).save(captor.capture());
        
        RuleAuditLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo(RuleAuditLog.Action.UPDATE);
        assertThat(log.getBeforeJson()).contains("/old");
        assertThat(log.getAfterJson()).contains("/new");
    }

    @Test
    void logDelete_shouldSaveBeforeOnly() {
        HttpRule rule = HttpRule.builder().id("uuid-1").matchKey("/deleted").build();

        service.logDelete(rule);

        ArgumentCaptor<RuleAuditLog> captor = ArgumentCaptor.forClass(RuleAuditLog.class);
        verify(repository).save(captor.capture());
        
        RuleAuditLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo(RuleAuditLog.Action.DELETE);
        assertThat(log.getBeforeJson()).contains("/deleted");
        assertThat(log.getAfterJson()).isNull();
    }

    @Test
    void cleanup_shouldDeleteOldLogs() {
        when(configService.getAuditRetentionDays()).thenReturn(7);
        when(repository.deleteByTimestampBefore(any(LocalDateTime.class))).thenReturn(5);

        service.cleanup();

        verify(repository).deleteByTimestampBefore(any(LocalDateTime.class));
    }

    @Test
    void getAuditLogs_shouldReturnLogs() {
        Object[] row = new Object[]{1L, "uuid-1", RuleAuditLog.Action.CREATE, "admin", LocalDateTime.now(), true, false};
        when(repository.findSummaryByRuleId(eq("uuid-1"), any()))
                .thenReturn(Collections.singletonList(row));

        List<RuleAuditLog> logs = service.getAuditLogs("uuid-1", 10);

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getRuleId()).isEqualTo("uuid-1");
        assertThat(logs.get(0).getAction()).isEqualTo(RuleAuditLog.Action.CREATE);
    }

    @Test
    void getAllAuditLogs_shouldReturnLogs() {
        Object[] row = new Object[]{1L, "uuid-1", RuleAuditLog.Action.UPDATE, "admin", LocalDateTime.now(), true, true};
        when(repository.findAllSummary(any()))
                .thenReturn(Collections.singletonList(row));

        List<RuleAuditLog> logs = service.getAllAuditLogs(10);

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getRuleId()).isEqualTo("uuid-1");
    }

    @Test
    void queryAuditSummary_shouldMapPageWithoutJson() {
        LocalDateTime timestamp = LocalDateTime.now();
        Object[] row = new Object[]{1L, "uuid-1", RuleAuditLog.Action.UPDATE,
                "admin", timestamp, true, true, "HTTP", "/users/1", "取得使用者",
                "{\"method\":\"GET\",\"changeCount\":1,\"changes\":[{\"f\":\"priority\",\"b\":\"0\",\"a\":\"5\"}]}"};
        when(repository.findSummaryPage(eq(RuleAuditLog.Action.UPDATE), eq("admin"), eq("uuid"), eq("RULE"), isNull(), any()))
                .thenReturn(new PageImpl<>(Collections.singletonList(row)));

        RuleAuditService.AuditQueryResult result = service.queryAuditSummary(
                RuleAuditService.AuditQueryFilter.builder()
                        .action(RuleAuditLog.Action.UPDATE)
                        .operator(" admin ")
                        .keyword(" uuid ")
                        .targetType("rule")
                        .page(0)
                        .size(20)
                        .sortField("operator")
                        .sortDirection("asc")
                        .build());

        assertThat(result.getResults()).singleElement().satisfies(summary -> {
            assertThat(summary.getRuleId()).isEqualTo("uuid-1");
            assertThat(summary.isHasBeforeJson()).isTrue();
            assertThat(summary.isHasAfterJson()).isTrue();
            assertThat(summary.getTargetProtocol()).isEqualTo("HTTP");
            assertThat(summary.getTargetName()).isEqualTo("/users/1");
            assertThat(summary.getTargetDescription()).isEqualTo("取得使用者");
            assertThat(summary.getSummary().get("changes").get(0).get("f").asText()).isEqualTo("priority");
        });
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(repository, never()).findById(any());
    }

    @Test
    void queryAuditSummary_shouldFillMissingSummaryOneRowAtATime() {
        Object[] row = new Object[]{7L, "uuid-7", RuleAuditLog.Action.UPDATE,
                "admin", LocalDateTime.now(), true, true, null, null, null, null};
        when(repository.findSummaryPage(isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(Collections.singletonList(row)));
        when(repository.findById(7L)).thenReturn(Optional.of(RuleAuditLog.builder()
                .id(7L).ruleId("uuid-7").action(RuleAuditLog.Action.UPDATE)
                .beforeJson("{\"id\":\"uuid-7\",\"protocol\":\"HTTP\",\"matchKey\":\"/old\",\"priority\":0}")
                .afterJson("{\"id\":\"uuid-7\",\"protocol\":\"HTTP\",\"matchKey\":\"/old\",\"priority\":5}")
                .build()));

        RuleAuditService.AuditQueryResult result = service.queryAuditSummary(
                RuleAuditService.AuditQueryFilter.builder().page(0).size(20).build());

        assertThat(result.getResults()).singleElement().satisfies(summary -> {
            assertThat(summary.getTargetName()).isEqualTo("/old");
            assertThat(summary.getSummary().get("changeCount").asInt()).isEqualTo(1);
        });
        verify(repository).fillSummary(eq(7L), eq("HTTP"), eq("/old"), isNull(), contains("\"priority\""));
    }

    @Test
    void queryAuditSummary_shouldRejectUnknownTargetType() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.queryAuditSummary(
                        RuleAuditService.AuditQueryFilter.builder().targetType("issue").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logUpdate_shouldStoreListSummary() {
        service.logUpdate(
                "{\"id\":\"uuid-1\",\"protocol\":\"JMS\",\"queueName\":\"ECHO.REQUEST\",\"bodyCondition\":\"a=1\"}",
                "{\"id\":\"uuid-1\",\"protocol\":\"JMS\",\"queueName\":\"ECHO.REQUEST\",\"bodyCondition\":\"a=2\"}");

        ArgumentCaptor<RuleAuditLog> captor = ArgumentCaptor.forClass(RuleAuditLog.class);
        verify(repository).save(captor.capture());
        RuleAuditLog log = captor.getValue();
        assertThat(log.getTargetProtocol()).isEqualTo("JMS");
        assertThat(log.getTargetName()).isEqualTo("ECHO.REQUEST");
        assertThat(log.getSummary()).contains("\"f\":\"bodyCondition\"").contains("\"changeCount\":1");
    }

    @Test
    void backfillSummaries_shouldStopWhenNothingIsLeft() {
        when(repository.findIdsWithoutSummary(any())).thenReturn(List.of(3L), List.of());
        when(repository.findById(3L)).thenReturn(Optional.of(RuleAuditLog.builder()
                .id(3L).ruleId("response-3").action(RuleAuditLog.Action.CREATE)
                .afterJson("{\"id\":3,\"description\":\"共用回應\",\"contentType\":\"application/json\"}")
                .build()));

        service.backfillSummaries();

        verify(repository).fillSummary(eq(3L), eq("RESPONSE"), eq("共用回應"), isNull(), contains("application/json"));
        verify(repository, times(2)).findIdsWithoutSummary(any());
    }

    @Test
    void getAuditDetail_shouldReturnFullEntity() {
        RuleAuditLog detail = RuleAuditLog.builder().id(9L).beforeJson("{\"old\":1}")
                .afterJson("{\"new\":2}").build();
        when(repository.findById(9L)).thenReturn(Optional.of(detail));

        assertThat(service.getAuditDetail(9L)).isSameAs(detail);
    }

    @Test
    void logCreate_shouldUseAnonymous_whenNoAuth() {
        HttpRule rule = HttpRule.builder().id("uuid-1").matchKey("/test").build();

        service.logCreate(rule);

        ArgumentCaptor<RuleAuditLog> captor = ArgumentCaptor.forClass(RuleAuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOperator()).isEqualTo("anonymous");
    }

    @Test
    void logUpdate_shouldCaptureTimestamp() throws Exception {
        String beforeJson = "{\"id\":\"uuid-1\",\"matchKey\":\"/old\"}";
        String afterJson = "{\"id\":\"uuid-1\",\"matchKey\":\"/new\"}";

        service.logUpdate(beforeJson, afterJson);

        ArgumentCaptor<RuleAuditLog> captor = ArgumentCaptor.forClass(RuleAuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTimestamp()).isNotNull();
    }

    // ==================== hasActualChanges Test ====================

    @Test
    void hasActualChanges_shouldReturnFalse_whenOnlyIgnoredFieldsDiffer() {
        String before = "{\"id\":\"1\",\"matchKey\":\"/api\",\"version\":1,\"updatedAt\":\"2026-01-01T00:00:00\"}";
        String after  = "{\"id\":\"1\",\"matchKey\":\"/api\",\"version\":2,\"updatedAt\":\"2026-03-17T10:00:00\"}";

        assertThat(service.hasActualChanges(before, after)).isFalse();
    }

    @Test
    void hasActualChanges_shouldReturnTrue_whenContentFieldsDiffer() {
        String before = "{\"id\":\"1\",\"matchKey\":\"/old\",\"version\":1}";
        String after  = "{\"id\":\"1\",\"matchKey\":\"/new\",\"version\":2}";

        assertThat(service.hasActualChanges(before, after)).isTrue();
    }

    @Test
    void hasActualChanges_shouldReturnTrue_whenBeforeIsNull() {
        assertThat(service.hasActualChanges(null, "{\"id\":\"1\"}")).isTrue();
    }

    @Test
    void hasActualChanges_shouldReturnTrue_whenAfterIsNull() {
        assertThat(service.hasActualChanges("{\"id\":\"1\"}", null)).isTrue();
    }

    @Test
    void hasActualChanges_shouldReturnFalse_whenIdentical() {
        String json = "{\"id\":\"1\",\"matchKey\":\"/api\",\"enabled\":true}";

        assertThat(service.hasActualChanges(json, json)).isFalse();
    }

    @Test
    void hasActualChanges_shouldReturnFalse_whenOnlyExtendedAtDiffers() {
        String before = "{\"id\":\"1\",\"matchKey\":\"/api\",\"extendedAt\":null}";
        String after  = "{\"id\":\"1\",\"matchKey\":\"/api\",\"extendedAt\":\"2026-03-17T10:00:00\"}";

        assertThat(service.hasActualChanges(before, after)).isFalse();
    }

    @Test
    void hasActualChanges_shouldReturnFalse_whenOnlyBodySizeDiffers() {
        String before = "{\"id\":\"1\",\"matchKey\":\"/api\",\"bodySize\":100}";
        String after  = "{\"id\":\"1\",\"matchKey\":\"/api\",\"bodySize\":200}";

        assertThat(service.hasActualChanges(before, after)).isFalse();
    }

    @Test
    void hasActualChanges_shouldReturnTrue_whenInvalidJson() {
        assertThat(service.hasActualChanges("not json", "{\"id\":\"1\"}")).isTrue();
    }

    // ==================== Skip testing without substantial changes ====================

    @Test
    void logUpdate_shouldSkip_whenNoActualChanges() {
        String jsonV2 = "{\"id\":\"uuid-1\",\"matchKey\":\"/api\",\"version\":2}";

        service.logUpdate(jsonV2, jsonV2);

        verify(repository, never()).save(any());
    }

    @Test
    void logUpdate_withBaseRule_shouldSkip_whenNoActualChanges() {
        HttpRule before = HttpRule.builder().id("uuid-1").matchKey("/api").build();
        HttpRule after = HttpRule.builder().id("uuid-1").matchKey("/api").build();

        service.logUpdate(before, after);

        verify(repository, never()).save(any());
    }

    @Test
    void logBatchUpdate_shouldSkipUnchangedEntries() {
        List<String> beforeList = List.of(
                "{\"id\":\"1\",\"matchKey\":\"/api\",\"enabled\":true}",
                "{\"id\":\"2\",\"matchKey\":\"/test\",\"enabled\":true}"
        );
        List<String> afterList = List.of(
                "{\"id\":\"1\",\"matchKey\":\"/api\",\"enabled\":true}",
                "{\"id\":\"2\",\"matchKey\":\"/test\",\"enabled\":false}"
        );

        service.logBatchUpdate(beforeList, afterList);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RuleAuditLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getRuleId()).isEqualTo("2");
    }

    @Test
    void logBatchUpdate_shouldSkipAll_whenNoneChanged() {
        List<String> beforeList = List.of(
                "{\"id\":\"1\",\"matchKey\":\"/api\",\"enabled\":true}"
        );
        List<String> afterList = List.of(
                "{\"id\":\"1\",\"matchKey\":\"/api\",\"enabled\":true}"
        );

        service.logBatchUpdate(beforeList, afterList);

        verify(repository, never()).saveAll(any());
    }
}
