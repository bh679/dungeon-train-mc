<!-- gate: gate-1-plan (Dungeon Train add-on) | version: 1.1.0 -->
# Gate 1 — Plan Approval (Dungeon Train add-on)

**Follow `~/.claude/playbooks/gates/gate-1-plan.md` first.** This file only adds the Dungeon Train
steps below; the UI prototype rule there applies to every in-game screen, overlay and tooltip.

## Research & Reuse: where to look
- **Your own mods first:** the sibling repos (`gh repo list bh679 | grep -- -mc`: AIN, AIS, PMOB, ECP,
  TE, Keep Trim, Dungeon Backup, Lost City Terrain Fit…) and the mods DT already depends on (Sable,
  BoP, WWOO, BetterNether/End, Deeper Darker, VanillaBackport, Exposure). Prefer a seam in one of
  those over new code in DT.
- **Mod registries:** Modrinth and CurseForge, NeoForge 1.21.1. A mod that does it already is a
  candidate dependency or modpack companion. Check its licence before porting code (Big Lost City
  is All Rights Reserved: no DT processors on its templates).

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

## Mod-impact checklist (in place of the template's deployment checklist)
Flag each that applies in the plan's Risks, with its follow-up:

| Change | Follow-up |
|---|---|
| New/removed dependency or modpack mod | `modpack.config.json` pins + `mod_ids`, `release.yml` `curseforge-dependencies`, whitelist question at Gate 3, `scripts/deptest/run-all.sh` before release |
| MC / NeoForge / Sable bump | Sable: modpack `sable.file_id` + `sable.modrinth_version`; deptest |
| Sibling floor (`<mod>_min_version`) raised | Refresh that mod's modpack pin; `check-pins.py` |
| New mixin | Target mod present on both client and server? Mixin guard if third-party |
| New registered block/item/entity | Lang keys in all 20 locales; loot/creative tabs |
| World-gen change | Determinism across bands; existing worlds unaffected? |
| Network packet added/changed | Bump `PROTOCOL_VERSION` |
| New bundled templates | Regenerate `house_credits.json` |
| Block physics / collision / world interaction | Gate 2 Sable test on a moving train |
| Relay API change | Relay deploy order relative to the mod release |
