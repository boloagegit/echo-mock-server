/**
 * SseEvents - Calculations used in the rule editor SSE event list (excluding screens, can be tested separately):
 * The millisecond at which each event is sent, total length, time and size display, millisecond/second conversion, search,
 * And "paste the whole string": JSON event array or text/event-stream original text copied directly from the stream.
 */
const SseEvents = (() => {
  const MAX_DELAY_MS = 600000;
  const blank = () => ({ event: '', data: '', id: '', delayMs: 0, type: 'normal' });
  const delayOf = event => (Number.isFinite(event?.delayMs) && event.delayMs > 0 ? event.delayMs : 0);
  const isTerminal = event => event?.type === 'error' || event?.type === 'abort';

  /**
   * Events are sent in order, and the delay is the waiting time before sending. The connection is closed after the error event is sent, and the abort event is only waited for without sending;
   * Subsequent events will not be sent (state 'skipped', no time point).
   */
  const timeline = events => {
    let atMs = 0;
    let endIndex = -1;
    const rows = events.map((event, index) => {
      if (endIndex >= 0) return { atMs: null, state: 'skipped' };
      atMs += delayOf(event);
      if (isTerminal(event)) endIndex = index;
      return { atMs, state: event.type === 'abort' ? 'ends' : (event.type === 'error' ? 'ends-after' : 'sent') };
    });
    return { rows, totalMs: atMs, endIndex, skipped: endIndex >= 0 ? events.length - endIndex - 1 : 0 };
  };

  /**
   * A one-line summary of the rule details: several events, full length, whether it loops, and at which event the connection ends.
   * The stream ends there when there is an error/abort event, and it will not be replayed even if the loop is turned on (same as the server's player).
   */
  const summary = (events, loopEnabled) => {
    const list = Array.isArray(events) ? events : [];
    const line = timeline(list);
    const end = line.endIndex >= 0 ? list[line.endIndex].type : null;
    return { count: list.length, totalMs: line.totalMs, endAt: line.endIndex + 1, end,
      skipped: line.skipped, loops: !!loopEnabled && !end };
  };

  const trimNumber = value => String(Math.round(value * 1000) / 1000);
  /** 300 → 300 ms; 1500 → 1.5 seconds; 90000 → 1 minute and 30 seconds; 600000 → 10 minutes. */
  const formatDuration = (ms, t) => {
    const value = Math.max(0, Math.round(Number(ms) || 0));
    if (value < 1000) return t('sseEditor.durationMs', { ms: value });
    if (value < 60000) return t('sseEditor.durationSec', { s: trimNumber(value / 1000) });
    const minutes = Math.floor(value / 60000);
    const rest = value - minutes * 60000;
    return rest ? t('sseEditor.durationMinSec', { m: minutes, s: trimNumber(rest / 1000) })
      : t('sseEditor.durationMin', { m: minutes });
  };

  /** UTF-8 byte count without allocating a temporary array. */
  const byteLength = text => {
    const value = text == null ? '' : String(text);
    let bytes = 0;
    for (let i = 0; i < value.length; i++) {
      const code = value.charCodeAt(i);
      if (code < 0x80) bytes += 1;
      else if (code < 0x800) bytes += 2;
      else if (code >= 0xd800 && code <= 0xdbff && i + 1 < value.length) { bytes += 4; i++; }
      else bytes += 3;
    }
    return bytes;
  };
  const formatBytes = bytes => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return trimNumber(Math.round(bytes / 102.4) / 10) + ' KB';
    return trimNumber(Math.round(bytes / 104857.6) / 10) + ' MB';
  };

  /** A line displayed when collapsed: merge blanks, only look at the beginning (only the first short paragraph of the content of KB is processed). */
  const previewLine = (text, max = 160) => {
    const head = String(text ?? '').slice(0, max * 4).replace(/\s+/g, ' ').trim();
    return head.length > max ? head.slice(0, max) + '…' : head;
  };

  /** Unit when turned on: seconds for whole seconds (at least 1 second), milliseconds for everything else. */
  const unitFor = ms => (Number.isInteger(ms) && ms >= 1000 && ms % 1000 === 0 ? 's' : 'ms');
  const toUnit = (ms, unit) => (unit === 's' ? Math.round(ms) / 1000 : ms);
  /** Convert the input to milliseconds; return null if parsing fails. Round seconds to millisecond precision, for example 1.2345 s becomes 1235 ms. */
  const fromUnit = (value, unit) => {
    if (value === '' || value == null) return null;
    const number = Number(value);
    if (!Number.isFinite(number)) return null;
    return Math.round(unit === 's' ? number * 1000 : number);
  };
  const delayValid = ms => Number.isInteger(ms) && ms >= 0 && ms <= MAX_DELAY_MS;

  const matches = (event, query) => {
    const needle = String(query || '').trim().toLowerCase();
    if (!needle) return true;
    return [event.data, event.event, event.id].some(value => String(value ?? '').toLowerCase().includes(needle));
  };

  const fail = (key, params = {}) => ({ error: { key, params } });
  const fromJson = text => {
    let parsed;
    try { parsed = JSON.parse(text); } catch (e) { return fail('sseEditor.bulkJsonError', { message: e.message }); }
    if (!Array.isArray(parsed)) return fail('sseEditor.bulkNotArray');
    const events = [];
    for (let i = 0; i < parsed.length; i++) {
      const item = parsed[i];
      const at = { index: i + 1 };
      if (!item || typeof item !== 'object' || Array.isArray(item)) return fail('sseEditor.bulkItemNotObject', at);
      const type = item.type == null || item.type === '' ? 'normal' : item.type;
      if (!['normal', 'error', 'abort'].includes(type)) return fail('sseEditor.bulkBadType', { ...at, type: String(type) });
      const delayMs = item.delayMs == null ? 0 : item.delayMs;
      if (!delayValid(delayMs)) return fail('sseEditor.bulkBadDelay', { ...at, max: MAX_DELAY_MS });
      // data in object form ({"data": {"a":1}}) is still accepted and saved as JSON text
      const data = item.data == null ? '' : (typeof item.data === 'string' ? item.data : JSON.stringify(item.data));
      events.push({ event: item.event == null ? '' : String(item.event), data,
        id: item.id == null ? '' : String(item.id), delayMs, type });
    }
    return checked(events);
  };
  /** The same rules as the server: each event must have data, and the event name and ID cannot wrap. */
  const checked = events => {
    if (!events.length) return fail('sseEditor.bulkEmpty');
    for (let i = 0; i < events.length; i++) {
      if (!events[i].data) return fail('sseEditor.bulkNoData', { index: i + 1 });
      if (/[\r\n]/.test(events[i].event) || /[\r\n]/.test(events[i].id)) return fail('sseEditor.bulkMultiline', { index: i + 1 });
      if (events[i].id.includes('\0')) return fail('sseEditor.bulkBadId', { index: i + 1 });
    }
    return { events };
  };
  const fromStream = text => {
    const events = [];
    const parser = createSseParser(event => events.push({ event: event.event || '', data: event.data,
      id: event.id || '', delayMs: 0, type: 'normal' }));
    parser.push(text.endsWith('\n') ? text + '\n' : text + '\n\n');
    parser.finish();
    // Consecutive id are only retained when changing, do not copy the previous id to each event
    let lastId = '';
    events.forEach(event => { if (event.id === lastId) event.id = ''; else lastId = event.id; });
    return events.length ? checked(events) : fail('sseEditor.bulkUnreadable');
  };
  /** "Post the whole string": The beginning is [ when JSON event array, otherwise when text/event-stream original text (event: / data: / id:). */
  const parseBulk = text => {
    // Remove the BOM at the beginning (common when copying from files). JSON is the same as the original streaming text.
    const source = String(text ?? '').replace(/^\uFEFF/, '');
    if (!source.trim()) return fail('sseEditor.bulkEmpty');
    return source.trimStart().startsWith('[') ? fromJson(source) : fromStream(source);
  };

  /** The actual stream original text sent (including delay, error/abortion and loop description lines) is only generated when the user opens the "original content". */
  const rawStream = (events, loopEnabled, t) => {
    if (!events || !events.length) return '';
    const lines = [];
    let terminalEventReached = false;
    for (let i = 0; i < events.length; i++) {
      const evt = events[i];
      if (i > 0) lines.push('');
      if (evt.delayMs > 0) { lines.push(t('ssePreview.delay', { ms: evt.delayMs })); lines.push(''); }
      const type = evt.type || 'normal';
      if (type === 'abort') { lines.push(t('ssePreview.abort')); terminalEventReached = true; }
      if (type === 'error') lines.push(t('ssePreview.errorEvent'));
      if (type !== 'abort') {
        lines.push('event: ' + (type === 'error' ? 'error' : (evt.event || 'message')));
        (evt.data || '').split('\n').forEach(line => lines.push('data: ' + line));
        if (evt.id) lines.push('id: ' + evt.id);
        lines.push('');
      }
      if (type === 'error') terminalEventReached = true;
      if (terminalEventReached) {
        const remainingCount = events.length - i - 1;
        if (remainingCount > 0) lines.push(t('ssePreview.remainingSkipped', { count: remainingCount }));
        break;
      }
    }
    if (loopEnabled && !terminalEventReached) lines.push(t('ssePreview.loopPlay'));
    return lines.join('\n');
  };

  return { MAX_DELAY_MS, blank, timeline, summary, formatDuration, byteLength, formatBytes, previewLine,
    unitFor, toUnit, fromUnit, delayValid, matches, parseBulk, rawStream };
})();
