/**
 * useEditor - CodeMirror 編輯器管理 Composable
 *
 * 管理 CodeMirror 編輯器實例的建立、銷毀與格式切換。
 * 支援三個編輯器區域：預覽（preview）、編輯（edit）、回應表單（responseForm）。
 * 提供 detectMode 自動偵測文字格式（JSON/XML/純文字），
 * 以及 renderEditor 統一渲染邏輯（CodeMirror / 純文字 pre / textarea）。
 *
 * @param {Function} t - 翻譯函式（來自 useI18n）
 * @returns {{ editors: Object, previewFormatted: Ref<boolean>, editFormatted: Ref<boolean>, responseFormFormatted: Ref<boolean>, previewEditorRef: Object, editEditorRef: Object, responseFormEditorRef: Object, renderEditor: Function, detectMode: Function, togglePreviewFormat: Function, toggleEditFormat: Function, toggleResponseFormFormat: Function, cleanupEditors: Function }}
 */
const useEditor = (t) => {
    const { ref } = Vue;

    const previewEditorRef = { get value() { return document.getElementById('rulePreviewEditor'); } };
    const editEditorRef = { get value() { return document.getElementById('ruleEditEditor'); } };
    const responseFormEditorRef = { get value() { return document.getElementById('responseFormEditorEl'); } };

    const previewFormatted = ref(false);
    const editFormatted = ref(false);
    const responseFormFormatted = ref(false);

    /** 偵測文字格式，回傳 CodeMirror mode 字串 */
    const detectMode = (text) => {
        const s = (text || '').trim();
        if (s.startsWith('{') || s.startsWith('[')) return 'application/json';
        if (s.startsWith('<')) return 'xml';
        return 'text/plain';
    };

    const LARGE_FILE_THRESHOLD = 512 * 1024;
    const editors = { preview: null, edit: null, responseForm: null };
    const renderIds = { preview: 0, edit: 0, responseForm: 0 };

    /**
     * 渲染編輯器：根據 formatted 狀態決定使用 CodeMirror、純文字 pre 或 textarea
     * @param {string} key - 編輯器 key（preview / edit / responseForm）
     * @param {Object} elRef - DOM 元素參考（getter ref）
     * @param {string} text - 文字內容
     * @param {boolean} readOnly - 是否唯讀
     * @param {Function} [onChange] - 內容變更回呼
     */
    const renderEditor = (key, elRef, text, readOnly, onChange) => {
        if (editors[key]) { try { editors[key].toTextArea(); } catch { /* ignore */ } editors[key] = null; }
        const rid = ++renderIds[key];
        setTimeout(() => {
            if (renderIds[key] !== rid) return;
            if (!elRef.value) return;
            elRef.value.innerHTML = '';
            const formatted = key === 'preview' ? previewFormatted.value : key === 'edit' ? editFormatted.value : responseFormFormatted.value;
            if (formatted) {
                editors[key] = CodeMirror(elRef.value, { value: text || '', mode: detectMode(text), readOnly, lineNumbers: true, lineWrapping: true, theme: 'default' });
                if (onChange) editors[key].on('change', () => onChange(editors[key].getValue()));
            } else if (readOnly) {
                const pre = document.createElement('pre');
                pre.className = 'plain-preview';
                pre.textContent = text || '';
                elRef.value.appendChild(pre);
            } else {
                const ta = document.createElement('textarea');
                ta.className = 'form-control response-body-editor';
                ta.placeholder = key === 'responseForm' ? t('modal.responseBodyPlaceholder') : t('modal.editorPlaceholder');
                ta.value = text || '';
                if (onChange) ta.addEventListener('input', e => onChange(e.target.value));
                elRef.value.appendChild(ta);
            }
        }, 20);
    };

    /** 切換預覽區格式化 */
    const togglePreviewFormat = () => { previewFormatted.value = !previewFormatted.value; };

    /** 切換編輯區格式化 */
    const toggleEditFormat = () => { editFormatted.value = !editFormatted.value; };

    /** 切換回應表單格式化 */
    const toggleResponseFormFormat = () => { responseFormFormatted.value = !responseFormFormatted.value; };

    /** 清除所有編輯器實例，供 onUnmounted 呼叫 */
    const cleanupEditors = () => {
        Object.keys(editors).forEach(k => {
            if (editors[k]) { try { editors[k].toTextArea(); } catch { /* ignore */ } editors[k] = null; }
        });
    };

    return {
        editors,
        previewFormatted,
        editFormatted,
        responseFormFormatted,
        previewEditorRef,
        editEditorRef,
        responseFormEditorRef,
        renderEditor,
        detectMode,
        togglePreviewFormat,
        toggleEditFormat,
        toggleResponseFormFormat,
        cleanupEditors
    };
};
