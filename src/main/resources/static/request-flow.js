/**
 * request-flow.js - The "processing flow" is organized from a request record: Request received → Rule comparison → Simulated response/Forwarding/Rule not found → Return to the calling end.
 *
 * Pure function, does not depend on Vue, can be loaded directly in Node test. The input is list summary (log), detailed information (detail, can be null) and matching chain;
 * When the old record does not have candidateCount, delayMs and other fields, the corresponding information will not be displayed.
 * tone for each step: ok (normal), warn (special treatment or miss), bad (failed), off (not passed).
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

    /** 「scenario: checkout (required: Paid, current: Started)」 → {scenario, required, current}; only when the formats are different detail. */
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
     * @param log    List summary (matched, forwarded, proxyStatus, faultType, time, etc.)
     * @param detail Detailed information (candidateCount, delayMs, sseEventCount, scenarioSkipped, matchedByFallback); null when not loaded
     * @param chain  Parsed matching chain (array); if not available, null
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
            // When the detailed information has not been loaded, information such as the number of candidates and delay will appear later.
            complete: !!detail,
        };
    };

    /** Width (percentage) of each segment of the time bar; at least 2% of segments have values, summing to 100. */
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
