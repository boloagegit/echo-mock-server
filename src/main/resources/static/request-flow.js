/**
 * request-flow.js - 由一筆請求紀錄整理出「處理流程」：收到請求 → 比對規則 → 模擬回應／轉發／找不到規則 → 回給呼叫端。
 *
 * 純函式、不依賴 Vue，可在 Node 測試中直接載入。輸入是列表摘要（log）、詳細資料（detail，可為 null）與匹配鏈；
 * 舊紀錄沒有 candidateCount、delayMs 等欄位時，對應的資訊就不顯示。
 * 每個步驟的 tone：ok（正常）、warn（特殊處理或沒命中）、bad（失敗）、off（沒有經過）。
 */
const RequestFlow = (() => {
    const SCENARIO_DETAIL = /^scenario: (.*) \(required: (.*), current: (.*)\)$/;

    const parseJson = text => {
        if (!text) return [];
        try {
            const value = JSON.parse(text);
            return Array.isArray(value) ? value : [];
        } catch {
            return [];
        }
    };

    /** 「scenario: checkout (required: Paid, current: Started)」→ {scenario, required, current}；格式不同時只有 detail。 */
    const scenarioSkip = entry => {
        const match = SCENARIO_DETAIL.exec(entry.detail || '');
        return match
            ? { ruleId: entry.ruleId, description: entry.description || '', scenario: match[1], required: match[2], current: match[3] }
            : { ruleId: entry.ruleId, description: entry.description || '', detail: entry.detail || '' };
    };

    const replyTone = (status, faultType) => {
        if (faultType) return 'warn';
        if (status == null) return 'ok';
        if (status >= 500) return 'bad';
        if (status >= 400) return 'warn';
        return 'ok';
    };

    /**
     * @param log    列表摘要（matched、forwarded、proxyStatus、faultType、時間等）
     * @param detail 詳細資料（candidateCount、delayMs、sseEventCount、scenarioSkipped、matchedByFallback）；未載入時為 null
     * @param chain  解析後的匹配鏈（陣列）；沒有時為 null
     */
    const build = (log, detail, chain) => {
        const d = detail || {};
        const entries = Array.isArray(chain) ? chain : [];
        const jms = log.protocol === 'JMS';
        const fault = log.faultType && log.faultType !== 'NONE' ? log.faultType : null;

        const matchedEntry = log.matched ? entries.find(entry => entry.ruleId === log.ruleId && entry.reason === 'match') : null;
        const nearMiss = !log.matched ? entries.find(entry => entry.reason === 'near-miss') : null;
        const match = {
            matched: !!log.matched,
            ruleId: log.ruleId || null,
            description: matchedEntry?.description || '',
            byFallback: d.matchedByFallback === true,
            candidateCount: d.candidateCount ?? null,
            matchTimeMs: log.matchTimeMs ?? null,
            nearMiss: nearMiss ? { ruleId: nearMiss.ruleId, score: nearMiss.score || '' } : null,
            skipped: parseJson(d.scenarioSkipped).map(scenarioSkip),
            hasTrace: entries.length > 0,
            tone: log.matched ? 'ok' : 'warn',
        };

        let route;
        if (log.forwarded) route = 'forward';
        else if (log.matched) route = 'mock';
        else route = 'notFound';

        const mock = {
            tone: route !== 'mock' ? 'off' : (fault ? 'warn' : 'ok'),
            sse: d.sseEventCount != null,
            sseEventCount: d.sseEventCount ?? null,
            fault,
            delayMs: route === 'mock' && d.delayMs > 0 ? d.delayMs : null,
            scenario: log.scenarioToState
                ? { name: log.scenarioName || '', from: log.scenarioFromState || 'Started', to: log.scenarioToState }
                : null,
        };

        let forwardOutcome = null;
        if (route === 'forward') {
            if (log.proxyError) forwardOutcome = 'failed';
            else if (log.proxyStatus != null && log.proxyStatus >= 400) forwardOutcome = 'errorStatus';
            else forwardOutcome = 'ok';
        }
        const forward = {
            tone: route !== 'forward' ? 'off' : (forwardOutcome === 'failed' ? 'bad' : forwardOutcome === 'errorStatus' ? 'warn' : 'ok'),
            byRule: route === 'forward' && !!log.matched,
            target: log.forwardTarget || (route === 'forward' ? log.targetHost : null) || null,
            outcome: forwardOutcome,
            proxyStatus: log.proxyStatus ?? null,
            proxyError: log.proxyError || null,
            delayMs: route === 'forward' && d.delayMs > 0 ? d.delayMs : null,
        };

        const status = jms ? null : (log.responseStatus ?? null);
        const reply = {
            jms,
            status,
            connectionReset: fault === 'CONNECTION_RESET',
            emptyResponse: fault === 'EMPTY_RESPONSE',
            tone: route === 'forward' && forwardOutcome === 'failed' ? 'bad'
                : route === 'notFound' ? 'warn' : replyTone(status, fault),
        };

        const total = Math.max(0, log.responseTimeMs || 0);
        const matchMs = Math.min(total, Math.max(0, log.matchTimeMs || 0));
        const delayMs = log.matched && d.delayMs > 0 ? d.delayMs : 0;
        const timing = {
            matchMs,
            otherMs: total - matchMs,
            otherKind: route === 'forward' ? 'forward' : 'response',
            otherTone: route === 'forward' && forwardOutcome === 'failed' ? 'bad' : 'neutral',
            delayMs,
            totalMs: total + delayMs,
            sse: mock.sse && route === 'mock',
        };

        return {
            received: { protocol: log.protocol, method: log.method, endpoint: log.endpoint, clientIp: log.clientIp, targetHost: log.targetHost, jms },
            match, route, mock, forward, reply, timing,
            // 詳細資料尚未載入時，候選數與延遲等資訊之後才會出現
            complete: !!detail,
        };
    };

    /** 時間條各段的寬度（百分比）；有值的段至少 2%，總和為 100。 */
    const timingSegments = timing => {
        const parts = [
            { key: 'match', ms: timing.matchMs },
            { key: timing.otherKind, ms: timing.otherMs, tone: timing.otherTone },
            { key: 'delay', ms: timing.delayMs },
        ].filter(part => part.ms > 0);
        const total = parts.reduce((sum, part) => sum + part.ms, 0);
        if (total === 0) return [];
        const minimum = 2;
        const raw = parts.map(part => Math.max(minimum, (part.ms / total) * 100));
        const scale = 100 / raw.reduce((sum, width) => sum + width, 0);
        return parts.map((part, i) => ({ ...part, width: Math.round(raw[i] * scale * 10) / 10 }));
    };

    return { build, timingSegments };
})();
