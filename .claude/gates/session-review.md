<!-- standard: gate-4-review | version: 1.0.0 -->
# Gate 4 — Session Review

End-of-session standards compliance review.

## Trigger
After documentation is complete — the final gate before closing the session.

## Agent Actions
1. Re-read the project CLAUDE.md to get the current list of embedded standards
2. Enter plan mode and present a **Gate 4 Review Report**
3. For each standard referenced in CLAUDE.md, review the session's actions against it:

| Standard | What to check |
|---|---|
| `workflow` | All four gates were executed in order; no gate was skipped; plan mode was used at each gate; session title was updated at each status transition |
| `git` | Branch named `dev/<slug>`; commits after every meaningful unit; pushed after every commit; conventional commit messages; squash-merged via PR; feature branch deleted post-merge |
| `versioning` | `package.json` (or `VERSION`) bumped on every commit; minor bump on merge; tag created if applicable |
| `unit-testing` | Tests written before implementation (TDD); 80%+ line coverage achieved; test results included in Gate 2 report |
| `wiki-writing` | Wiki pages use correct breadcrumbs, heading hierarchy, link format, and templates; deployment docs updated if flagged |
| `project-board` | Board item created/updated; status synced at each gate transition |
| `port-management` | Ports registered if any new services were started |

4. Produce the report in this format:

```
## Gate 4 — Session Review

### Standards Checked
- [ ] workflow (vX.Y.Z) — PASS / FAIL: <details>
- [ ] git (vX.Y.Z) — PASS / FAIL: <details>
- [ ] versioning (vX.Y.Z) — PASS / FAIL: <details>
- [ ] unit-testing (vX.Y.Z) — PASS / FAIL: <details>
- [ ] wiki-writing (vX.Y.Z) — PASS / FAIL: <details>
- [ ] project-board (vX.Y.Z) — PASS / FAIL: <details>
- [ ] port-management (vX.Y.Z) — N/A / PASS / FAIL: <details>

### Deviations
<list any standards that were not fully followed, with reason>

### Remediation
<for each deviation, what action was taken or what the user should be aware of>
```

5. Present the report to the user via `ExitPlanMode`
6. If any deviations are found, ask the user whether to fix them now or accept them as-is

## Gate Requirement
User clicks Approve after reviewing the report. If deviations exist, the user decides whether to remediate or accept.

## Rules
- Only check standards that are embedded in the project's CLAUDE.md — not all standards in this repo
- Mark a standard as **N/A** if it doesn't apply to the session (e.g. `port-management` when no services were started)
- A **PASS** means all requirements of that standard were met
- A **FAIL** requires a brief explanation and suggested remediation
