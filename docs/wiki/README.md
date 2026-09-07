# Wiki page sources

Markdown for pages on the [project wiki](https://github.com/bh679/dungeon-train-mc/wiki) that are
written by hand rather than generated.

`Downloads.md` and `Downloads-Archive.md` are **not** here — those two are rendered from the GitHub
Releases API and pushed by `scripts/publish-wiki.sh` on every release. Everything in this folder is
authored, reviewed in a normal PR, then copied to the wiki repo.

## Publishing

The wiki lives in a separate git repo (`dungeon-train-mc.wiki.git`). Copy and push:

```bash
git clone https://github.com/bh679/dungeon-train-mc.wiki.git /tmp/dt-wiki
cp docs/wiki/*.md /tmp/dt-wiki/
cd /tmp/dt-wiki && git add -A && git commit -m "docs: <what changed>" && git push
```

Add the new page to the wiki's `Home.md` page list in the same push — it is the only index the wiki
has.

## Pages

| File | Wiki page | What it is |
|---|---|---|
| `Lost-Ender-Chest.md` | [I lost my Ender Chest](https://github.com/bh679/dungeon-train-mc/wiki/Lost-Ender-Chest) | Player-facing decision tree for an empty or missing Ender Chest: chest labels, backpack panels, per-install stash location, update history, hand recovery. |
