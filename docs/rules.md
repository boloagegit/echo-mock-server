# Rules and Matching

[← Documentation index](README.md)

How a request finds its rule, the condition syntax, stateful scenarios and response templates.

## Rule Matching Priority

When multiple rules match the same request, the system selects based on the following order:

### Sorting Priority (higher number = higher priority)

1. **matchKey specificity** – Exact paths take priority over wildcard `*`
2. **priority field** – Higher number = higher priority (default 0)
3. **targetHost specificity** (HTTP) – Specified host takes priority over empty value
4. **Creation time** – Newer rules take priority

### Matching Logic

1. **Has conditions and matches** → Return immediately
2. **No conditions** → Record as fallback, take the first one after sorting
3. Finally return the fallback rule or null

### Example

| Rule | targetHost | matchKey | priority | Condition | Order |
|------|------------|----------|----------|-----------|-------|
| A | api.com | /users | 10 | type=vip | 1 |
| B | api.com | /users | 10 | (none) | 2 |
| C | (empty) | /users | 10 | (none) | 3 |
| D | api.com | * | 10 | (none) | 4 |
| E | api.com | /users | 1 | (none) | 5 |

- Request body contains `type=vip` → Matches A
- Request body does not contain `type=vip` → Matches B

Rules with disabled tags do not participate in matching.

## Tag-Based Organization

Tags are used to organize rules by version, feature, or environment:

- **JSON format** – e.g., `{"env":"prod","team":"payment"}`
- **Batch control** – Enable/disable rules by tag (`PUT /api/admin/rules/tag/{key}/{value}/enable|disable`)
- **Quick filter** – Filter rules by tag on the rules page, with group view toggle

## Mock Rule Configuration

### HTTP Rule

```json
{
  "protocol": "HTTP",
  "targetHost": "api.example.com",
  "matchKey": "/users",
  "method": "GET",
  "bodyCondition": "custId=K123",
  "queryCondition": "status=active",
  "responseBody": "{\"name\": \"VIP User\"}",
  "status": 200,
  "delayMs": 100
}
```

### JMS Rule

```json
{
  "protocol": "JMS",
  "matchKey": "ORDER.QUEUE",
  "bodyCondition": "//OrderType=VIP",
  "responseBody": "<response><status>OK</status></response>",
  "delayMs": 50
}
```

For JMS rules, `matchKey` is the queue name. `status` is the HTTP status code of the reply.

## Move rules between environments (JSON)

Enable `ECHO_BULK_IMPORT_EXPORT_ENABLED=true` on both servers and sign in as an ADMIN.
On the source **Rules** page, open the data menu and export JSON. On the destination,
choose **Import → JSON**, select the file, review the preview, then confirm.
The single-rule JSON export is also accepted. Enable each protocol whose stored rules you want to export on the source.

- Exports include complete response bodies, structured conditions and SSE settings. They do not include connection credentials, server configuration, request history or runtime scenario/tag state. Treat the file as application data; response bodies and headers can contain secrets.
- Every import creates **new rule IDs and new local responses**. Existing rules and responses are never overwritten by source IDs. Rules sharing a response in one file share a newly copied response in the destination. Importing the same file again creates another set of copies.
- Creation/update times and authors are set locally. Old creation and extension times are discarded; retention starts at import time using the **destination's** rule/response retention settings. The protected flag is preserved, so protected rules remain exempt from automatic rule cleanup.
- Enabled/disabled flags are preserved. Enabled imports participate in matching after the transaction commits, subject to the destination's scenario/tag settings. Overlapping paths, priorities and the newer creation time can change which rule matches; importing does not guarantee identical routing or matching in a different environment.
- Forwarding rules require an explicit destination connection choice in the preview. Source numeric IDs and names are never used to select a local connection automatically. Existing profiles, defaults and credentials remain unchanged. Configure destination profiles beforehand; choosing its default connection follows future changes to that default. HTTP `X-Original-Host` is an explicit dynamic-target option.
- For JMS defaults, an enabled YAML target takes precedence over a database default, matching runtime forwarding. Select the database profile explicitly to use it instead.
- Preview performs no writes or connection tests. Confirmation revalidates destination choices; if a selected profile/default has changed, preview again. Invalid/disabled protocols, incomplete response data or a save failure reject/roll back the entire JSON batch.
- Review environment-dependent fields such as HTTP `targetHost`/paths and JMS `matchKey` (queue name); these are copied literally. Different destination queue names require rule changes.
- Use separate databases/schemas and separate JMS source queues/brokers for independent environments. Instances sharing a database share imported data; consumers sharing a broker queue compete for its messages. Export/import uses database and application resources; it is not a zero-load operation.

