# How to make and publish a new mod

For a new mod of our own. Bringing someone else's mod into DT is a different job: see
[modpack/newmod.md](../../modpack/newmod.md).

**The one rule:** nothing goes public until Brennan has confirmed the text, screenshots and links.
Private drafts are fine without asking.

## Page rules

`scripts/listing/listing.py check` enforces all of these except the last two.

| Rule | Detail |
|---|---|
| No long dashes | No em or en dashes in the title, summary, page text or screenshot captions. Use a comma, colon or new sentence |
| New issues link | `https://dungeon.training/issues#<anchor>`, never the GitHub issues URL |
| Dungeon Train in the first section | The exact sentence below, with links to both the DT mod and the modpack |
| Icon | The Dungeon Train logo with a small, very simple icon for the mod in the middle. See "The icon" below |
| Screenshots | At least one, exactly one featured, each with a title. Icon up to 256 KiB, screenshots up to 5 MiB |
| Website link | The mod's page on our site, `https://dungeon.training/custom-mods/<slug>/`, on both stores: `wiki_url` on Modrinth (step 2), Website on CurseForge (step 9) |
| Appropriate screenshots | Your judgement: see step 4 |

The first section, above the first heading:

> This mod is designed specifically for the [Dungeon Train modpack](https://modrinth.com/modpack/bEFyz3ji) / [mod](https://modrinth.com/mod/dungeon-train), but can be used independently.

On CurseForge, use the CurseForge links:
`https://www.curseforge.com/projects/1556213` and
`https://www.curseforge.com/minecraft/mc-mods/dungeon-train`.

**If the mod does nothing without DT,** that sentence would be false. Set
`"requires_dungeon_train": true` and write this instead, keeping both links in the first section:

> Built for Dungeon Train and only does something when Dungeon Train is installed.

## The icon

Every mod of ours uses the same icon, on both stores and as the CurseForge logo: the
[Dungeon Train logo](../../src/main/resources/logo.png) with a small, very simple picture in the
middle of the ring that says what the mod is about. Use the real Minecraft texture for it, scaled
up with nearest-neighbour so the pixels stay sharp: Sable Mob Paths uses the villager's face from
`textures/entity/villager/villager.png`, nose included, a little wider than the ring's hole. Keep
the logo's transparent background.

Make it 512 x 512 and under 256 KiB, and save it as `listing/icon.png`. CurseForge asks you to
crop the logo on upload: drag the crop box out to the whole image.

## 1. Make the repo

- Use the `/new-project` skill. Name it `bh679/<name>-mc`.
- Copy the layout of a recent sibling: `bh679/dtmodverify-mc` (NeoForge only) or `bh679/keeptrim-mc`
  (several loaders). Take `build.gradle`, `CLAUDE.md`, `.github/workflows/`.
- Keep the release jar's file name fixed from the first release. DT and the release cascade find it
  by name, and a rename silently breaks them.
- Add it to [`scripts/dev-hours/repos.json`](../../scripts/dev-hours/repos.json):
  `{ "name": "<name>-mc", "repo": "bh679/<name>-mc" }`.

## 2. Write the page

Create `listing/` in the mod's repo:

```
listing/
  listing.json
  LISTING.md
  icon.png
  screenshots/01-what-it-shows.png
```

`listing.json`:

```json
{
  "slug": "example-mod",
  "title": "Example Mod",
  "summary": "One line, up to 256 characters.",
  "categories": ["utility"],
  "license_id": "LicenseRef-PolyForm-Shield-1.0.0",
  "license_url": "https://polyformproject.org/licenses/shield/1.0.0",
  "source_url": "https://github.com/bh679/example-mc",
  "issues_url": "https://dungeon.training/issues#example",
  "discord_url": "https://discord.gg/hnj5G7duHU",
  "wiki_url": "https://dungeon.training/custom-mods/example-mod/",
  "icon": "icon.png",
  "screenshots": [
    {"file": "screenshots/01-what-it-shows.png", "title": "Short caption", "description": "One line", "featured": true}
  ]
}
```

Use the Discord invite exactly as above, on both stores: `discord.gg/dungeontrain` is not a real
invite.

`wiki_url` is the mod's own page on our site, the one step 10 adds. Modrinth has no "website" link,
so the site page goes in its Wiki slot; on CurseForge it is the Website link. Use the same slug as
the site page will: lowercase, words joined by hyphens.

Our mods default to PolyForm Shield 1.0.0: put it in the repo's `LICENSE` too. Modrinth has no
SPDX id for it, so it needs both `license_id` and `license_url` as above. `live_url` entries appear on screenshots after
upload: leave them in place, as that is how a later caption edit finds the image.

`LISTING.md` holds the page text and nothing else. A good shape is: first section, what it does,
what it doesn't do, setup. See `LISTING.md` in `bh679/dtmodverify-mc` for the tone.

Read the mod's code before claiming anything it does. Also say plainly if it sends anything over
the network: store reviewers and players both look for that.

## 3. Add the issues row

The issues page draws its list from the relay, so adding a mod takes one command, with no dp-relay
PR or deploy:

```bash
python3 scripts/listing/listing.py issues-add <repo>/listing             # shows the row
python3 scripts/listing/listing.py issues-add <repo>/listing --confirmed # adds it
```

It takes the anchor from `issues_url`, the name from `title` and the repo from `source_url`, which
must be `https://github.com/bh679/<repo>`. Running it again with the same anchor updates the row. The
row shows on the page within a minute, and `check` reads the same list. The call uses the relay admin
URL in `~/.config/dungeontrain/relay-admin-url.txt` and never prints it. The page is public, so add
the row once the mod's name and repo are settled. Use `--offline` on `check` while drafting before
then.

Write the link with no slash before the `#`: Modrinth refuses `https://dungeon.training/issues/#<anchor>`
as an unknown link, but accepts `https://dungeon.training/issues#<anchor>`. If a moderator questions
it, tell Brennan rather than switching back to GitHub on your own.

## 4. Take the screenshots

- Real in-game shots: F2 in `./gradlew runClient`, saved to `run/screenshots/`. Headless harnesses
  are listed in the build and test memory.
- Show what the mod does, with the feature clearly visible. Before and after pairs work well.
- Lead with the shot that explains the mod best, and mark it as featured.
- Leave out hidden DT content (new mobs, secret bands, dimensional carriages), debug screens, and
  real players' names or chat.
- Keep them legible at a small size, about 1600 wide.

**Look at each image yourself** before using it. `check` only knows the file exists.

## 5. Set up tokens (once per machine)

Tokens live in `~/.config/dungeontrain/publish-tokens.env`, outside every checkout, mode 600:

```
MODRINTH_TOKEN=
CURSEFORGE_TOKEN=
CURSEFORGE_API_KEY=
```

Brennan creates them and pastes them into the file. Never put a token in chat, and never print one.

| Token | Where | Permissions |
|---|---|---|
| `MODRINTH_TOKEN` | https://modrinth.com/settings/pats | `PROJECT_CREATE`, `PROJECT_READ`, `PROJECT_WRITE`, `VERSION_CREATE`, `VERSION_READ`, `VERSION_WRITE` |
| `CURSEFORGE_TOKEN` | https://authors.curseforge.com/#/settings/api-tokens | Upload only |
| `CURSEFORGE_API_KEY` | https://console.curseforge.com/ | Read only |

```bash
python3 scripts/listing/listing.py tokens
```

Each line should say `ok`. The Modrinth token has no delete permission, so a draft made by mistake
has to be deleted by Brennan on the site.

## 6. Check and create the Modrinth draft

```bash
python3 scripts/listing/listing.py check <repo>/listing
python3 scripts/listing/listing.py draft <repo>/listing
```

The draft is private and gives you the real page to review. If Modrinth rejects a field, such as a
license or category name, the tool prints its error message.

## 7. Confirm with Brennan

First build a localhost preview of each store page, an exact copy of how the whole profile will
look, as in [editmod.md step 4](editmod.md#4-confirm-with-brennan), and send him the link. Then send
him all of this and wait for a clear yes:

- **Text:** title, summary and full page text
- **Screenshots:** the images rendered in chat, with captions, and which is featured
- **Links:** issues, source, Discord, the DT links, and the draft URL

Anything he changes: edit the folder, run `update --dry-run`, then show him again. Drafts count as
not public, so use `update --confirmed` to keep the draft in step with his edits.

Approval here covers this text and these images only. A later change needs its own yes.

## 8. Set up the release

Add the project ids and tokens to the mod's repo. This keeps the values out of your shell history
and your output:

```bash
gh variable set MODRINTH_PROJECT_ID -R bh679/<name>-mc --body <modrinth id>
grep '^MODRINTH_TOKEN=' ~/.config/dungeontrain/publish-tokens.env | cut -d= -f2- | gh secret set MODRINTH_TOKEN -R bh679/<name>-mc
grep '^CURSEFORGE_TOKEN=' ~/.config/dungeontrain/publish-tokens.env | cut -d= -f2- | gh secret set CURSEFORGE_TOKEN -R bh679/<name>-mc
```

Create the CurseForge project (step 9) to get its id, then set `CURSEFORGE_PROJECT_ID` the same way.
Run the mod's first release: see [release.md](release.md#sibling-mod-releases). Modrinth won't review
a project with no version.

Client and server sides aren't part of the page: Modrinth takes them from versions. Check that the
"Set Modrinth side metadata" step in the copied `release.yml` has the right values for this mod, and
confirm the sides on the draft after the first release.

Modrinth's content disclosures unlock once the first version is up (Settings, Disclosures). Turn on
only "Contains AI-generated content", tick Code and Text (not Assets), and use this explanation
exactly:

> I am a software engineer with 20 years of experience coding games. AI Guidance was used when writing this code.

Leave every other disclosure off.

## 9. Publish on both stores

Only after step 7.

**Modrinth:**

```bash
python3 scripts/listing/listing.py submit <slug> --confirmed
```

If Modrinth says "link could not be verified", its page has hit GitHub's rate limit in the browser.
Reload once the limit resets. If it is the Wiki link it can't verify, the site page isn't live yet:
do step 10 first, then submit again.

**CurseForge has no API for pages,** so this happens on Brennan's signed-in session with Claude in
Chrome at https://authors.curseforge.com:

1. Create the project: name, summary, category, and the same links as `listing.json`. Defaults:
   - **Allow Comments:** on.
   - **License:** PolyForm Shield License 1.0.0 (it is in CurseForge's list).
   - **Project distribution:** Allow distribution to 3rd party, so modpacks and launchers can
     include the file.
2. Set the description to `LISTING.md`, using the CurseForge links in the first section.
3. Set the issues link: the new portal's Issues tab opens the legacy settings page. Choose
   External and enter the same `https://dungeon.training/issues#<anchor>` as `listing.json`.
4. Set the project's **Website** link to the mod's site page, the `wiki_url` from `listing.json`
   (`https://dungeon.training/custom-mods/<slug>/`).
5. Upload the icon and screenshots.
6. Check the form against what Brennan approved, then submit.

If Chrome hits a sign-in page, ask Brennan to sign in. Never type a password.

Do step 10 the same day: both stores now link to the site page, and a reviewer who follows the link
should not land on a missing page.

## 10. Add it to the website

Every mod of ours has a card on https://dungeon.training/custom-mods/ and a page of its own there. The
page is named for the count ("17 Custom Mods"), so a new mod also renames it. It all lives in
`bh679/dp-relay`; make the change in a dp-relay worktree.

1. Add an entry to `public/dungeontrain/custom-mods/mods.json`: `slug` (the one in `wiki_url`),
   `name`, `group` (one of the groups at the top of the file), `summary` (one line), `description`
   (two or three short paragraphs), `loaders`, `standalone` (false if it does nothing without DT),
   `curseforge`, `modrinth` (once listed) and `github`. GitHub links are hidden on the site for now,
   but fill it in.
2. Add the icon as `public/dungeontrain/custom-mods/icons/<slug>.png`: `listing/icon.png` scaled to
   160 x 160 (`sips -Z 160`). New images need `git add -f`: a global ignore rule matches them.
3. Add the mod to `MODS` in `custom-mod-downloads.js`: its CurseForge project id, and its Modrinth
   slug once listed. This is what keeps its download count fresh. Add its current counts to
   `public/dungeontrain/custom-mods/downloads.json` so the card shows a number from the start.
4. Run `node scripts/build-custom-mods.js`. It writes the new page and card, renames the page to the
   new count on every page, and updates the home page footer link and the sitemap. Never edit the
   generated pages by hand.
5. The site's text follows the page rules above and three more: no recipes (hint instead), nothing
   about hidden DT content, and nothing a store page doesn't already say about it.
6. Show Brennan the new card and page in a localhost preview and wait for a yes.
7. Open the dp-relay PR (`npm test` must pass), merge after his yes, then deploy the relay
   (`scripts/deploy-relay.sh`, for the download counts) and the pages
   (`scripts/deploy-public-pages.sh`). Check the live card, page and footer link.

## 11. Follow up on review

- Modrinth and CurseForge both review by hand. Tell Brennan what is waiting and where.
- Answer any moderator message only after showing Brennan your reply.
- Check that both public pages show what was approved.

## 12. If DT will use it

Follow "Hybrid siblings" and "Sibling-mod floors" in CLAUDE.md and the wiring checklist in
[modpack/newmod.md](../../modpack/newmod.md#wiring-checklist). Add the mod's ids to the approved list
by running `python3 scripts/modpack/pull-approved-hashes.py`, because the build fails on an approved
id with no record.

Editing the page later: see [editmod.md](editmod.md).
