# How to edit an existing store page

For changing the text, links or screenshots of a mod that is already on Modrinth or CurseForge. The
[page rules in newmod.md](newmod.md#page-rules) apply here too.

**The one rule:** the page is public, so nothing changes until Brennan has confirmed the exact
change. Approval for one edit doesn't cover the next one.

## 1. Copy the live page

The live page is the truth. Someone may have edited it on the site, so don't start from an old copy.

```bash
python3 scripts/listing/listing.py pull <slug> --out <repo>/listing
```

This writes `listing.json`, `LISTING.md`, the icon and the screenshots. If the repo already has a
`listing/` folder, pull to a temporary folder and compare first. Commit the folder to the mod's repo
once the edit is live.

Also read the CurseForge page: its text can differ from Modrinth's.

## 2. Make the edit

- **Text and links:** edit `LISTING.md` and `listing.json`.
- **New screenshot:** add the file and an entry to `screenshots` in `listing.json`.
- **Caption or featured image:** edit the screenshot's entry in `listing.json`. Keep its `live_url`.
- **Removing a screenshot:** do it on the site. The tool never removes images, and lists what it
  left alone.

## 3. Check it

```bash
python3 scripts/listing/listing.py check <repo>/listing
python3 scripts/listing/listing.py update <repo>/listing --dry-run
```

**Older pages will fail `check`:** many predate these rules (long dashes, GitHub issues links, the
old "Built for Dungeon Train" opening). `--dry-run` still shows the diff and lists the problems, but
`--confirmed` refuses a failing page, so bring the page up to the rules as part of the edit and
include those changes in what you show Brennan. A pulled screenshot with no title fails too, so give
it one in the folder.

DT's own mod and modpack pages can't use the "designed for Dungeon Train" sentence. Set
`"dungeon_train_itself": true` on those.

## 4. Confirm with Brennan

Show him the dry-run output, and render any new screenshots in chat. Wait for a clear yes.

## 5. Apply on both stores

**Modrinth:**

```bash
python3 scripts/listing/listing.py update <repo>/listing --confirmed
```

**CurseForge has no API for pages,** so use Brennan's signed-in session with Claude in Chrome at
https://authors.curseforge.com:

1. Open the project and change only what was approved.
2. Use CurseForge links in the first section (see [newmod.md](newmod.md#page-rules)).
3. Compare with the approved text, then save.

## 6. Verify

- Run `update --dry-run` again: it should list no changes.
- Open both public pages and check the change. CurseForge can take a few minutes, and a public cache
  may lag.
- Tell Brennan what changed, with links.

## Things to watch

| Situation | What to do |
|---|---|
| Changing a slug | Don't, unless Brennan asks. DT's `release.yml` dependency lists and `modpack.config.json` use slugs |
| Changing dependencies on a page | Those come from releases and `release.yml`, not from the page |
| Mentioning hidden DT content | Don't. The changelog spoiler rules apply to store pages too |
| Editing many pages at once | Show one finished example first, then get a yes on the list of pages |
