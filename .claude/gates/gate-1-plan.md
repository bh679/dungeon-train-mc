<!-- gate: gate-1-plan (Dungeon Train add-on) | version: 1.2.1 -->
# Gate 1 — Plan Approval (Dungeon Train add-on)

**Follow `~/.claude/playbooks/gates/gate-1-plan.md` first.** This file only adds the Dungeon Train
steps below; its UI prototype step applies to every in-game screen, overlay, HUD and tooltip.

## Research & Reuse: where to look
- **Your own mods first:** the sibling repos (`gh repo list bh679 | grep -- -mc`: AIN, AIS, PMOB, ECP,
  TE, Keep Trim, Dungeon Backup, Lost City Terrain Fit…) and the mods DT already depends on (Sable,
  BoP, WWOO, BetterNether/End, Deeper Darker, VanillaBackport, Exposure). Prefer a seam in one of
  those over new code in DT.
- **Mod registries:** Modrinth and CurseForge, NeoForge 1.21.1. A mod that does it already is a
  candidate dependency or modpack companion. Check its licence before porting code (Big Lost City
  is All Rights Reserved: no DT processors on its templates).

## New or changed advancements
Before plan mode, read `.claude/gates/advancement-prototype.md` and prototype them in the advancement
editor.

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
| Bundled templates added or re-saved, or room weights changed | Regenerate `house_credits.json` (CI fails if stale) |
| Block physics / collision / world interaction | Gate 2 Sable test on a moving train |
| Relay API change | Relay deploy order relative to the mod release |
