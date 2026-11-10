# How to do a release

For shipping Dungeon Train after a Gate 3 merge, when Brennan says "tag for release". CLAUDE.md
covers when to suggest one.

**Never:** run `git tag`, or bring up a leaderboard reset. Add `-f leaderboard_reset=true` only if
Brennan asks for a reset in his own words.

## 1. Get a clean copy of main

The notes come from the files in your checkout. An out-of-date checkout gives empty or wrong notes,
and an empty notes input ships the raw commit list to every store (this happened on v0.1408.0).

```bash
git fetch origin main
git worktree add --detach .claude/worktrees/release-preview origin/main
```

Run every step below from that folder.

## 2. Check the version

```bash
git show origin/main:gradle.properties | grep '^mod_version='
gh release list --limit 5
```

The tag must equal `mod_version` on main and must not exist yet. Every merge bumps the version, so
check again just before step 6.

If the release changes dependencies (a sibling mod added or removed, a raised floor, a Sable or
NeoForge bump), run `scripts/deptest/run-all.sh` now.

## 3. Render the notes to a file

```bash
python3 scripts/release-notes/render-unreleased.py > "$TMPDIR/notes.md"
wc -c "$TMPDIR/notes.md"
```

If it is empty, stop and work out why. Only leave `changelog` out of step 6 if Brennan agrees to
ship the auto-generated commit notes.

## 4. Preview on localhost (required)

Do this **before** asking for confirmation.

```bash
python3 scripts/release-notes/preview-update-page.py
```

| Page | Shows |
|---|---|
| http://127.0.0.1:8765/update/ | The updates page with this release at the top of the timeline |
| http://127.0.0.1:8765/notes.html | The exact notes for GitHub, Modrinth and CurseForge |

Open both in the browser pane and show Brennan a screenshot of the timeline. Check that:

- every change is there, and collapsed minor updates open properly
- major and notable cards show their image and text
- nothing spoils hidden content (see the changelog rules in the workflow memory)
- there are no long dashes

The headline and store cards at the top still show what the stores list today. That is expected:
they read the live stores, and the stores don't have this release yet.

The script needs a dp-relay clone at `~/Projects/dp-relay` (or pass `--relay`) and always reads that
clone's `origin/main`. It refuses to run if your checkout isn't `origin/main`, or if the page has
changed in a way it can't patch. If that happens, fix the script rather than skipping the preview.

Stop the preview when you are done.

### Social posts (when the release has a major or notable update)

Once the release ships, the relay posts every major and notable update to the Facebook Page and
Instagram, with a story, and the Socials page offers "Copy post" text for the other platforms.
Render those before asking, too:

```bash
python3 scripts/release-notes/preview-social-posts.py
```

Open http://127.0.0.1:8766/ in the browser pane and show Brennan each update's posts. Check that:

- the Facebook and Instagram media show the picture well. An animated GIF becomes a 4:5 video for
  both; a still becomes the Facebook photo and the Instagram card.
- the title fits on the card, and the story frame reads cleanly
- the captions (title line, summary, link, hashtags) say nothing that spoils hidden content, and
  have no long dashes

It renders with dp-relay's own code at its `origin/main` and reads each picture from
`.github/release-notes/images/`, so it works before the merge too. It needs `node` and that clone's
`node_modules`. The hashtags rotate by the update's place in the live list, so the five shown can
differ from the ones that post. Stop it when you are done.

## 5. Ask for confirmation

Ask Brennan, with both previews in front of him:

> Release v`<version>`? These are the notes (all changes since the last release): ... Publishes to
> GitHub Releases, Modrinth, CurseForge and Discord.

Don't dispatch until he says yes. A changed word means going back to step 3.

## 6. Dispatch

Check `mod_version` again, then:

```bash
gh workflow run release.yml -f tag=v<version> -f changelog="$(cat "$TMPDIR/notes.md")"
```

Add `-f notify_discord=false` only if Brennan wants a quiet release. Don't merge any PR while the
run is going: that can break its mark-released step.

## 7. Watch and report

```bash
gh run watch $(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
gh release view v<version> --json url --jq .url
```

Use the version in that command: `gh release view` with no version shows an old release, because
releases are marked as pre-releases.

Share the link, then remove the worktree:

```bash
git worktree remove .claude/worktrees/release-preview
```

## What happens afterwards

| What | How it goes | If it goes wrong |
|---|---|---|
| Modrinth modpack | Publishes with no wait | A 404 on the new version is a timing issue: re-run the failed job |
| CurseForge modpack | Waits up to 300 min for the mod file's approval, then catches up hourly | Check with `python3 scripts/modpack/reconcile.py` |
| CurseForge mod file | `verify-curseforge-upload.yml` watches it | Red means rejected: run `reupload-curseforge.yml`. "Still under review" is not a rejection, so don't re-upload |
| Relay's approved mods list | `release.yml` opens or updates one dp-relay PR | Merge that PR. Never refresh the copy by hand |

## Test releases

For things only a published listing can show, such as whether launchers auto-install dependencies:

```bash
gh workflow run release.yml -f tag=v<version> -f test_release=true -f notify_discord=false \
  -f changelog="$(cat "$TMPDIR/notes.md")"
```

Tell Brennan the costs first: alpha is still public, both modpacks publish a version, and the tag
and version number are used up. Check that `AUTO_RELEASE_ENABLED` won't start the cascade off it.

## Sibling mod releases

Each sibling repo (`bh679/<name>-mc`) has its own dispatch-only `release.yml`. Its tag must match
`mod_version` in that repo:

```bash
gh workflow run release.yml -R bh679/<name>-mc -f tag=v<version>
```

The DT updates page doesn't show sibling releases, so there is no page to preview. Show Brennan the
notes and get his confirmation anyway. Discord only fires on a major version unless you pass
`-f notify_discord=true`.

Don't bump a sibling's version in DT until its release exists: that turns main's build red for
every branch.
