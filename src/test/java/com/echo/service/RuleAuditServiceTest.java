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

import java.time.LocalDateTime;
import java.util.List;

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
        when(repository.findByRuleIdOrderByTimestampDesc(eq("uuid-1"), any()))
                .thenReturn(List.of(new RuleAuditLog()));

        List<RuleAuditLog> logs = service.getAuditLogs("uuid-1", 10);

        assertThat(logs).hasSize(1);
    }

    @Test
    void getAllAuditLogs_shouldReturnLogs() {
        when(repository.findAllByOrderByTimestampDesc(any()))
                .thenReturn(List.of(new RuleAuditLog()));

        List<RuleAuditLog> logs = service.getAllAuditLogs(10);

        assertThat(logs).hasSize(1);
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

    // ==================== hasActualChanges 測試 ====================

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

    // ==================== 無實質變更跳過測試 ====================

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