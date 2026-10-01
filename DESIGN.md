# Echo Interface Design

Echo uses a compact, neutral operational interface built for clarity and restraint. The interface keeps existing workflows and behavior intact while making dense HTTP and JMS data easier to scan.

## Visual system

- Surfaces use black, white, and neutral gray with crisp one-pixel borders and minimal shadow.
- Echo blue is reserved for primary actions, selected states, links, and keyboard focus.
- Green, amber, and red communicate operational meaning only; they are not decorative accents.
- Corners remain modest and consistent. Controls should feel precise rather than soft or oversized.
- Light and dark themes keep the same hierarchy and component geometry.

The shared theme tokens live in `src/main/resources/static/theme.css`. Page and component styling live in `src/main/resources/static/style.css`. New UI should use those tokens instead of introducing one-off colors.

## Layout and density

- The left sidebar provides stable workspace navigation on desktop and collapses on narrow screens.
- Page headers keep the title, record count, and primary actions visible without competing cards.
- Filters form one compact toolbar; related choices are grouped and search receives the remaining width.
- Tables are the primary workspace surface. Rows prioritize identifiers, protocol, endpoint, state, and actions in that order.
- At narrower widths, secondary columns disappear before core actions or record identity.
- Editors use a split workbench so matching criteria and response behavior remain visible together.

## Components

- Buttons use neutral borders by default; only the main action uses a filled blue treatment.
- Selected navigation items and rows retain their selected background on hover.
- Inputs, selects, and editors share the same border, background, and focus treatment.
- Modals have clear header, content, and footer separation and must remain usable at 1024px and below.
- Empty states explain what will appear and what causes it, without decorative illustration.
- Code, JSON, and XML use a dedicated neutral code surface and monospace text.

## Settings information architecture

- Keep five local tabs: Overview, Resource Monitoring, Forward Connections, Data & Backups, and Service Configuration.
- Overview is a compact snapshot summary, not a health dashboard. Resource details separate current usage, lifetime counters, and configured limits/timestamps.
- Retain collection availability and stale states; unavailable values must never appear as zero. Do not imply that cleanup counters measure broker inventory or that a backup proves successful restore.
- HTTP and JMS targets use one shared table with source, destination, default role, last explicit test, and actions. At narrow widths, stack these fields instead of hiding operational actions or outcomes.
- Keep backup actions separate from collapsed destructive maintenance. Long connection strings belong in a disclosure, not the primary summary.
- Switching tabs or opening details must not refresh resources. Keep one monitoring instance and preserve manual refresh, retry, timeout, and permission-loss behavior.

## Accessibility and behavior

- Every interactive control must remain keyboard reachable and display a solid focus ring.
- Text and action colors must maintain readable contrast in both themes.
- Icons supplement labels; they do not replace meaning unless an accessible name is present.
- Loading, error, paused, delayed, and recovery states must be stated in text as well as color.
- Visual changes must not alter API calls, validation, field order, defaults, shortcuts, or save behavior.

## Review checklist

Before merging a UI change, verify the affected flow in light and dark themes, at 1440x900 and a narrow viewport, with long identifiers and long XML or JSON content. Run the frontend contract tests and confirm that no external CDN or new frontend framework was introduced.
