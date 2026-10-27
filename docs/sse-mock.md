# SSE mock contract and playback

Send `Accept: text/event-stream` to `/mock/<path>`. Missing or wildcard Accept headers keep the ordinary HTTP pipeline and fallback behavior. HTTP SSE rules support GET,
POST, PUT, PATCH and DELETE, including request body conditions and response data
templates (`{{{request.body}}}` includes the raw request body). An omitted/null
method keeps the existing match-any-method behavior; HEAD and OPTIONS requests
matching an SSE rule return 405. Explicit HEAD, OPTIONS, TRACE, `*` or blank
methods cannot be saved as SSE rules. Ordinary HTTP rules retain their pipeline
behavior, including conditions, templates, response status, headers and delay.
Unmatched requests with explicit SSE Accept retain the original 404 behavior
for GET and HEAD, even when a forwarding target exists. Other methods use the
ordinary HTTP pipeline: the default connection takes precedence over
`X-Original-Host`, and no forwarding target returns 404. Forwarded responses keep
the existing buffered HTTP behavior.

Example request against a synthetic POST rule:

```sh
curl --no-buffer http://127.0.0.1:8080/mock/ai \
  -H 'Accept: text/event-stream' -H 'Content-Type: application/json' \
  --data '{"prompt":"synthetic example"}'
```

Response bodies are nonempty JSON arrays of objects with exactly these fields:
`data` (required, nonempty string), optional `event`/`id` (string or null),
`delayMs` (integer or null), and `type` (`normal`, `error`, `abort`, or null).
Whitespace and multiline data are supported. CRLF/CR in data normalize to LF
on the wire; UTF-8 is used. Event names and IDs must not contain CR/LF, and IDs
must not contain NUL. Unsupported fields, including `retry` and `comment`, are
rejected. Save-time validation and runtime parsing use the same contract;
legacy invalid stored bodies produce a controlled HTTP 500 rather than partial
playback. Existing response selections, explicitly typed SSE responses, and updates to responses referenced by SSE rules are validated too.

Each event delay occurs before that event, accumulating across the sequence.
The existing normalization remains: negative event delays become zero and
values above 600,000 ms (10 minutes) become 600,000 ms. The rule delay (randomly selected from
`delayMs` through `maxDelayMs`, if supplied) occurs once before the first event.
Rule delays must be nonnegative and the maximum must be at least the minimum.
Finite stream timeout is cumulative delay through the first terminal event
plus a 30-second margin. Configurations needing more than 24 hours including
that margin are rejected; loops retain the 24-hour timeout. A normal finite
sequence completes; `error` sends an event named `error` then ends with an
error; `abort` ends with an error without sending its event. Both stop loops.

HTTP status 200 streams with `text/event-stream;charset=UTF-8`. Custom headers
are applied; `Cache-Control` defaults to `no-cache` if omitted. A conflicting
content type/charset or custom Content-Length, Transfer-Encoding or
Content-Encoding is rejected. Non-200 settings use the ordinary HTTP pipeline,
including its request timeout, delay, status, headers and saved body; they do
not open an SSE stream. Status 204 supplies an empty HTTP response, allowing
native EventSource clients to stop reconnecting. Non-200 text/event-stream
content types and informational statuses are unsupported and rejected.

The UI test uses fetch so POST bodies work. Its incremental parser accepts LF,
CRLF and CR across arbitrary chunks, preserves empty multiline data, ignores
comments/unsupported protocol fields, and discards an incomplete event at EOF.
It decodes UTF-8 across chunk boundaries and exposes non-stream HTTP responses
as HTTP results. Stopping or replacing a test aborts its fetch.

SSE timing and SSE writes are separated. Eight scheduler threads only decide
when the next event is due; the write itself runs on an `sse-writer` thread
created on demand (at most 256, idle threads end after 60 seconds), and each
stream has at most one write in progress. A client that stays connected but
stops reading therefore holds only its own writer: other streams and the
general HTTP delay workers keep running. A write still unfinished after
`echo.sse.stalled-write-timeout-ms` (default 120,000, minimum 1,000) closes that
connection, which ends the blocked write and frees the writer. Only a write in
progress counts, so long pauses between events are never treated as stalled.
If all 256 writers are waiting at once, other streams retry every 200 ms and
continue as soon as the stalled connections are closed; they are delayed, not
stopped. In a test with a stream sending one event every 100 ms, 12 clients
that never read previously stopped it completely (no event for 32 s, and the
stalled connections were never closed); it now keeps its 100 ms rhythm, and
260 such clients pause it for at most the timeout (measured with a 30-second
timeout; the default is now 2 minutes).

Known limits of the stalled-write timeout: it measures how long one write has
been in progress, not whether bytes are still moving, so a client that keeps
reading but needs longer than the timeout for one event (for example 64 KB
events at 1 KB/s needs about 64 seconds) is also closed when that exceeds the
setting; the default of 2 minutes covers events of several KB even on very
slow links. Raise the setting for clients that need longer. After a
stalled connection is closed, its request objects (about 50 KB each) stay in
memory until the stream's own timeout: the sequence duration plus 30 seconds,
or 24 hours for looping rules. Before this change the same objects and a
playback thread were held for as long as the client stayed connected.

Completion, timeout, error, failed writes and shutdown cancel/remove
outstanding playback tasks. A disconnected peer may only be detected by the
container or next write; without a callback, a delayed stream can retain its
next task until that write (at most the remaining initial rule delay plus the
event delay). This is not a persistent detached task.

Native EventSource automatically reconnects after normal EOF and some errors.
Every request currently restarts the configured sequence at the first event.
`Last-Event-ID` is not used to resume; no event replay store or resume semantics
are provided. Use fetch or explicitly close EventSource after the expected
sequence when a single playback is required.

Regression tests use synthetic SQLite data and random loopback HTTP ports. They
include scheduler execution/cancellation before future publication, individual
HTTP event arrival, the 20s + 20s sequence, method/body matching, settings,
invalid bodies, UTF-8 multiline data, loop cancellation and Last-Event-ID restart.
