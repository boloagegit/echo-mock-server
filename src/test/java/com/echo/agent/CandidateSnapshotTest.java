package com.echo.agent;

import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CandidateSnapshot 單元測試。
 * <p>
 * 驗證 Pipeline 整合中 Rule → CandidateSnapshot 的欄位映射與深拷貝正確性。
 * <p>
 * Validates: Requirements 11.1, 11.3
 */
class CandidateSnapshotTest {

    @Nested
    @DisplayName("HttpRule → CandidateSnapshot 欄位映射")
    class FromHttpRuleTest {

        @Test
        @DisplayName("所有欄位正確映射")
        void shouldMapAllFieldsFromHttpRule() {
            HttpRule rule = HttpRule.builder()
                    .id("http-001")
                    .matchKey("/api/orders")
                    .description("Order API rule")
                    .enabled(true)
                    .bodyCondition("type=ORDER")
                    .queryCondition("status=active")
                    .headerCondition("X-Tenant=abc")
                    .priority(10)
                    .delayMs(0L)
                    .httpStatus(200)
                    .build();

            CandidateSnapshot snapshot = CandidateSnapshot.fromHttpRule(rule);

            assertThat(snapshot.getRuleId()).isEqualTo("http-001");
            assertThat(snapshot.getEndpoint()).isEqualTo("/api/orders");
            assertThat(snapshot.getDescription()).isEqualTo("Order API rule");
            assertThat(snapshot.isEnabled()).isTrue();
            assertThat(snapshot.getBodyCondition()).isEqualTo("type=ORDER");
            assertThat(snapshot.getQueryCondition()).isEqualTo("status=active");
            assertThat(snapshot.getHeaderCondition()).isEqualTo("X-Tenant=abc");
            assertThat(snapshot.getPriority()).isEqualTo(10);
        }

        @Test
        @DisplayName("null 條件欄位正確映射為 null")
        void shouldMapNullConditionsFromHttpRule() {
            HttpRule rule = HttpRule.builder()
                    .id("http-002")
                    .matchKey("/api/health")
                    .enabled(true)
                    .priority(0)
                    .delayMs(0L)
                    .httpStatus(200)
                    .build();

            CandidateSnapshot snapshot = CandidateSnapshot.fromHttpRule(rule);

            assertThat(snapshot.getRuleId()).isEqualTo("http-002");
            assertThat(snapshot.getEndpoint()).isEqualTo("/api/health");
            assertThat(snapshot.getBodyCondition()).isNull();
            assertThat(snapshot.getQueryCondition()).isNull();
            assertThat(snapshot.getHeaderCondition()).isNull();
        }
    }

    @Nested
    @DisplayName("JmsRule → CandidateSnapshot 欄位映射")
    class FromJmsRuleTest {

        @Test
        @DisplayName("所有欄位正確映射，queryCondition 與 headerCondition 為 null")
        void shouldMapAllFieldsFromJmsRule() {
            JmsRule rule = JmsRule.builder()
                    .id("jms-001")
                    .queueName("ORDER.REQUEST")
                    .description("JMS order queue rule")
                    .enabled(true)
                    .bodyCondition("type=ORDER")
                    .priority(5)
                    .delayMs(0L)
                    .build();

            CandidateSnapshot snapshot = CandidateSnapshot.fromJmsRule(rule);

            assertThat(snapshot.getRuleId()).isEqualTo("jms-001");
            assertThat(snapshot.getEndpoint()).isEqualTo("ORDER.REQUEST");
            assertThat(snapshot.getDescription()).isEqualTo("JMS order queue rule");
            assertThat(snapshot.isEnabled()).isTrue();
            assertThat(snapshot.getBodyCondition()).isEqualTo("type=ORDER");
            assertThat(snapshot.getQueryCondition()).isNull();
            assertThat(snapshot.getHeaderCondition()).isNull();
            assertThat(snapshot.getPriority()).isEqualTo(5);
        }

