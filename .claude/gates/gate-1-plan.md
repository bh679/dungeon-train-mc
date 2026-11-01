<!-- gate: gate-1-plan (Dungeon Train add-on) | version: 1.0.0 -->
# Gate 1 — Plan Approval (Dungeon Train add-on)

**Follow `~/.claude/playbooks/gates/gate-1-plan.md` first.** This file only adds the Dungeon Train
steps below; the UI prototype rule there applies to every in-game screen, overlay and tooltip.

## New or changed advancements: prototype in the advancement editor
Before entering plan mode, build the proposed advancements in the local advancement editor:

1. Start it from this worktree (it saves into the checkout it runs from):
   `python3 scripts/advancements/editor/serve.py` → http://127.0.0.1:8833. If 8833 is held by
   another worktree, leave it alone and pass `--port 8834`.
2. Open it in the built-in browser and lay out the proposal: tab, parent, icon, title, description,
   hint, visibility, required value, Everything Burrito / It's Not That Simple membership.
3. Keep the edits in the browser. **Do not press Save to repo or Save & commit before approval.**
4. Screenshot the tab and each new advancement's menu; show them rendered in chat.
5. Iterate with the user, then put the agreed screenshots in the plan.

After approval, save from the editor (or apply the user's "Copy changes" JSON with
`python3 scripts/advancements/editor/apply.py <file>`). Reworded or new English text still needs
all locales and a provenance restamp.

## Mod-impact check
Call out new dependencies, MC/NeoForge/Sable bumps, mixins, registered blocks/items/entities,
world-gen changes and network packets (packets ⇒ `PROTOCOL_VERSION` bump).
