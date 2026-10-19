package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Response;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.repository.CacheEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repeating the same operation (such as double-clicking): the version will not be changed if there are no actual changes, and reconstruction and synchronization will not be triggered.
 * Don't let people who are editing encounter fake version conflicts; real changes still take effect as usual.
 * Only when multi-instance synchronization is enabled can you see whether synchronization events are sent.
 */
@TestPropertySource(properties = "echo.cache.sync-enabled=true")
class RepeatedActionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CacheEventRepository cacheEvents;

    @Test
    @DisplayName("Repeated disabling preserves the version and concurrent edits while disable/re-enable operations still take effect")
    void repeatedDisableIsANoOp() {
        RuleDto rule = createHttpRule("/api/repeat-toggle", "GET", "{\"ok\":true}");
        long initial = cacheEvents.count();
        assertThat(state(rule.getId(), "disable")).isEqualTo(HttpStatus.OK);
        assertThat(cacheEvents.count()).as("a real change is synced").isGreaterThan(initial);
        RuleDto mine = get(rule.getId());
        long events = cacheEvents.count();

        assertThat(state(rule.getId(), "disable")).isEqualTo(HttpStatus.OK);

        assertThat(get(rule.getId()).getVersion()).isEqualTo(mine.getVersion());
        assertThat(cacheEvents.count()).as("no sync event for a no-op").isEqualTo(events);
        mine.setDescription("edited after a double click");
        assertThat(put(mine).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/mock/api/repeat-toggle", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(state(rule.getId(), "enable")).isEqualTo(HttpStatus.OK);
        assertThat(get(rule.getId()).getEnabled()).isTrue();
        assertThat(restTemplate.getForEntity("/mock/api/repeat-toggle", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Disabling creates one revision record and increments the version once")
    void oneDisableIsOneVersionAndOneAuditEntry() {
        RuleDto rule = createHttpRule("/api/repeat-audit", "GET", "{}");
        long before = get(rule.getId()).getVersion();

        state(rule.getId(), "disable");
        state(rule.getId(), "disable");

        assertThat(get(rule.getId()).getVersion()).isEqualTo(before + 1);
        List<Map> audits = adminClient().getForObject("/api/admin/rules/" + rule.getId() + "/audit", List.class);
        assertThat(audits).filteredOn(a -> "UPDATE".equals(a.get("action"))).hasSize(1);
    }

    @Test
    @DisplayName("Repeated protection succeeds, missing rules return 404, and batches report only actual changes")
    void repeatedProtect() {
        RuleDto rule = createHttpRule("/api/repeat-protect", "GET", "{}");
        assertThat(state(rule.getId(), "protect")).isEqualTo(HttpStatus.OK);
        long version = get(rule.getId()).getVersion();

        assertThat(state(rule.getId(), "protect")).isEqualTo(HttpStatus.OK);
        assertThat(get(rule.getId()).getVersion()).isEqualTo(version);
        assertThat(state("no-such-rule", "protect")).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> batch = adminClient().exchange("/api/admin/rules/batch/protect", HttpMethod.PUT,
                new HttpEntity<>(List.of(rule.getId())), Map.class);
        assertThat(batch.getBody()).containsEntry("updated", 0);
    }

    @Test
    @DisplayName("Deleting nonexistent rules in a batch emits no synchronization event")
    void deletingNothingPublishesNothing() {
        long events = cacheEvents.count();
        ResponseEntity<Map> deleted = adminClient().exchange("/api/admin/rules/batch", HttpMethod.DELETE,
                new HttpEntity<>(List.of("missing-1", "missing-2")), Map.class);
        assertThat(deleted.getBody()).containsEntry("deleted", 0);
        assertThat(cacheEvents.count()).isEqualTo(events);
    }

    @Test
    @DisplayName("Response no-ops emit no synchronization event; stale saves return 409 and preserve other edits")
    void responseSaves() {
        Response created = createResponse("repeat-response", "{\"v\":1}");
        Response mine = adminClient().getForObject("/api/admin/responses/" + created.getId(), Response.class);
        long events = cacheEvents.count();

        ResponseEntity<Map> sameContent = putResponse(created.getId(),
                Map.of("description", "renamed only", "body", "{\"v\":1}"));
        assertThat(sameContent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cacheEvents.count()).as("description-only edit").isEqualTo(events);

        ResponseEntity<Map> stale = putResponse(created.getId(),
                Map.of("description", "mine", "body", "{\"v\":2}", "version", mine.getVersion()));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("error", "OPTIMISTIC_LOCK_CONFLICT");
        Response current = adminClient().getForObject("/api/admin/responses/" + created.getId(), Response.class);
        assertThat(current.getBody()).isEqualTo("{\"v\":1}");
        assertThat(current.getDescription()).isEqualTo("renamed only");

        ResponseEntity<Map> fresh = putResponse(created.getId(),
                Map.of("description", "mine", "body", "{\"v\":2}", "version", current.getVersion()));
        assertThat(fresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cacheEvents.count()).as("real content change is synced").isGreaterThan(events);

        ResponseEntity<Map> legacy = putResponse(created.getId(), Map.of("body", "{\"v\":3}"));
        assertThat(legacy.getStatusCode()).as("callers without a version still update").isEqualTo(HttpStatus.OK);
    }

    private HttpStatus state(String id, String action) {
        return HttpStatus.valueOf(adminClient().exchange("/api/admin/rules/" + id + "/" + action,
                HttpMethod.PUT, null, Map.class).getStatusCode().value());
    }

    private RuleDto get(String id) {
        return adminClient().getForObject("/api/admin/rules/" + id, RuleDto.class);
    }

    private ResponseEntity<Map> put(RuleDto dto) {
        return adminClient().exchange("/api/admin/rules/" + dto.getId(), HttpMethod.PUT, new HttpEntity<>(dto), Map.class);
    }

    private ResponseEntity<Map> putResponse(Long id, Map<String, Object> body) {
        return adminClient().exchange("/api/admin/responses/" + id, HttpMethod.PUT, new HttpEntity<>(body), Map.class);
    }
}
