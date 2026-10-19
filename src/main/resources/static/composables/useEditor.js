/**
 * useEditor - Content format detection
 *
 * Viewing and editing of response content are changed to use the shared ui-code-viewer (line number, syntax coloring, search, line wrapping, formatting, copying).
 * Only detectMode is retained here to determine the content of the requested record JSON/XML/plain text.
 *
 * @returns {{ detectMode: Function }}
 */
const useEditor = () => {
    /** Detect text format and return CodeMirror mode string */
    const detectMode = (text) => {
        const s = (text || '').trim();
        if (s.startsWith('{') || s.startsWith('[')) return 'application/json';
        if (s.startsWith('<')) return 'xml';
        return 'text/plain';
    };

    return { detectMode };
};
