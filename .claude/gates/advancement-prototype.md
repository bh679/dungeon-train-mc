<!-- dt: advancement-prototype | version: 1.0.0 -->
# Advancement prototypes (Dungeon Train)

Read from the DT Gate 1 add-on when a change adds or changes advancements. Follows
`~/.claude/playbooks/ui-prototype.md`; the advancement editor is DT's design tool for them.

## Before plan mode

1. Start it from this worktree (it saves into the checkout it runs from):
   `python3 scripts/advancements/editor/serve.py` → http://127.0.0.1:8833. If 8833 is held by
   another worktree, leave it alone and pass `--port 8834`.
2. Open it in the built-in browser and lay out the proposal: tab, parent, icon, title, description,
   hint, visibility, required value, Everything Burrito / It's Not That Simple membership.
3. Keep the edits in the browser. **Do not press Save to repo or Save & commit before approval.**
4. Screenshot the tab and each new advancement's menu; show them rendered in chat.
5. Iterate with the user, then put the agreed screenshots in the plan.

## After approval
Save from the editor (or apply the user's "Copy changes" JSON with
`python3 scripts/advancements/editor/apply.py <file>`). Reworded or new English text still needs
all locales and a provenance restamp.
