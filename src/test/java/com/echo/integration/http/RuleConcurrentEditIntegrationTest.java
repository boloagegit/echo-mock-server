package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Editing the same rule at the same time: saving with an outdated version will be rejected and will not overwrite other people's modifications (including quick operations such as enable/disable). */
class RuleConcurrentEditIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("另一人修改後，用舊版本存檔回 409，規則保持別人的修改")
    void staleSaveIsRejected() {
        RuleDto rule = createHttpRule("/api/concurrent-edit", "GET", "{\"v\":1}", null, 200,
                null, null, null, null, null, 0, "original");
        RuleDto mine = get(rule.getId());
        RuleDto theirs = get(rule.getId());

        theirs.setDescription("changed by someone else");
        assertThat(put(theirs).getStatusCode()).isEqualTo(HttpStatus.OK);

        mine.setDescription("my change");
        ResponseEntity<Map> stale = put(mine);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("error", "OPTIMISTIC_LOCK_CONFLICT");
        assertThat(get(rule.getId()).getDescription()).isEqualTo("changed by someone else");

        // Saving can work normally after reloading the latest version
        RuleDto latest = get(rule.getId());
        latest.setDescription("my change");
        assertThat(put(latest).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(rule.getId()).getDescription()).isEqualTo("my change");
    }

    @Test
    @DisplayName("編輯期間規則被停用：舊版本存檔被擋下，不會把它改回啟用")
    void quickActionsAlsoChangeTheVersion() {
        RuleDto rule = createHttpRule("/api/concurrent-toggle", "GET", "{}", null, 200,
                null, null, null, null, null, 0, "toggle");
        RuleDto mine = get(rule.getId());

        ResponseEntity<Map> disabled = adminClient().exchange("/api/admin/rules/" + rule.getId() + "/disable",
                HttpMethod.PUT, null, Map.class);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(rule.getId()).getVersion()).isGreaterThan(mine.getVersion());

        mine.setDescription("edited while disabled");
        assertThat(put(mine).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get(rule.getId()).getEnabled()).isFalse();
    }

    @Test
    @DisplayName("沒有送版本的呼叫端維持原本的行為")
    void callersWithoutVersionStillUpdate() {
        RuleDto rule = createHttpRule("/api/concurrent-legacy", "GET", "{}", null, 200,
                null, null, null, null, null, 0, "legacy");
        RuleDto update = get(rule.getId());
        update.setVersion(null);
        update.setDescription("script update");

        assertThat(put(update).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(rule.getId()).getDescription()).isEqualTo("script update");
    }

    private RuleDto get(String id) {
        return adminClient().getForObject("/api/admin/rules/" + id, RuleDto.class);
    }

    private ResponseEntity<Map> put(RuleDto dto) {
        return adminClient().exchange("/api/admin/rules/" + dto.getId(), HttpMethod.PUT, new HttpEntity<>(dto), Map.class);
    }
}
