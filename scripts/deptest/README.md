# Dependency-contract tests

Verifies that Dungeon Train's declared mod dependencies actually behave the way `mods.toml`
claims — that a missing sibling mod produces a clean, itemised error rather than a crash, and
that the version ranges accept and reject the builds they are supposed to.

## Why `./gradlew runServer` cannot do this

`build.gradle` declares the sibling mods as `implementation`, which puts them on the dev
runtime classpath. So under `runServer` / `runClient` the siblings are **always present** — a
declared-but-absent dependency structurally cannot go missing there, and the loader never gets
the chance to reject anything.

This harness installs a real NeoForge server and feeds it real jars in `mods/`. That is the
production mod-loading path: the same FML `ModSorter` a player's client runs, with no dev
classpath. No Minecraft account and no GUI are needed, so it runs unattended.

This matters because the four sibling mods (AIN, AIS, PlayerMob, EnderChestPersistence) are
**not bundled** — they are required external downloads. Every existing player hits the
missing-dependency path exactly once, on the update that un-bundled them.

## Running

```bash
scripts/deptest/setup.sh     # once — installs a NeoForge server (~200 MB), fetches one fixture
./gradlew build              # the jar under test
scripts/deptest/run-all.sh
```

Everything the harness writes (`server/`, `logs/`, downloaded jars) is gitignored.

Mod versions are read from `gradle.properties` and resolved out of the Gradle cache, so the
harness tests whatever the repo currently declares — there is no second version list to drift.
The NeoForge version follows `neo_version` for the same reason.

## The cases

| Case | `mods/` contents | Expected |
|---|---|---|
| **A** | DT + Sable + all four siblings | Server starts cleanly |
| **B** | minus AIN | Fails — `adventureitemnames … Actual version: '[MISSING]'` |
| **C** | DT + Sable only | Fails — names **all four**, with each declared range |
| **D** | PlayerMob **above** the floor | Server starts cleanly |
| **E** | PlayerMob **below** the floor | Fails — `Expected range: '[<floor>,)', Actual version: '0.50.0'` |
| **F** | minus Sable | Fails — `Expected range: '[x,x]'` (exact pin, not a minimum) |

**A is the positive control.** If it fails, every other "failed" result is meaningless — fix A
before reading anything else.

**C is the upgrade path** — what a player sees the first time they launch after updating. The
point is that it is a readable list of what to install, not a stack trace.

**D and E are the range semantics.** DT declares minimums (`[x,)`) for the siblings, so a newer
build must be accepted and an older one rejected. D matters more than it looks: the auto-release
cascade bumps `playermob_version` roughly 22 times per release cycle while `playermob_min_version`
stays put, and D is the standing proof that those bumps don't strand anyone. Under an exact pin
every one of those ticks would break existing installs.

**F is the contrast case.** Sable is exact-pinned because DT is compiled against one physics
build; the siblings are additive and take minimums. Seeing `[2.0.2,2.0.2]` next to `[0.45.0,)`
in the same run is the clearest statement of that difference.

## When to run it

Before any release that changes dependency declarations: a new or removed sibling mod, a raised
`<mod>_min_version` floor, a Sable bump, or a NeoForge bump.

Not wired into CI — it downloads a server install and takes minutes, whereas `build.yml`'s
`modpack-checks` job is deliberately fast and stdlib-only. The fast structural guards
(`scripts/modpack/check-pins.py`, `check-relations.py`) run there instead; this is the slow,
on-demand confirmation that the structure is actually true at runtime.

## Post-release launcher checklist

The harness proves the **jar's** contract. It cannot prove the **platform's**: the CurseForge
and Modrinth apps auto-install required dependencies by reading the *published listing*, not the
jar's `mods.toml`. That metadata does not exist until a release publishes, so this part can only
be checked afterwards.

Run through this after the first release that un-bundles a mod:

1. **Modrinth app** — fresh profile, install Dungeon Train from the platform. All four siblings
   should arrive without being asked for; DT should reach the main menu.
2. **CurseForge app** — same, from a new instance.
3. **Both modpacks** — install each and confirm six mods are present *and all four siblings are
   enabled*. A CurseForge `required:false` entry ships a mod switched **off**; for a hard
   dependency that breaks the pack. See `modpack/README.md` §"Enabled vs disabled by default".
4. **Manual / vanilla NeoForge** — drop in only the DT jar + Sable. Expect Case C's error,
   rendered as a screen.
5. **Check the download counters** on all four sibling project pages a day later.

Step 5 is the one that matters. Steps 1–4 verify mechanism; only the counters verify the
*purpose* — un-bundling exists so those mods get credited for the installs they were always
part of. If the counters aren't moving, the dependency declarations aren't reaching players and
the change failed silently, however green everything else looks.
