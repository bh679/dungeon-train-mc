# The Shaders menu, and where its previews come from

The title screen's **Shaders** button (between Mods and Options) opens a page listing the nine
supported Iris packs — the ones `compat-matrix.md` measured as loading and rendering Dungeon Train's
bands and dimensional carriages correctly. Each one shows a screenshot, and one button downloads it,
installs it and turns it on.

This document is about the two halves that are not obvious: where the list comes from, and where the
screenshots come from.

---

## The list

`src/main/resources/assets/dungeontrain/shader_menu/packs.json`, generated:

```bash
python3 scripts/shaders/fetch-packs.py            # rewrite the manifest
python3 scripts/shaders/fetch-packs.py --download # …and fetch the zips into run/shaderpacks/
```

The nine packs and their exact builds are pinned in the script's `PACKS` table; the script resolves
each against the Modrinth API and writes out the download URL, SHA-512, size, author and project
page. Pinning by version string rather than "latest" is deliberate: a pack releasing a new build
should never silently change what the menu offers, because the compatibility result belongs to the
build that was measured. Moving a pin is an edit to the table and a re-run.

**Nothing is mirrored or bundled.** Most of these packs are all-rights-reserved or custom-licensed.
The URL in the manifest is the author's own Modrinth CDN link, fetched only when a player clicks
Download, the way a launcher does it, and the pack's own project page is one button away.

Two properties of the manifest are load-bearing and easy to undo by accident:

- **It is read off the classpath, not through the resource manager** (`ShaderPack.MANIFEST`). Every
  entry carries a URL the game will fetch and a hash it will trust; a resource pack able to override
  this file could point the download button anywhere.
- **The host allowlist is checked twice** — once when the manifest loads (a bad entry is dropped, so
  the page never shows a button that cannot work) and once at download time, *after* redirects.

## The previews

One frame per pack, same world, same camera, same hour. Anything that varies between them is a
difference the page attributes to the shader pack, so nothing may.

```bash
# 1. the packs themselves, into run/shaderpacks/
python3 scripts/shaders/fetch-packs.py --download

# 2. one client launch per pack; run/screenshots/preview-<family>.png
scripts/shaders/sweep-all.sh --preview "SweepWorld"

# 3. crop, scale and install as assets/dungeontrain/textures/gui/shaders/<id>.png
python3 scripts/shaders/pack-previews.py
```

Step 2 is the compatibility sweep's harness in a second mode (`-PshaderPreview`, `ShaderSweep`).
It shares everything that makes the sweep trustworthy — one launch per pack, `iris.properties`
rewritten between launches, the window fronted, the stale-frame counter — and differs in three ways:
one site instead of thirteen, the F3+5 panel **off**, and no `vanilla` control (the page's "Shaders
off" row says what it is in words; there is nothing to compare).

The site itself pins everything that could drift:

| | |
|---|---|
| `gamerule doDaylightCycle false` + `time set 1000` | Morning. A pack's sky is a function of the hour, so two packs photographed at different times are not comparable. |
| `dungeontrain speed 0.0`, then sixty ticks | The train is stopped before it is photographed. A moving train is a different picture every launch. |
| `gamemode spectator` | A rider sits on a Sable sub-level, so the server resolves a rider's relative coordinates in far shipyard plot space rather than on the track. |
| `frame:` | Places the camera **from where the train actually came to rest**, not from a coordinate. |

That last one is why the camera placement is a pseudo-command rather than a `tp` in the list: the
list is built while the train is still rolling, and it keeps rolling through the sixty ticks it takes
to stop. Coordinates computed at build time would frame empty track.

Step 3 checks the two ways a preview run lies, both of which produce a plausible image rather than an
error:

- **A stale capture** — macOS stops rendering an occluded window while ticks continue, so the
  framebuffer never changes. Caught as a byte-identical duplicate of another pack's frame. (The
  harness also shouts `THE IMAGE IS PROBABLY STALE` in the log.)
- **A pack that failed to compile** — Iris then has *no* pack active, and the capture is named for
  whatever is active, so a broken pack quietly photographs vanilla. Caught as a near-black frame at
  this lit morning site, where nothing legitimately dark is in view.

Both abort that pack rather than writing an asset, and the script exits non-zero listing every pack
it could not produce.

> ⚠ The in-game editor auto-writes `src/main/resources` while the harness flies around. Check
> `git status` after every run — see `compat-matrix.md`.
