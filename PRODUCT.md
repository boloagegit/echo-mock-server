# Echo Product Context

## What Echo is

Echo is a desktop-oriented enterprise mock server for operating HTTP and JMS integrations. It helps integration engineers create deterministic mock rules and responses, inspect traffic, diagnose matching failures, and keep shared test environments healthy.

## Primary users

- SIT and API/JMS integration engineers working with dense operational data.
- Administrators managing connections, accounts, retention, backups, and runtime health.
- Teams that need fast scanning and predictable controls more than a spacious marketing-style interface.

## Core workflows

1. Find or create an HTTP/JMS rule and connect it to a response.
2. Inspect request logs and understand why a rule matched or failed.
3. Monitor runtime health, queues, forwarding, and backlog status.
4. Configure downstream connections, retention, backups, and access safely.
5. Import, export, audit, and maintain large rule sets without losing context.

## Product principles

- Reliability first: overload may reduce throughput, but the service must remain observable and recover automatically without silently losing accepted acknowledged messages.
- Dense but calm: prioritize table scanning, restrained hierarchy, and compact controls.
- Operational clarity: distinguish healthy, delayed, paused, failed, and recovering states in plain language.
- Preserve muscle memory: navigation order, editor field order, shortcuts, defaults, and business behavior remain stable unless explicitly approved.
- Self-contained deployment: keep the existing Vue, Bootstrap, and WebJar-based stack; no external CDN or new frontend framework.

## Visual direction

Use a restrained, high-density operational design language suited to enterprise integration work:

- Neutral black, white, and gray surfaces with crisp borders and minimal elevation.
- Retain Echo blue as a restrained interaction and focus accent.
- Use semantic colors only for meaningful operational status.
- Favor refined sidebar navigation, compact search, dense records/filter tables, task-style status rows, clear loading states, and readable code/XML surfaces.
- Support light and dark themes with the same information hierarchy.
- Avoid gradients, glow, glass effects, decorative section numbering, excessive cards, and excessive pill-shaped controls.

## Approved redesign scope

The redesign covers the complete frontend: login, navigation, rules, responses, request logs and statistics, issues, audit, settings, accounts, editors, modals, empty/loading/error states, and responsive behavior. Functional contracts and backend APIs are out of scope for visual changes.

## Success criteria

- Existing workflows and automated frontend contracts continue to pass.
- Common desktop flows are fully usable at 1440x900 without clipped actions or lost table context.
- Keyboard focus, labels, contrast, light/dark themes, narrow layouts, long XML/JSON, and long identifiers remain usable.
- The result feels like one coherent operational workspace, not a collection of individually styled pages.
