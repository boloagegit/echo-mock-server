/**
 * utils.js - Echo Mock Server 共用工具函式
 *
 * 提供全域工具函式供各元件與 app.js 使用。
 * 載入順序：vue.global.prod.js → codemirror → utils.js → components → app.js
 */
const { createApp, ref, computed, watch, onMounted, onUnmounted } = Vue;
const api = async (url, opt = {}) => { const r = await fetch(url, { ...opt, headers: { 'Accept': 'application/json', 'Content-Type': 'application/json', ...(opt.headers || {}) } }); return r };
const fmtTime = (t, short = true) => { if (!t) return ''; return short ? t.substring(5, 16).replace('T', ' ') : t.replace('T', ' ').substring(0, 19) };
const shortId = id => id && id.length > 8 ? id.substring(0, 8) : id;
const daysLeft = (createdAt, extendedAt, retentionDays) => { if (!retentionDays) return null; const baseDate = extendedAt || createdAt; if (!baseDate) return null; const d = Math.ceil((new Date(baseDate).getTime() + retentionDays * 86400000 - Date.now()) / 86400000); return d > 0 ? d : 0; };
const fmtSize = bytes => { if (!bytes) return '0 B'; if (bytes < 1024) return bytes + ' B'; if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'; return (bytes / 1024 / 1024).toFixed(2) + ' MB'; };
const reasonText = r => { const map = {match: _t('reasonText.match'), disabled: _t('reasonText.disabled'), condition_not_match: _t('reasonText.condition_not_match'), fallback: _t('reasonText.fallback'), skipped: _t('reasonText.skipped'), 'near-miss': _t('reasonText.nearMiss'), shadowed: _t('reasonText.shadowed'), mismatch: _t('reasonText.mismatch'), scenario_state_mismatch: _t('reasonText.scenarioStateMismatch')}; return map[r] || r; };
const condCount = r => { const b = (r.bodyCondition||'').split(';').filter(x=>x); const q = (r.queryCondition||'').split(';').filter(x=>x); const h = (r.headerCondition||'').split(';').filter(x=>x); return b.length + q.length + h.length; };
const condTooltip = r => { const parts = []; (r.bodyCondition||'').split(';').filter(x=>x).forEach(c => parts.push(c)); (r.queryCondition||'').split(';').filter(x=>x).forEach(c => parts.push('?' + c)); (r.headerCondition||'').split(';').filter(x=>x).forEach(c => parts.push('@' + c)); return parts.join('\n'); };
const condTags = r => { const tags = []; (r.bodyCondition||'').split(';').filter(x=>x).forEach(c => { const t = c.trim(); const label = t.startsWith('$.') ? 'JsonPath' : /^\//.test(t) ? 'XPath' : 'Body'; tags.push({t:'body',v:c,label}); }); (r.queryCondition||'').split(';').filter(x=>x).forEach(c => tags.push({t:'query',v:c,label:'Query'})); (r.headerCondition||'').split(';').filter(x=>x).forEach(c => tags.push({t:'header',v:c,label:'Header'})); return tags; };
const condTagsGrouped = r => { const groups = []; const body = (r.bodyCondition||'').split(';').filter(x=>x).map(c => { const t = c.trim(); const label = t.startsWith('$.') ? 'JsonPath' : /^\//.test(t) ? 'XPath' : 'Body'; return {t:'body',v:c,label}; }); const query = (r.queryCondition||'').split(';').filter(x=>x).map(c => ({t:'query',v:c,label:'Query'})); const header = (r.headerCondition||'').split(';').filter(x=>x).map(c => ({t:'header',v:c,label:'Header'})); if (body.length) { groups.push({type:'body', items:body}); } if (query.length) { groups.push({type:'query', items:query}); } if (header.length) { groups.push({type:'header', items:header}); } return groups; };
const parseTags = t => { try { return t ? JSON.parse(t) : {} } catch { return {} } };
const parseHeaders = t => { try { return t ? JSON.parse(t) : {} } catch { return {} } };
const fmtCond = s => s ? s.split(';').filter(x=>x).join('\n') : '';
const debounce = (fn, delay = 300) => {
    let timer = null;
    const debounced = (...args) => { if (timer) { clearTimeout(timer); } timer = setTimeout(() => { fn(...args); timer = null; }, delay); };
    debounced.cancel = () => { if (timer) { clearTimeout(timer); timer = null; } };
    return debounced;
};
const deserializeSseEvents = (jsonStr) => {
    const defaultEvent = () => ({ event: '', data: '', id: '', delayMs: 0, type: 'normal' });
    if (jsonStr == null || typeof jsonStr !== 'string' || !jsonStr.trim()) { return [defaultEvent()]; }
    try {
        const arr = JSON.parse(jsonStr);
        if (!Array.isArray(arr)) { return [defaultEvent()]; }
        return arr.map(e => ({
            event: (e.event != null ? String(e.event) : ''),
            data: (e.data != null ? String(e.data) : ''),
            id: (e.id != null ? String(e.id) : ''),
            delayMs: (typeof e.delayMs === 'number' ? e.delayMs : 0),
            type: (e.type === 'error' || e.type === 'abort') ? e.type : 'normal'
        }));
    } catch (ex) { return [defaultEvent()]; }
};
const serializeSseEvents = (events) => {
    const arr = events.map(e => {
        const obj = {};
        obj.event = (e.event && e.event.trim()) ? e.event : null;
        obj.data = e.data != null ? String(e.data) : '';
        obj.id = (e.id && e.id.trim()) ? e.id : null;
        obj.delayMs = typeof e.delayMs === 'number' ? e.delayMs : 0;
        if (e.type && e.type !== 'normal') { obj.type = e.type; }
        return obj;
    });
    return JSON.stringify(arr, null, 2);
};
let _showToast = null;
let _t = null;
const apiCall = async (url, opt = {}, config = {}) => {
    const { silent = false, errorMsg = '' } = config;
    try {
        const r = await fetch(url, { ...opt, headers: { 'Accept': 'application/json', 'Content-Type': 'application/json', ...(opt.headers || {}) } });
        if (r.status === 401 || r.status === 403) {
            if (!silent) { _showToast(_t('toast.loginRequired'), 'error'); }
            return r;
        }
        if (r.status === 409) {
            if (!silent) { _showToast(_t('toast.dataConflict'), 'error'); }
            return r;
        }
        if (!r.ok && !silent) {
            try { const body = await r.clone().json(); _showToast(body.error || errorMsg || _t('toast.operationFailed'), 'error'); }
            catch { _showToast(errorMsg || _t('toast.operationFailed'), 'error'); }
        }
        return r;
    } catch (e) {
        if (!silent) { _showToast(_t('toast.networkError'), 'error'); }
        console.warn('API call failed:', url, e);
        return null;
    }
};
