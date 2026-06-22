# Wiki Drafts

These files were prepared by a scheduled wiki-update routine on 2026-06-22 but could not be pushed to the wiki directly because the `GITHUB_TOKEN` environment variable holds a fine-grained PAT, and **fine-grained PATs cannot push to GitHub wiki repositories** (wikis live at `<repo>.wiki.git`, which cannot be selected as a target in a fine-grained PAT's repository permissions).

## To publish

```bash
git clone https://github.com/bh679/dungeon-train-mc.wiki.git
cd dungeon-train-mc.wiki

# Copy in the prepared files:
cp ../wiki-drafts/Blog-2026-06-22.md ./

# Blog.md — add this row to the Posts table (after the header row):
# | [2026-06-22](Blog-2026-06-22) | World disintegration band ... — 18 PRs, v0.344.0 → v0.350.2 |

# Downloads.md — update Latest release to v0.350.2

# Feature-World-Disintegration.md — update Since block + config defaults + Atmosphere section
# (see diff: config defaults 500 → 5000/10000, add sky-lag + music entries)

git add -A
git commit -m "docs: dev log 2026-06-22 — world disintegration band, Aaro stories, v0.344→v0.350"
git push
```

## Fix for future runs

Replace `GITHUB_TOKEN` in the environment with a **classic PAT** that has the `repo` scope — classic PATs have full wiki write access. Fine-grained PATs do not support wiki repos.
