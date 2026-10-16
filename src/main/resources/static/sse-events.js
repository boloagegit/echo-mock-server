/**
 * SseEvents - 規則編輯器 SSE 事件清單用到的計算（不含畫面，可單獨測試）：
 * 每個事件第幾毫秒送出、總長度、時間與大小的顯示、毫秒／秒換算、搜尋，
 * 以及「整串貼上」：JSON 事件陣列或直接從串流複製的 text/event-stream 原文。
 */
const SseEvents = (() => {
  const MAX_DELAY_MS = 600000;
  const blank = () => ({ event: '', data: '', id: '', delayMs: 0, type: 'normal' });
  const delayOf = event => (Number.isFinite(event?.delayMs) && event.delayMs > 0 ? event.delayMs : 0);
  const isTerminal = event => event?.type === 'error' || event?.type === 'abort';

  /**
   * 事件依序送出，延遲是送出前的等待時間。錯誤事件送出後關閉連線，中止事件只等待不送出；
   * 之後的事件都不會送出（state 'skipped'，沒有時間點）。
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
   * 規則詳細資料的一行摘要用：幾個事件、全長、會不會循環，以及在哪個事件結束連線。
   * 有錯誤／中止事件時串流在那裡結束，即使開了循環也不會重播（與伺服器的播放器相同）。
   */
  const summary = (events, loopEnabled) => {
    const list = Array.isArray(events) ? events : [];
    const line = timeline(list);
    const end = line.endIndex >= 0 ? list[line.endIndex].type : null;
    return { count: list.length, totalMs: line.totalMs, endAt: line.endIndex + 1, end,
      skipped: line.skipped, loops: !!loopEnabled && !end };
  };

  const trimNumber = value => String(Math.round(value * 1000) / 1000);
  /** 300 → 300 ms；1500 → 1.5 秒；90000 → 1 分 30 秒；600000 → 10 分鐘。 */
  const formatDuration = (ms, t) => {
    const value = Math.max(0, Math.round(Number(ms) || 0));
    if (value < 1000) return t('sseEditor.durationMs', { ms: value });
    if (value < 60000) return t('sseEditor.durationSec', { s: trimNumber(value / 1000) });
    const minutes = Math.floor(value / 60000);
    const rest = value - minutes * 60000;
    return rest ? t('sseEditor.durationMinSec', { m: minutes, s: trimNumber(rest / 1000) })
      : t('sseEditor.durationMin', { m: minutes });
  };

  /** UTF-8 位元組數（不配置暫存陣列）。 */
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

  /** 收起時顯示的一行：空白合併，只看開頭（幾 KB 的內容也只處理前面一小段）。 */
  const previewLine = (text, max = 160) => {
    const head = String(text ?? '').slice(0, max * 4).replace(/\s+/g, ' ').trim();
    return head.length > max ? head.slice(0, max) + '…' : head;
  };

  /** 打開時的單位：整秒（至少 1 秒）用秒，其他用毫秒。 */
  const unitFor = ms => (Number.isInteger(ms) && ms >= 1000 && ms % 1000 === 0 ? 's' : 'ms');
  const toUnit = (ms, unit) => (unit === 's' ? Math.round(ms) / 1000 : ms);
  /** 輸入框的值換成毫秒；看不懂時回 null。秒最多到毫秒（1.2345 秒 → 1235 毫秒）。 */
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
      // 物件形式的 data（{"data": {"a":1}}）照樣接受，存成 JSON 文字
      const data = item.data == null ? '' : (typeof item.data === 'string' ? item.data : JSON.stringify(item.data));
      events.push({ event: item.event == null ? '' : String(item.event), data,
        id: item.id == null ? '' : String(item.id), delayMs, type });
    }
    return checked(events);
  };
  /** 與伺服器相同的規則：每個事件都要有資料，事件名稱與 ID 不可換行。 */
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
    // 連續的 id 只在改變時保留，不把上一個 id 複製到每一個事件
    let lastId = '';
    events.forEach(event => { if (event.id === lastId) event.id = ''; else lastId = event.id; });
    return events.length ? checked(events) : fail('sseEditor.bulkUnreadable');
  };
  /** 「整串貼上」：開頭是 [ 當 JSON 事件陣列，否則當 text/event-stream 原文（event: / data: / id:）。 */
  const parseBulk = text => {
    // 開頭的 BOM（從檔案複製時常見）先拿掉，JSON 與串流原文都一樣
    const source = String(text ?? '').replace(/^\uFEFF/, '');
    if (!source.trim()) return fail('sseEditor.bulkEmpty');
    return source.trimStart().startsWith('[') ? fromJson(source) : fromStream(source);
  };

  /** 實際送出的串流原文（含延遲、錯誤／中止與循環的說明行），只在使用者打開「原始內容」時才產生。 */
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
