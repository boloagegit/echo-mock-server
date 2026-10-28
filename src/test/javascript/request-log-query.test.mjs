// Run with: node --test src/test/javascript/request-log-query.test.mjs
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../main/resources/static/composables/useStats.js', import.meta.url), 'utf8');
const tick = async () => { for (let i = 0; i < 10; i++) { await Promise.resolve(); } };
const deferred = () => {
    let resolve;
    const promise = new Promise(done => { resolve = done; });
    return {promise, resolve};
};
const page = id => ({results: [{log: {id}, rule: null}], totalElements: 1, totalPages: 1});
const response = data => ({ok: true, status: 200, json: async () => data});

function setup() {
    let now = 100000, timerId = 0;
    let active = true;
    const timers = new Map(), requests = [];
    const loading = {value: {logs: false, logsError: ''}};
    const context = vm.createContext({
        Vue: {ref: value => ({value}), computed: get => ({get value() { return get(); }}), watch: () => {}},
        URLSearchParams, AbortController,
        localStorage: {getItem: () => null, setItem: () => {}},
        Date: class extends Date { static now() { return now; } },
        setTimeout: (run, delay) => { const id = ++timerId; timers.set(id, {run, at: now + delay}); return id; },
        clearTimeout: id => timers.delete(id),
        apiCall: (url, options = {}) => {
            if (url.endsWith('/summary')) { return Promise.resolve(response({})); }
            const result = deferred();
            requests.push({url, signal: options.signal, resolve: result.resolve});
            options.signal?.addEventListener('abort', () => result.resolve(null));
            return result.promise;
        },
        deps: {loading, isActive: () => active, t: key => key, showToast: () => { throw new Error('Unexpected toast'); },
            httpLabel: {value: 'HTTP'}, jmsLabel: {value: 'JMS'}}
    });
    vm.runInContext(source + '\nglobalThis.stats = useStats(deps);', context);
    return {stats: context.stats, requests, loading, timers, setActive: value => { active = value; },
        advance: async milliseconds => {
            now += milliseconds;
            for (const [id, timer] of [...timers]) {
                if (timer.at <= now) { timers.delete(id); timer.run(); }
            }
            await tick();
        }};
}

test('repeated refreshes share the list request and clear the loading state', async () => {
    const {stats, requests, loading} = setup();
    const first = stats.loadLogs(true), second = stats.loadLogs(true);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].signal.aborted, false);
    requests[0].resolve(response(page(11)));
    await Promise.all([first, second]);
    assert.equal(stats.logs.value[0].log.id, 11);
    assert.equal(loading.value.logs, false);
});

test('a late response body cannot replace results for a newer filter', async () => {
    const {stats, requests, loading} = setup();
    const oldBody = deferred();
    const first = stats.loadLogs(true);
    requests[0].resolve({ok: true, status: 200, json: () => oldBody.promise});
    await tick();
    stats.logFilter.value.endpoint = '/latest';
    const second = stats.loadLogs(true);
    assert.equal(requests[0].signal.aborted, true);
    assert.equal(new URL(requests[1].url, 'http://local').searchParams.get('endpoint'), '/latest');
    requests[1].resolve(response(page(22)));
    await second;
    oldBody.resolve(page(11));
    await first;
    assert.equal(stats.logs.value[0].log.id, 22);
    assert.equal(loading.value.logs, false);
});

test('capacity responses honor Retry-After and retry the latest filter once', async () => {
    const {stats, requests, loading, timers, advance} = setup();
    const initial = stats.loadLogs(true);
    requests[0].resolve({ok: false, status: 429, headers: {get: () => '1'}});
    await initial;
    assert.equal(loading.value.logsError, 'stats.queryBusy');
    assert.equal(loading.value.logsQueryBusy, true);
    assert.equal(loading.value.logs, false);
    stats.logFilter.value.endpoint = '/changed-while-busy';
    await stats.loadLogs(true);
    await stats.loadLogs(true);
    assert.equal(requests.length, 1);
    assert.equal(timers.size, 1);
    await advance(999);
    assert.equal(requests.length, 1);
    await advance(1);
    assert.equal(requests.length, 2);
    assert.equal(new URL(requests[1].url, 'http://local').searchParams.get('endpoint'), '/changed-while-busy');
    requests[1].resolve(response(page(33)));
    await tick();
    assert.equal(stats.logs.value[0].log.id, 33);
    assert.equal(loading.value.logsError, '');
    assert.equal(loading.value.logsQueryBusy, false);
    assert.equal(timers.size, 0);
});

