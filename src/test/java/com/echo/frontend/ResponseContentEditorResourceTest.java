package com.echo.frontend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回應內容編輯：規則編輯器與回應對話框共用抽屜的 ui-code-viewer；格式化只改變顯示（存檔是原文），
 * 內容可放大到整個編輯區，選擇回應的清單換頁時不會變空白。
 */
class ResponseContentEditorResourceTest {
    @Test
    void sharedViewerEditsTheOriginalAndFormatsOnlyTheView() throws IOException {
        String viewer = text("components/UiCodeViewer.js");

        assertThat(viewer)
                .contains("editable: { type: Boolean, default: false }")
                .contains("fill: { type: Boolean, default: false }")
                .contains("emits: ['copy', 'update:value']")
                // Read-only content opens formatted (as in the drawers); editable content opens as written.
                .contains("formatted: !this.editable")
                // While the formatted view is on the editor is read-only, so what is saved is always the original.
                .contains("return !this.editable || (this.formatted && this.canFormat);")
                .contains("if (change.origin === 'setValue' || this.readOnly) return;")
                .contains("this.$emit('update:value', cm.getValue());")
                // Typing never resets the cursor: the text is only replaced when it really differs.
                .contains("if (this.cm.getValue() !== value) this.cm.setValue(value);")
                .contains("editable(value) { this.formatted = !value; }")
                .contains("<span v-if=\"editable && readOnly\" class=\"ui-code-viewer__mode\">{{t('codeViewer.formattedReadOnly')}}</span>");
        for (String lang : new String[]{"zh-TW", "en"}) {
            assertThat(text("i18n/" + lang + ".json")).as(lang)
                    .contains("\"formattedReadOnly\"").contains("\"expandContent\"").contains("\"collapseContent\"");
        }
    }

    @Test
    void ruleEditorAndResponseDialogUseTheSharedViewer() throws IOException {
        String rule = text("components/RuleEditModal.js");
        String dialog = text("components/ResponseEditModal.js");
        String index = text("index.html");

        assertThat(rule)
                .contains("@update:value=\"$emit('update:preview-edit-body', $event)\"")
                .contains("@update:value=\"form.responseBody = $event\"")
                .doesNotContain("toggle-preview-format")
                .doesNotContain("previewFormatted");
        assertThat(dialog)
                .contains("<ui-code-viewer class=\"response-form-code\" fill editable")
                .contains("@update:value=\"updateBody\"")
                .doesNotContain("responseFormEditorEl")
                .doesNotContain("toggle-format");
        assertThat(index)
                .contains("@update:preview-edit-body=\"previewEditBody = $event\"")
                .contains("@copy-text=\"clipCopy($event)\"")
                .doesNotContain("toggleResponseFormFormat");
        // The old id-based editors (re-created after a 20 ms timeout, which showed an empty box) are gone.
        assertThat(text("composables/useEditor.js")).doesNotContain("renderEditor");
        assertThat(text("composables/useRuleForm.js")).doesNotContain("renderEditor");
        assertThat(text("app.js")).doesNotContain("renderEditor");
    }

    @Test
    void responseContentCanTakeOverTheEditorAndEscReturns() throws IOException {
        String rule = text("components/RuleEditModal.js");
        String console = text("console.css");

        assertThat(rule)
                .contains("const contentExpanded = ref(false);")
                .contains("else if (contentExpanded.value) contentExpanded.value = false;")
                .contains(":class=\"{'is-content-expanded': contentExpanded}\"")
                .contains("class=\"rule-response-expand\"");
        assertThat(console)
                .contains(".rule-editor.is-content-expanded .response-content-block {\n    position: absolute;\n    inset: 0;")
                .contains(".rule-editor.is-content-expanded .rule-right-content > :not(.response-content-block) { visibility: hidden }");
    }

    @Test
    void responsePickerKeepsItsRowsWhileTheNextPageLoads() throws IOException {
        String rule = text("components/RuleEditModal.js");

        assertThat(rule)
                .contains("<div v-if=\"responsePickerLoading && !filteredResponsePicker.length\" class=\"response-picker-state loading-reveal\">")
                .contains(":class=\"{'is-stale': responsePickerLoading && filteredResponsePicker.length}\"")
                .contains("responsePickerResults.value.scrollTop = 0;")
                .doesNotContain(":disabled=\"responsePickerPage <= 0 || responsePickerLoading\"");
        assertThat(text("console.css"))
                .contains(".response-picker-drawer-results { scrollbar-gutter: stable }")
                .contains(".response-picker-drawer-results.is-stale > .response-picker-drawer-item { opacity: 0.55;");
    }

    @Test
    void responsePaneUsesOneLabelColumnAndOneToolbarRowWithIconActions() throws IOException {
        String rule = text("components/RuleEditModal.js");
        String viewer = text("components/UiCodeViewer.js");
        String console = text("console.css");

        // Wrap, format and copy are icon buttons with names; the label and state sit in the toolbar's lead slot.
        assertThat(viewer)
                .contains("<div v-if=\"$slots.lead\" class=\"ui-code-viewer__lead\"><slot name=\"lead\"></slot></div>")
                .contains(":title=\"t('codeViewer.wrap')\" :aria-label=\"t('codeViewer.wrap')\"")
                .contains(":title=\"t('codeViewer.format')\" :aria-label=\"t('codeViewer.format')\"");
        assertThat(rule)
                .contains("class=\"response-edit-toggle\" @click=\"$emit('toggle-preview-editing')\" :title=\"editResponseLabel\" :aria-label=\"editResponseLabel\"")
                .contains(":title=\"t('modal.searchDifferentResponseLabel')\" :aria-label=\"t('modal.searchDifferentResponseLabel')\"")
                .contains("<strong>{{t('modal.currentSelection')}}</strong>")
                .doesNotContain("class=\"response-selected-label\"");
        assertThat(console)
                .contains(".mock-result-settings { --result-label-w: 148px }")
                .contains(".mock-result-settings .response-existing-panel.has-selection { display: grid; grid-template-columns: var(--result-label-w) minmax(0, 1fr);")
                .contains(".rule-editor .result-advanced-summary-icon { display: none }");
    }

    @Test
    void onlyTheEditorScrollsAndThePickerAlwaysStartsAtThePaneTop() throws IOException {
        String console = text("console.css");

        // The body fills what the settings leave (220px minimum) instead of a fixed 420px that pushed the
        // editor and its status line below the pane; scrolling inside it does not carry on into the pane.
        assertThat(console)
                .contains(".rule-editor .response-content-block { flex: 1 1 auto; min-height: 220px }")
                .contains(".ui-code-viewer__host .CodeMirror-scroll { overscroll-behavior: contain }")
                .contains(".rule-right:has(> .response-picker-drawer) { overflow: hidden }")
                .contains(".rule-editor .response-picker-drawer { inset: 0;");
        assertThat(text("components/RuleEditModal.js"))
                .contains("dialogRef.value?.querySelector('.rule-right')?.scrollTo({ top: 0 });");
    }

    private static String text(String name) throws IOException {
        return new ClassPathResource("static/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