Old metadata-only exports that contain a `responseId` without the response body are rejected;
re-export them from the updated source. Do not add `exportVersion` by hand to bypass this check.
If the confirmation reply is lost, check the destination rule list before retrying: the transaction
may already have committed, and another import creates new copies.

JSON is the round-trip format for this workflow. Excel and OpenAPI imports create rules from their
own formats; they are not complete backups of existing rules or environment configuration.

## Condition Matching Syntax

### HTTP Body Conditions (JSON)

| Syntax | Description | Example |
|--------|-------------|---------|
| `field=value` | Simple field | `userId=123` |
| `a.b.c=value` | Nested field | `order.customer.id=VIP001` |
| `arr[0].field=value` | Array index | `items[0].sku=A001` |

### HTTP Query Conditions

| Syntax | Description | Matches |
|--------|-------------|---------|
| `status=active` | Query parameter | `?status=active` |
| `id=123` | Query parameter | `?id=123&other=x` |

### JMS Body Conditions (XML)

| Syntax | Description |
|--------|-------------|
| `element=value` | Simple element (auto-converts to `//element`) |
| `//CustomerId=K123` | XPath anywhere |
| `/root/order/id=123` | XPath absolute path |
| `order/id=123` | Relative path: any `id` whose parent is `order` |
| `//item/@sku=A1` | Attribute value |

Simple paths like these match element and attribute **local names**, so `CustomerId` also matches `<ns:CustomerId>`; `==` is treated as `=`. The same rules apply to HTTP and JMS and to `xpath` selectors in advanced conditions, and XML bodies whose conditions only use simple paths are scanned once without building a DOM. Paths with predicates, functions, wildcards or prefixed names (for example `//item[1]`, `//ns:item`) use XPath.

### Multiple Conditions

Multiple conditions separated by `;` must all match (AND logic):
- Body: `custId=K123;type=vip`
- Query: `status=active;page=1`
- Header: `X-Api-Key=abc123;Content-Type*=json`

### HTTP Header Conditions

| Syntax | Description | Example |
|--------|-------------|---------|
| `Header=value` | Exact match (case-insensitive) | `X-Api-Key=abc123` |
| `Header!=value` | Not equal | `Accept!=text/xml` |
| `Header*=value` | Contains | `Content-Type*=json` |
| `Header~=regex` | Regex match | `Authorization~=Bearer.*` |

### Advanced Conditions (`matchSpec`)

When `;`-joined AND conditions are not enough, a rule can carry a structured condition tree in
`matchSpec` (REST API, JSON import/export, or `echo.mock/v2` documents as `spec.match`). It supports
`all` / `any` / `not` groups and these operators: `equals`, `notEquals`, `contains`, `matches`,
`exists`, `absent`, `gt` / `gte` / `lt` / `lte` (numeric), `in`, and `equalToJson`.

