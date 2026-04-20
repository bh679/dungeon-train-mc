<!-- gate: gate-1-plan | version: 1.0.0 -->
# Gate 1 — Plan Approval

**Trigger:** Before writing any code.

## Research & Reuse
**Mandatory before writing the plan.**

- **GitHub code search first:** Run `gh search repos` and `gh search code` to find existing implementations, templates, and patterns before writing anything new.
- **Check package registries:** Search npm, PyPI, crates.io, and other registries before writing utility code. Prefer battle-tested libraries over hand-rolled solutions.
- **Search for adaptable implementations:** Look for open-source projects that solve 80%+ of the problem and can be forked, ported, or wrapped.
- Prefer adopting or porting a proven approach over writing net-new code when it meets the requirement.

## Agent Actions
1. Research & reuse (see above)
2. Enter plan mode (`EnterPlanMode`)
3. Explore the codebase — read relevant files, understand existing patterns
4. Write a plan to the plan file covering:
   - What will be built
   - Which files will be changed and why
   - Estimated complexity
   - Risks or dependencies
   - Deployment impact (see checklist below)
5. Present the plan to the user via `ExitPlanMode`

**Gate requirement:** User clicks Approve in plan mode.

**Never skip:** Even for "simple" changes. Plan mode catches assumptions early.

## Related Playbooks
Before creating or searching board items, read `~/.claude/playbooks/project-board.md`.

## Deployment Impact Checklist
Assess whether the planned changes impact deployment. Flag if any apply:

- Environment variable additions, removals, or changes
- New dependencies or major version bumps
- Port or networking changes
- Database schema migrations
- New API endpoints requiring proxy/load balancer config
- Docker/container configuration changes
- Build step changes (new tools, changed commands, new artifacts)
- New external service integrations (credentials/config needed)
- Startup or shutdown procedure changes
- Infrastructure requirement changes (memory, CPU, storage)

**If any apply:**
1. Check for existing `Deployment-*.md` wiki pages
2. Include "Update deployment documentation" as a task in the plan
3. Note which deployment methods are affected

**If no deployment docs exist yet:** create them after Gate 3 (see gate-3-merge.md).

## Session Identification
Update the session title on entering this gate:

**Title format:** `PLAN - <Task Name> - <Project Name>`

| Status code | Meaning |
|---|---|
| `IDEA` | Exploring / not yet started |
| `PLAN` | Gate 1 in progress |
| `DEV` | Implementing |
| `TEST` | Gate 2 in progress |
| `DONE` | Merged and shipped |

Update the title on every status transition. Sync to GitHub Project V2 board item if applicable.
