package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interface easter egg: ↑↑↓↓←→←→AB or quickly click the Echo icon 5 times to open Echo Strike. Only the trigger is resident, and the game is loaded after it is triggered;
 * The switch (ECHO_UI_EASTER_EGG) is set by the server. It will not be accidentally touched when typing, nor will it be connected to any external resources.
 * Behavior details (levels, bombs, transitions, connection points) are verified by the actual execution of src/test/js/echo-strike.test.cjs.
 */
class EasterEggResourceTest {
    @Test
    void onlyTheSmallTriggerLoadsWithThePageAndTheGameLoadsOnDemand() throws IOException {
        String index = text("index.html");
        String trigger = text("easter-egg.js");

        assertThat(index).contains("<script src=\"/easter-egg.js?v=").doesNotContain("echo-strike.js");
        assertThat(index.indexOf("/easter-egg.js?v=")).isPositive().isLessThan(index.indexOf("/app.js?v="));
        assertThat(trigger)
                .contains("var GAME_SRC = '/echo-strike.js?v=")
                .contains("script.src = GAME_SRC;");
    }

    @Test
    void triggerFollowsTheServerSwitchAndIgnoresTyping() throws IOException {
        String trigger = text("easter-egg.js");

        assertThat(trigger)
                .contains("var enabled = false;")
                .contains("setEnabled: function (value) {").contains("enabled = value === true;")
                .contains("if (!enabled || event.repeat || event.ctrlKey || event.metaKey || event.altKey) return;")
                .contains("target.closest('input, textarea, select, [contenteditable=\"true\"], .CodeMirror')")
                .contains("var ENDINGS = ['ab', 'ba'];");
        assertThat(text("app.js"))
                .contains("window.EchoEasterEgg?.setEnabled(data.uiEasterEgg === true);")
                .contains("'uiAccent', 'uiEasterEgg',");
    }

    @Test
    void theLogoIsASecondEntranceAndTheHelpDialogDropsAHint() throws IOException {
        String trigger = text("easter-egg.js");

        assertThat(trigger)
                .contains("var BRAND = '.sidebar-brand';")
                .contains("document.addEventListener('click', function (event) {")
                .contains("else if (clicks >= 2) wink();\n    }, true);")
                .contains("if (!enabled || gameOpen()) return;")
                .contains("isEnabled: function () { return enabled; },");
        assertThat(text("components/SidebarNav.js")).contains("<div class=\"sidebar-brand\">");
        // The hint only shows while the server allows the game.
        assertThat(text("components/PriorityHelpModal.js"))
                .contains("secretHint.value = window.EchoEasterEgg?.isEnabled?.() === true;")
                .contains("<p v-if=\"secretHint\" class=\"sub-info help-secret\">{{t('help.secretHint')}}</p>");
        assertThat(text("i18n/en.json")).contains("\"secretHint\":");
        assertThat(text("i18n/zh-TW.json")).contains("\"secretHint\":");
    }

    @Test
    void gameIsSelfContainedAndHandsTheKeyboardBack() throws IOException {
        String game = text("echo-strike.js");

        // No network, no external assets: everything is drawn on the canvas.
        assertThat(game).doesNotContain("http://").doesNotContain("https://").doesNotContain("fetch(").doesNotContain("XMLHttpRequest");
        assertThat(game)
                .contains("window.addEventListener('keydown', onKeyDown, true);")
                .contains("e.stopImmediatePropagation();")
                .contains("window.removeEventListener('keydown', onKeyDown, true);")
                .contains("if (back && back.focus && document.contains(back)) back.focus();")
                .contains("document.addEventListener('visibilitychange', onVisibility);")
                .contains("(prefers-reduced-motion: reduce)");
    }

    @Test
    void overlaySitsAboveModalsAndRespectsReducedMotion() throws IOException {
        String console = text("console.css");
        String egg = console.substring(console.indexOf("/* ===== Easter egg (2026-10-08) ===== */"));

        assertThat(egg)
                .contains(".echo-strike {\n    position: fixed;\n    inset: 0;\n    z-index: 10000;")
                .contains("html.easter-egg-flash::after {")
                .contains("@media (prefers-reduced-motion: reduce) {\n    .echo-strike { transition: none }\n    html.easter-egg-flash::after { display: none }")
                .contains(".sidebar-brand.is-winking .brand-icon { animation: brand-wink 900ms")
                .contains(".sidebar-brand.is-winking .brand-icon, .sidebar-brand.is-winking .brand-mark::after { animation: none }");
    }

    private static String text(String name) throws IOException {
        return new ClassPathResource("static/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
