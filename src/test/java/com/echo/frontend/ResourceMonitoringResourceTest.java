package com.echo.frontend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceMonitoringResourceTest {
    @Test
    void panelIsIndependentlyLoadedAndUsesManualRefreshOnly() throws Exception {
        String panel = text("static/components/ResourceMonitoringPanel.js");
        String settings = text("static/components/SettingsPage.js");
        String index = text("static/index.html");
        assertThat(panel).contains("mounted() { this.loadSnapshot(); }")
                .contains("watch: { refreshToken() { this.loadSnapshot(); } }")
                .contains("if (this.busy || this.disposed) return;")
                .contains("this.requestController?.abort();")
                .contains("clearTimeout(deadline)")
                .doesNotContain("setInterval", "loadStatus", "/api/admin/status", "status()", "localStorage");
        assertThat(settings).contains("this.resourceRefreshToken++", "@click=\"refreshStatus\"")
                .contains("v-if=\"isAdmin\" v-show=\"activeTab === 'overview' || activeTab === 'monitoring'\"")
                .contains(":refresh-token=\"resourceRefreshToken\"")
                .contains("<ui-tabs")
                .contains("<template v-if=\"status\">");
        assertThat(index.indexOf("ResourceMonitoringPanel.js")).isLessThan(index.indexOf("/app.js"));
        assertThat(text("static/app.js")).contains("_app.component('resource-monitoring-panel', ResourceMonitoringPanel)");
    }

    @Test
    void everyDisplayedFieldHasBothTranslations() throws Exception {
        String panel = text("static/components/ResourceMonitoringPanel.js");
        var declaration = Pattern.compile("const fields = \\{(.*?)\\n      \\};", Pattern.DOTALL).matcher(panel);
        assertThat(declaration.find()).isTrue();
        for (String locale : List.of("en", "zh-TW")) {
            var i18n = new ObjectMapper().readTree(text("static/i18n/" + locale + ".json")).path("monitoring");
            var metrics = Pattern.compile("'([A-Za-z][A-Za-z0-9_]*)'").matcher(declaration.group(1));
            while (metrics.find()) assertThat(i18n.path("metrics").path(metrics.group(1)).asText()).isNotBlank();
            for (String group : List.of("jvm", "caches", "scheduler", "jms", "http", "database", "requestLog", "applicationLog", "storage"))
                assertThat(i18n.path("groups").path(group).asText()).isNotBlank();
            for (String state : List.of("AVAILABLE", "PARTIAL", "DISABLED", "UNSUPPORTED", "FAILED"))
                assertThat(i18n.path("states").path(state).asText()).isNotBlank();
            for (String group : List.of("jvm", "jms", "http", "database", "requestLog", "storage"))
                assertThat(i18n.path("summary").path(group).asText()).isNotBlank();
            for (String group : List.of("memory", "cache", "jms", "http", "database", "scheduler")) {
                assertThat(i18n.path("details").path(group).asText()).isNotBlank();
                assertThat(i18n.path("detailNotes").path(group).asText()).isNotBlank();
            }
            assertThat(i18n.path("cumulativeHint").asText()).isNotBlank();
            assertThat(i18n.path("incompleteGroups").asText()).isNotBlank();
        }
    }

    private static String text(String path) throws Exception {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
