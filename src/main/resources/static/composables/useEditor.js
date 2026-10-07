/**
 * useEditor - 內容格式偵測
 *
 * 回應內容的檢視與編輯都改用共用的 ui-code-viewer（行號、語法上色、搜尋、換行、格式化、複製）。
 * 這裡只保留 detectMode，給請求記錄的內容判斷 JSON／XML／純文字。
 *
 * @returns {{ detectMode: Function }}
 */
const useEditor = () => {
    /** 偵測文字格式，回傳 CodeMirror mode 字串 */
    const detectMode = (text) => {
        const s = (text || '').trim();
        if (s.startsWith('{') || s.startsWith('[')) return 'application/json';
        if (s.startsWith('<')) return 'xml';
        return 'text/plain';
    };

    return { detectMode };
};
