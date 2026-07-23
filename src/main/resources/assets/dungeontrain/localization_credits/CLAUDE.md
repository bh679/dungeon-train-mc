# Localization Credits — Pack Author Guide

If you translate Dungeon Train into another language and distribute it as a resource pack, you
can add a thank-you entry that shows up on the main menu, right next to the language button —
but only when a player has YOUR language selected. Drop one JSON file per contributor in this
folder, alongside your `assets/dungeontrain/lang/<locale>.json` translation override.

This must be a **resource pack**, not a data pack — the main menu has no world loaded yet, so
only resource packs (the same channel your lang-file override already uses) are read there.

## File shape

`assets/dungeontrain/localization_credits/<your_slug>.json`:

```json
{
  "locale": "es_es",
  "name": "Your Name",
  "url": "https://example.com",
  "human_reviewed": true
}
```

- `locale` — required. The Minecraft locale code your translation targets — the same code as
  your `lang/<locale>.json` filename (e.g. `"es_es"`, `"de_de"`, `"pt_br"`).
- `name` — required. How you want to be credited.
- `url` — optional. If present, your name becomes a clickable link (players get a confirmation
  prompt before it opens, same as every other external link in the game). Omit it to show as
  plain text.
- `human_reviewed` — optional boolean, default `false`. Set `true` once a human has proofread
  this locale's translation. A language counts as human-reviewed if **any** of its credit entries
  sets this `true`. This drives the Dungeon Train logo in the vanilla language-selection list:
  human-reviewed languages show the logo solid, everything else shows it faded to 35% opacity
  with a blue "AI" label beside it (see `LanguageSelectEntryLogoMixin`).

### Generated fields (bundled files only — do not hand-edit)

The files bundled in the mod jar additionally carry three GENERATED integer fields:

```json
  "total_keys": 890,
  "ai_authored": 77,
  "ai_unreviewed": 69
```

They summarize the repo-side provenance sidecars (`localization/provenance/<locale>.json`) and
drive the **blue AI-fraction ring** around the logo in the language-selection list — the filled
fraction of the ring's circumference is `ai_unreviewed / total_keys`. Refreshed by every
`scripts/localization/stamp-provenance.py` run and hard-validated by
`scripts/localization/check-provenance.py` (CI fails when they drift), so never edit them by
hand. Third-party packs may omit them — a credit without valid counts simply renders no ring.
When several credits carry counts for one locale, the set with the greatest `total_keys` wins.

## Where it shows up

Nothing shows on the main menu unless a player has `locale` selected as their current game
language. When they do, "Localized by \<name\>" appears immediately to the left of the vanilla
language button. If nobody has your language selected, your entry is silent — the credit is
never a general "translators" list, only a per-language annotation on the language button
itself.

## Rules

- One file per contributor. Filename can be anything (`<your_slug>.json`) — it doesn't need to
  match any field inside the file.
- Multiple contributors can credit the same `locale` — all of their names show, comma-separated.
- If two active resource packs ship a file with the same filename, the higher-priority pack's
  entry wins for that filename — same as any other resource-pack override.
- A malformed file is skipped (logged) without breaking anyone else's entry.

## Loader

`LocalizationCreditRegistry.java` loads every file here into memory whenever the client's
resource packs reload (including at game launch), registered via
`LocalizationCreditsClientLoaders`. `TitleScreenLayoutHandler` looks up
`LocalizationCreditRegistry.creditsFor(currentLocale)` on every title-screen init and positions
`LocalizationCreditLabel` left of the vanilla language button only when that lookup is non-empty.
