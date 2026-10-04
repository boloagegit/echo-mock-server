# SSE mock contract and playback

Send `Accept: text/event-stream` to `/mock/<path>`. Missing or wildcard Accept headers keep the ordinary HTTP pipeline and fallback behavior. HTTP SSE rules support GET,
POST, PUT, PATCH and DELETE, including request body conditions and response data
templates (`{{{request.body}}}` includes the raw request body). An omitted/null
method keeps the existing match-any-method behavior; HEAD and OPTIONS requests
matching an SSE rule return 405. Explicit HEAD, OPTIONS, TRACE, `*` or blank
methods cannot be saved as SSE rules. Ordinary HTTP rules retain their pipeline
behavior, including conditions, templates, response status, headers and delay.

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
values above 30,000 ms become 30,000 ms. The rule delay (randomly selected from
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

SSE writes use a separate fixed-size pool so slow clients cannot consume the
general HTTP delay workers. Completion, timeout, error, failed writes and
shutdown cancel/remove outstanding playback tasks. A disconnected peer may
only be detected by the container or next write; without a callback, a delayed
stream can retain its next task until that write (at most the remaining initial
rule delay plus the event delay). This is not a persistent detached task.

Native EventSource automatically reconnects after normal EOF and some errors.
Every request currently restarts the configured sequence at the first event.
`Last-Event-ID` is not used to resume; no event replay store or resume semantics
are provided. Use fetch or explicitly close EventSource after the expected
sequence when a single playback is required.

Regression tests use synthetic H2 data and random loopback HTTP ports. They
include scheduler execution/cancellation before future publication, individual
HTTP event arrival, the 20s + 20s sequence, method/body matching, settings,
invalid bodies, UTF-8 multiline data, loop cancellation and Last-Event-ID restart.
