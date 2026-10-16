package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.repository.HttpRuleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * 改規則時模擬請求不必等規則清單重建：重建期間用舊清單回應；存檔的請求等到新清單生效才回應，
 * 所以存檔後立刻送出的模擬請求一定用到新規則。
 */
@TestPropertySource(properties = "echo.features.bulk-import-export-enabled=true")
class RuleChangeWithoutWaitingIntegrationTest extends BaseIntegrationTest {

    /** 讓建立 HTTP 規則清單變慢，模擬規則很多時的重建時間。 */
    private static final AtomicLong SLOW_BUILD_MS = new AtomicLong();
    private static final long SLOW_MS = 800;

    @MockitoSpyBean
    private HttpRuleRepository httpRuleRepository;

    @BeforeEach
    void slowRuleListBuilds() {
        doAnswer(invocation -> {
            long ms = SLOW_BUILD_MS.get();
            if (ms > 0) {
                Thread.sleep(ms);
            }
            // Spring Data 的 repository 是介面：交給 spy 原本的預設行為（轉呼叫真正的 repository）
            return mockingDetails(httpRuleRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        }).when(httpRuleRepository).findByEnabledTrueOrderByIdAsc();
    }

    @AfterEach
    void fastAgain() {
        SLOW_BUILD_MS.set(0);
    }

    private ResponseEntity<String> mock(String path) {
        return restTemplate.exchange("/mock" + path, HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    private static long millisSince(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    @Test
    @DisplayName("規則清單重建很慢時，模擬請求立刻用舊清單回應；存檔回應時新規則已生效")
    void mockRequestsDoNotWaitWhileTheRuleListIsRebuilt() throws Exception {
        createHttpRule("/nowait/a", "GET", "A");
        assertThat(mock("/nowait/a").getBody()).isEqualTo("A");

        SLOW_BUILD_MS.set(SLOW_MS);
        long saveStarted = System.nanoTime();
        CompletableFuture<RuleDto> saving = CompletableFuture.supplyAsync(() -> createHttpRule("/nowait/b", "GET", "B"));
        TimeUnit.MILLISECONDS.sleep(150);

        // 重建進行中：舊規則照常、立刻回應，不等那 800 ms
        long requestStarted = System.nanoTime();
        ResponseEntity<String> during = mock("/nowait/a");
        long tookMs = millisSince(requestStarted);
        assertThat(during.getBody()).isEqualTo("A");
        assertThat(tookMs).as("mock request during a rebuild took %d ms", tookMs).isLessThan(400);

        // 存檔要等新清單生效才回應；回應後立刻送出的模擬請求一定用到新規則
        saving.get(10, TimeUnit.SECONDS);
        assertThat(millisSince(saveStarted)).isGreaterThanOrEqualTo(SLOW_MS);
        SLOW_BUILD_MS.set(0);
        assertThat(mock("/nowait/b").getBody()).isEqualTo("B");
    }

    @Test
    @DisplayName("存檔後立刻送出的模擬請求用到新內容（修改、刪除）")
    void changesAreLiveWhenTheAdminResponseArrives() {
        RuleDto rule = createHttpRule("/nowait/edit", "GET", "old");
        assertThat(mock("/nowait/edit").getBody()).isEqualTo("old");

        RuleDto current = adminClient().getForObject("/api/admin/rules/" + rule.getId(), RuleDto.class);
        current.setResponseBody("new");
        current.setResponseId(null);
        ResponseEntity<RuleDto> saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(current), RuleDto.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mock("/nowait/edit").getBody()).isEqualTo("new");

        ResponseEntity<Void> deleted = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
        assertThat(deleted.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(mock("/nowait/edit").getStatusCode()).isNotEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("批次匯入 50 條：只等一次重建，回應時全部規則都已生效")
    void batchImportWaitsOnceAndEveryRuleIsLive() {
        createHttpRule("/nowait/warm", "GET", "warm");
        assertThat(mock("/nowait/warm").getBody()).isEqualTo("warm");
        List<RuleDto> rules = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rules.add(RuleDto.builder().protocol(Protocol.HTTP).matchKey("/nowait/bulk/" + i).method("GET")
                    .status(200).responseBody("bulk" + i).build());
        }

        SLOW_BUILD_MS.set(200);
        long started = System.nanoTime();
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> imported = adminClient().postForEntity("/api/admin/rules/import-batch", rules, Map.class);
        long tookMs = millisSince(started);
        SLOW_BUILD_MS.set(0);

        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(imported.getBody()).containsEntry("imported", 50);
        // 50 次存檔若各等一次 200 ms 的重建要 10 秒以上；合併後只等少數幾次
        assertThat(tookMs).as("import of 50 rules took %d ms", tookMs).isLessThan(3_000);
        for (int i = 0; i < 50; i += 7) {
            assertThat(mock("/nowait/bulk/" + i).getBody()).isEqualTo("bulk" + i);
        }
        assertThat(mock("/nowait/bulk/49").getBody()).isEqualTo("bulk49");
    }
}