        @Test
        @DisplayName("disabled JmsRule 正確映射 enabled=false")
        void shouldMapDisabledJmsRule() {
            JmsRule rule = JmsRule.builder()
                    .id("jms-002")
                    .queueName("PAYMENT.*")
                    .enabled(false)
                    .priority(0)
                    .delayMs(0L)
                    .build();

            CandidateSnapshot snapshot = CandidateSnapshot.fromJmsRule(rule);

            assertThat(snapshot.isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("toCandidateSnapshots 深拷貝與邊界條件")
    class ToCandidateSnapshotsTest {

        @Test
        @DisplayName("null candidates 回傳空列表")
        void shouldReturnEmptyListForNullCandidates() {
            List<CandidateSnapshot> result = CandidateSnapshot.toCandidateSnapshots(null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("empty candidates 回傳空列表")
        void shouldReturnEmptyListForEmptyCandidates() {
            List<CandidateSnapshot> result = CandidateSnapshot.toCandidateSnapshots(Collections.emptyList());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("混合 HttpRule 與 JmsRule 正確轉換")
        void shouldConvertMixedRuleTypes() {
            HttpRule httpRule = HttpRule.builder()
                    .id("http-mix-1")
                    .matchKey("/api/users")
                    .description("HTTP rule")
                    .enabled(true)
                    .bodyCondition("name=test")
                    .queryCondition("page=1")
                    .headerCondition("Accept=json")
                    .priority(1)
                    .delayMs(0L)
                    .httpStatus(200)
                    .build();

            JmsRule jmsRule = JmsRule.builder()
                    .id("jms-mix-1")
                    .queueName("USER.QUEUE")
                    .description("JMS rule")
                    .enabled(true)
                    .bodyCondition("action=create")
                    .priority(2)
                    .delayMs(0L)
                    .build();

            // Use ArrayList to allow mixed types via BaseRule list
            List result = CandidateSnapshot.toCandidateSnapshots(
                    new ArrayList<>(List.of(httpRule, jmsRule)));

            assertThat(result).hasSize(2);

            CandidateSnapshot httpSnapshot = (CandidateSnapshot) result.get(0);
            assertThat(httpSnapshot.getRuleId()).isEqualTo("http-mix-1");
            assertThat(httpSnapshot.getEndpoint()).isEqualTo("/api/users");
            assertThat(httpSnapshot.getQueryCondition()).isEqualTo("page=1");

            CandidateSnapshot jmsSnapshot = (CandidateSnapshot) result.get(1);
            assertThat(jmsSnapshot.getRuleId()).isEqualTo("jms-mix-1");
            assertThat(jmsSnapshot.getEndpoint()).isEqualTo("USER.QUEUE");
            assertThat(jmsSnapshot.getQueryCondition()).isNull();
        }

        @Test
        @DisplayName("深拷貝 — 修改原始 Rule 不影響 Snapshot")
        void shouldDeepCopyIndependentFromOriginalRule() {
            HttpRule rule = HttpRule.builder()
                    .id("http-deep-1")
                    .matchKey("/api/original")
                    .description("original desc")
                    .enabled(true)
                    .bodyCondition("field=value")
                    .queryCondition("q=1")
                    .headerCondition("H=v")
                    .priority(5)
                    .delayMs(100L)
                    .httpStatus(200)
                    .build();

            List<CandidateSnapshot> snapshots = CandidateSnapshot.toCandidateSnapshots(List.of(rule));
            CandidateSnapshot snapshot = snapshots.get(0);

            // Mutate the original rule
            rule.setId("http-deep-CHANGED");
            rule.setMatchKey("/api/changed");
            rule.setDescription("changed desc");
            rule.setEnabled(false);
            rule.setBodyCondition("changed=true");
            rule.setQueryCondition("changed=yes");
            rule.setHeaderCondition("Changed=header");
            rule.setPriority(999);

            // Snapshot should retain original values
            assertThat(snapshot.getRuleId()).isEqualTo("http-deep-1");
            assertThat(snapshot.getEndpoint()).isEqualTo("/api/original");
            assertThat(snapshot.getDescription()).isEqualTo("original desc");
            assertThat(snapshot.isEnabled()).isTrue();
            assertThat(snapshot.getBodyCondition()).isEqualTo("field=value");
            assertThat(snapshot.getQueryCondition()).isEqualTo("q=1");
            assertThat(snapshot.getHeaderCondition()).isEqualTo("H=v");
            assertThat(snapshot.getPriority()).isEqualTo(5);
        }

        @Test
        @DisplayName("深拷貝 — 修改原始 JmsRule 不影響 Snapshot")
        void shouldDeepCopyJmsRuleIndependentFromOriginal() {
            JmsRule rule = JmsRule.builder()
                    .id("jms-deep-1")
                    .queueName("ORIGINAL.QUEUE")
                    .description("original jms")
                    .enabled(true)
                    .bodyCondition("key=val")
                    .priority(3)
                    .delayMs(0L)
                    .build();

            List<CandidateSnapshot> snapshots = CandidateSnapshot.toCandidateSnapshots(List.of(rule));
            CandidateSnapshot snapshot = snapshots.get(0);

            // Mutate the original rule
            rule.setId("jms-deep-CHANGED");
            rule.setQueueName("CHANGED.QUEUE");
            rule.setDescription("changed jms");
            rule.setEnabled(false);
            rule.setBodyCondition("changed=true");
            rule.setPriority(888);

            // Snapshot should retain original values
            assertThat(snapshot.getRuleId()).isEqualTo("jms-deep-1");
            assertThat(snapshot.getEndpoint()).isEqualTo("ORIGINAL.QUEUE");
            assertThat(snapshot.getDescription()).isEqualTo("original jms");
            assertThat(snapshot.isEnabled()).isTrue();
            assertThat(snapshot.getBodyCondition()).isEqualTo("key=val");
            assertThat(snapshot.getPriority()).isEqualTo(3);
        }
    }
}
