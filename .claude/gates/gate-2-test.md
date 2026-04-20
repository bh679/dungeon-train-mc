<!-- gate: gate-2-test | version: 1.0.0 -->
# Gate 2 — Testing Approval

**Trigger:** After isolated implementation is complete.

## Agent Actions
1. Run unit tests — verify 80%+ line coverage (see unit-testing standard)
2. Run integration/e2e tests (curl for APIs, Playwright MCP for UI)
3. Take screenshots using `browser_take_screenshot`
4. Use `browser_snapshot` for accessibility tree analysis
5. Enter plan mode and present a **Gate 2 Testing Report** containing:
   - Clickable local URL with port
   - Unit test summary: total, passed, failed, skipped, coverage %
   - Screenshot paths (for blogging)
   - Step-by-step user testing instructions
   - Integration/e2e test results summary
   - What passed / what failed

**Gate requirement:** User tests manually and clicks Approve.

## Screenshot Naming
```
gate2-<feature-slug>-<timestamp>.png
```

Save in `./test-results/`.

## Related Playbooks
Before starting dev servers, read `~/.claude/playbooks/port-management.md`.

## Session Title Update
Update title to: `TEST - <Task Name> - <Project Name>`