test('disposing the page cancels a queued retry and prevents further requests', async () => {
    const {stats, requests, timers, advance, loading} = setup();
    const initial = stats.loadLogs(true);
    requests[0].resolve({ok: false, status: 429, headers: {get: () => '1'}});
    await initial;
    stats.cleanupStats();
    assert.equal(timers.size, 0);
    await advance(2000);
    await stats.loadLogs(true);
    assert.equal(requests.length, 1);
    assert.equal(loading.value.logs, false);
});

test('a failed automatic retry reports failure without keeping a busy state or retry loop', async () => {
    const {stats, requests, timers, advance, loading} = setup();
    const initial = stats.loadLogs(true);
    requests[0].resolve({ok: false, status: 429, headers: {get: () => '1'}});
    await initial;
    await advance(1000);
    requests[1].resolve({ok: false, status: 500});
    await tick();
    assert.equal(loading.value.logsQueryBusy, false);
    assert.equal(loading.value.logsError, 'stats.loadFailed');
    assert.equal(timers.size, 0);
    assert.equal(loading.value.logs, false);
});

test('leaving request logs stops retries; re-entering resumes despite a recently cached page', async () => {
    const {stats, requests, advance, setActive} = setup();
    const loaded = stats.loadLogs(true);
    requests[0].resolve(response(page(11)));
    await loaded;
    const busy = stats.loadLogs(true);
    requests[1].resolve({ok: false, status: 429, headers: {get: () => '1'}});
    await busy;
    setActive(false);
    await advance(1000);
    assert.equal(requests.length, 2);
    setActive(true);
    const resumed = stats.loadLogs(false);
    assert.equal(requests.length, 3);
    requests[2].resolve(response(page(22)));
    await resumed;
    assert.equal(stats.logs.value[0].log.id, 22);
});

test('deletion invalidation supersedes old responses and discards cached details for reused IDs', async () => {
    const {stats, requests} = setup();
    const loaded = stats.loadLogs(true);
    requests[0].resolve(response(page(11)));
    await loaded;
    const oldItem = stats.logs.value[0];
    const oldDetail = stats.loadLogDetail(oldItem);
    requests[1].resolve(response({requestBody: 'old body'}));
    await oldDetail;
    const oldBody = deferred();
    const pending = stats.loadLogs(true);
    requests[2].resolve({ok: true, status: 200, json: () => oldBody.promise});
    await tick();
    stats.invalidateLogs();
    const refreshed = stats.loadLogs(true);
    assert.equal(requests.length, 4);
    requests[3].resolve(response(page(11)));
    await refreshed;
    assert.equal(stats.logs.value[0]._detail, undefined);
    oldBody.resolve(page(99));
    await pending;
    assert.equal(stats.logs.value[0].log.id, 11);
    const detail = stats.loadLogDetail(stats.logs.value[0]);
    assert.equal(requests.length, 5);
    requests[4].resolve(response({requestBody: 'new body'}));
    await detail;
    assert.equal(stats.logs.value[0]._detail.requestBody, 'new body');
});

test('details already downloading at deletion cannot repopulate the new detail cache', async () => {
    const {stats, requests} = setup();
    const loaded = stats.loadLogs(true);
    requests[0].resolve(response(page(11)));
    await loaded;
    const oldBody = deferred();
    const oldDetail = stats.loadLogDetail(stats.logs.value[0]);
    requests[1].resolve({ok: true, status: 200, json: () => oldBody.promise});
    await tick();
    stats.invalidateLogs();
    const refreshed = stats.loadLogs(true);
    requests[2].resolve(response(page(11)));
    await refreshed;
    oldBody.resolve({requestBody: 'old body'});
    await oldDetail;
    const detail = stats.loadLogDetail(stats.logs.value[0]);
    assert.equal(requests.length, 4);
    requests[3].resolve(response({requestBody: 'new body'}));
    await detail;
    assert.equal(stats.logs.value[0]._detail.requestBody, 'new body');
});
