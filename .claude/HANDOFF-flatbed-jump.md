# Handoff: residual flatbed-only train jump

**Copy-paste everything below this line into a fresh Claude Code session at `/Users/brennanhatton/Projects/DungeonTrainCraft`.**

---

## Task

There is a small whole-train jump in the Dungeon Train mod that still happens occasionally after v0.10.0 (PR #14). It is reported as:

> **"A small jump backwards and then to the correct place. It seems to happen every time I move into a flatbed carriage. Happens occasionally — not reliably reproducible."**

Your job: find the root cause and fix it. This is a continuation of an investigation that already landed a four-layer fix; don't repeat the same approaches. Propose a new angle.

## Project context

- **Repo:** `github.com/bh679/dungeon-train-mc` (this directory is the clone at `/Users/brennanhatton/Projects/DungeonTrainCraft`).
- **Stack:** Minecraft Forge 1.20.1, Java 17, Valkyrien Skies 2 `2.4.11` (`org.valkyrienskies:valkyrienskies-120-forge:2.4.11`, `org.valkyrienskies.core:api:1.1.0+cf208d8b56`).
- **Current version:** `0.10.0` (tagged, on `main`).
- **Entry command:** `/dungeontrain spawn [count]` where count is 1–20.
- **Rolling-window behaviour:** as the player moves along the train, carriages are placed ahead and erased behind via `level.setBlock(...)` on the ship's shipyard chunks.
- **Carriage variants** (cycled by index: `i % 4`): STANDARD (0), WINDOWED (1), SOLID_ROOF (2), FLATBED (3). Flatbeds have **only** a floor (9×5 = 45 blocks) while the other three each have ~200 blocks (floor + four perimeter walls + roof, hollow interior).
- **Follow the project CLAUDE.md.** It enforces a four-gate workflow (plan → test → merge → review), SemVer on `gradle.properties`, wiki updates for VS quirks, etc. This is a real bug fix so all gates apply.

## The symptom in more detail

Observed by the human during `./gradlew runClient` testing on v0.10.0:

- Happens specifically when **entering a flatbed carriage** (i.e., the player's `pIdx` crosses into a carriage whose type is FLATBED).
- The whole visible train jumps backward (−X relative to train motion) by a small amount — a few blocks, not a teleport across the map — then snaps to the correct position.
- Does **not** happen every time the player crosses into a flatbed — the human noted "not reliably reproducible, if I go back it won't necessarily happen again."
- Does **not** happen when entering STANDARD / WINDOWED / SOLID_ROOF carriages, at least not visibly.
- Already rare enough that v0.10.0 was approved and shipped; this is a polish bug, not a blocker.

## What was already tried (v0.10.0, PR #14 — don't repeat these)

PR #14: https://github.com/bh679/dungeon-train-mc/pull/14 — merged as commit `be625cc` on main.

Wiki entry with the full story and math: https://github.com/bh679/dungeon-train-mc/wiki/Compatibility#shiptransformpositioninmodel-silently-re-centers-on-voxel-com--compensate-in-the-provider

The fix had four layers, all currently in place on `main`:

1. **Pivot-drift compensation in `TrainTransformProvider`** — every physics tick, compute `comDelta = current.getPositionInModel() − lockedPositionInModel`, rotate into world frame, add to `canonicalPos`. This makes voxel-to-world mapping `position + rot·(S − PIM)` independent of whatever `positionInModel` VS uses, because the `PIM` terms cancel:
   ```
   world(S) = (canonicalPos + rot·(currentPIM − lockedPIM)) + rot·(S − currentPIM)
            = canonicalPos + rot·(S − lockedPIM)
   ```
   See `src/main/java/games/brennan/dungeontrain/train/TrainTransformProvider.java` → `computeCompensatedTransform(ShipTransform current)`.

2. **Atomic block+transform push on server tick** — after `TrainWindowManager` finishes voxel mutations within a server tick, it immediately calls `ship.unsafeSetTransform(provider.computeCompensatedTransform(ship.getTransform()))`. Closes the ~17 ms gap between block-change packets and transform-update packets reaching the client. See `src/main/java/games/brennan/dungeontrain/train/TrainWindowManager.java`, near the end of `updateWindow(...)`.

3. **Reversal debounce in `TrainWindowManager`** — window shifts that reverse the last committed direction are suppressed for 6 ticks (~300 ms). State is held on the provider (`committedPIdx`, `lastShiftDirection`, `lastShiftTick`). Prevents the single-tick flap where pIdx oscillates ±1 at a boundary. See same file, `updateWindow(...)`.

4. **Ship marked static at assembly time** — `ship.setStatic(true)` after `setTransformProvider`, from PR #13. This skips the Bullet dynamics step but does NOT prevent `positionInModel` from being re-derived from COM. It's necessary but not sufficient, which is why layer 1 exists. See `TrainAssembler.java`.

**Layers 1 + 3 were already proven in the Gate 2 loop** to eliminate the "whole-train continuous teleport" and "teleport on every shift." Layer 2 was added specifically to fight the flatbed-entry jump and it *partially* worked — reduced from "every time" to "occasionally."

## Three hypotheses to investigate next

The human noticed the flatbed-specific pattern. Flatbeds are special because they have ~1/4 the mass of the other carriage types. Suggested angles:

### Hypothesis A: the Y-axis COM swing is what's leaking, not X

The existing compensation handles X and Y (we compute `comDelta` as a full Vector3d and rotate it). But the Y component of the drift is tiny (~0.01–0.05 blocks per shift) compared to X (~7–11 blocks), so Y was treated as noise in the investigation. **When a flatbed is added to the window while a wall-carriage is erased, the Y COM drops** — because the flatbed has no mass above dy=0 while the other types have mass at dy=1, 2, 3. Check: is the *Y* part of the compensation being applied correctly, or is there a sign flip / rotation issue that only manifests when the Y component is large enough? Log the Y component of `comDelta` specifically at the tick a flatbed enters/leaves the window and compare to the visible jump direction.

### Hypothesis B: client-side interpolation smoothing between transforms

Even with the atomic server-side push of block + transform in the same server tick, VS still **interpolates client-side** between the previous and current ship transform for smooth rendering. If the jump from old-PIM-transform to new-PIM-compensated-transform is large enough in world space, the client will visibly interpolate through an intermediate state where the train renders offset from the voxels. The voxel data arrives as chunk updates at render-tick boundaries, but the transform interpolation runs on every frame. Check: does VS have a "snap" / "non-interpolated" transform API (`unsafeSetTransform` alone may not bypass client interpolation)? Look at `ShipObjectClientWorld` — it references `ClientShipTransformProvider.provideNextTransform(prev, current, next)`. Maybe we need a **client-side** transform provider that applies the same compensation, so client interpolation works off compensated transforms rather than raw ones.

### Hypothesis C: the rendering pipeline doesn't use `positionInModel` — it uses the inertia COM directly

Our compensation is based on `ShipTransform.getPositionInModel()`. But VS has TWO places where COM lives: the transform's `positionInModel`, and `ShipInertiaData.getCenterOfMassInShip()`. If the **renderer** reads the inertia COM (via `ship.getInertiaData()`), not the transform's `positionInModel`, then our compensation is off by whatever delta exists between those two values. Check: extract both from a live ship each tick during a flatbed entry and compare them. If they differ, switch the compensation to use inertia COM.

## Where to start (concrete steps)

1. Read the current wiki entry first — it has math, code snippets, and the full "what we tried" story: https://github.com/bh679/dungeon-train-mc/wiki/Compatibility#shiptransformpositioninmodel-silently-re-centers-on-voxel-com--compensate-in-the-provider
2. Read `TrainTransformProvider.java`, `TrainWindowManager.java`, `TrainAssembler.java`, `CarriageTemplate.java` — all four are small (< 250 lines each).
3. Run `./gradlew runClient`, `/dungeontrain spawn 10`, walk forward along the train and watch specifically for the jump when entering carriages where `pIdx % 4 == 3` (those are flatbeds).
4. Instrument: add per-tick logging of `current.getPositionInModel()`, `ship.getInertiaData().getCenterOfMassInShip()`, and `ship.getTransform().getPosition()` to see if they tell a consistent story.
5. Enter Gate 1 plan mode with findings.

## Rules of the road

- **Four-gate workflow is mandatory.** Even for a one-line fix. Read `~/.claude/playbooks/gates/gate-1-plan.md`.
- **One feature per session.**
- **Re-read `CLAUDE.md` at every gate transition.**
- **Worktrees:** use a new one for isolation (`dev/<slug>`).
- **Version:** this will be a PATCH during dev, MINOR on Gate 3 merge per `gradle.properties` `mod_version`. Current `main` is `0.10.0`, so next PATCH during dev is `0.10.1`, Gate 3 merge goes to `0.11.0`.
- **After merge:** update `Compatibility.md` in the wiki with whatever new VS quirk you discovered (the hypothesis that turned out correct).

Good luck.