```json
{
  "protocol": "HTTP", "matchKey": "/orders", "method": "POST", "responseBody": "{\"tier\":\"gold\"}",
  "matchSpec": {"all": [
    {"source": "header", "name": "X-Tenant", "op": "equals", "value": "acme"},
    {"any": [
      {"source": "body", "selector": {"jsonPath": "$.amount"}, "op": "gte", "value": 1000},
      {"source": "query", "name": "vip", "op": "exists"}
    ]},
    {"not": {"source": "body", "selector": {"jsonPath": "$.status"}, "op": "equals", "value": "CANCELLED"}}
  ]}
}
```

- Sources: HTTP rules use `body`, `query` and `header`; JMS rules use `body` (selectors `jsonPath`, `xpath`, `field`, `raw`).
- Invalid trees are rejected on save with the JSON path of the problem (for example `$.all[1].any[0].op`).
  Limits: 64 KiB, 16 levels, 256 nodes, 1,000 `in` values.
- `matchSpec` and `bodyCondition` / `queryCondition` / `headerCondition` are mutually exclusive. A tree that is only
  an `all` of `{"legacy": {"source": ..., "condition": ...}}` fragments is stored as the legacy fields.
- In the rule editor, switch the condition block from **Simple** to **Advanced** to build groups, NOT and the new
  operators; existing simple conditions become legacy fragments that keep their exact matching. Switching back is
  instant when only legacy fragments remain, otherwise the editor asks before clearing the advanced conditions.
  The editor checks the tree against the server while you type and points at the failing node.
- A request that omits both `matchSpec` and legacy conditions keeps a rule's structured conditions (so older clients
  cannot erase them by accident); send `{"all": []}` to clear them.

## Stateful Scenarios

Rules can participate in a named state machine using `scenarioName`, match only in
`requiredScenarioState`, and transition to `newScenarioState` after a successful match.
Every scenario starts in `Started`.

```json
{
  "protocol": "HTTP",
  "matchKey": "/orders/123/pay",
  "method": "POST",
  "scenarioName": "order-flow",
  "requiredScenarioState": "Started",
  "newScenarioState": "Paid",
  "responseBody": "{\"result\":\"payment-ok\"}"
}
```

Reset one scenario with `PUT /api/admin/scenarios/{name}/reset`, or all scenarios
with `PUT /api/admin/scenarios/reset`.

## Dynamic Response Templates

WireMock-style Handlebars template engine. Use `{{...}}` syntax in response content to generate dynamic content.

### Basic Syntax

```handlebars
{{request.path}}                              // Request path
{{request.method}}                            // HTTP method
{{request.query.xxx}}                         // Query parameter
{{request.headers.xxx}}                       // Header value
{{{request.body}}}                            // Request body
{{now format='yyyy-MM-dd'}}                   // Formatted time
{{randomValue type='UUID'}}                   // Random UUID
{{randomValue length=8 type='ALPHANUMERIC'}}  // Random string
```

### Conditions & Loops

```handlebars
{{#if (eq request.method 'POST')}}Created{{else}}Other{{/if}}
{{#each (split request.query.ids ',')}}{{this}}{{/each}}
```

Comparison operators: `eq`, `ne`, `gt`, `lt`, `contains`, `matches`

### JSONPath / XPath

```handlebars
{{jsonPath request.body '$.user.name'}}
{{xPath request.body '//name/text()'}}
```

### HTTP and JMS

Templates work the same way for every protocol: HTTP responses, SSE events and JMS replies. Content without `{{` is returned unchanged at no extra cost.

| Variable | HTTP | JMS |
|----------|------|-----|
| `request.body` | Request body | Message text |
| `request.headers.xxx` | Request header | `JMSMessageID`, `JMSCorrelationID`, `JMSType`, `JMSDestination` (the listening queue) and the message's own properties |
| `request.path` | Request path | Queue, plus the endpoint field value when present (`ECHO.REQUEST \| Order`) |
| `request.method`, `request.query.xxx` | Method, query parameter | Empty |

```handlebars
<reply><orderId>{{xPath request.body '//orderId/text()'}}</orderId><ref>{{request.headers.JMSCorrelationID}}</ref></reply>
```
