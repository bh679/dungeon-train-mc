# Train Editor OOP review (2026-10-28)

Scope: `editor/` (230 files, ~59k lines), `template/`, `client/menu/editorscreen/`, the world-space menu controllers, and the per-kind dispatch in `command/EditorCommand`. The Train Builder (`builder/`) is out of scope as obsolete. Paths are relative to `src/main/java/games/brennan/dungeontrain/`.

## Summary

The editor already has the right central abstraction: `template/Template.java` is a sealed interface with 15 record kinds and polymorphic `store()`, `registry()`, `weight()`, `gate()`, `restampPlot()`, `editorPlotOrigin()`, `plotSize()`, `placeAt()`. Its javadoc calls itself "Phase 1 / Phase 2 / Phase 3 of the unification plan". Phase 1 shipped; the collapse of per-kind code onto it mostly did not. The result is four families of near-identical static classes, one per template kind, plus a layer of hand-maintained switches that re-encode facts the records already know.

| Family | Classes | Lines | Shared base? | Est. removable |
|---|---|---|---|---|
| Plot editors (`CarriageEditor`, `PortalRoomEditor`, …) | 12 | ~6,400 | none; all static | 1,500–1,800 |
| Three-tier template / sidecar stores | 17 | ~5,500 | `ContentsAllowStore` only | 1,300–1,900 |
| Block-variant sidecars (`*VariantBlocks`) | 5 (+facade) | ~4,300 | `BlockVariantPlot` facade over API, not storage | ~1,500 |
| World-space menu controllers + X-menu panes | 6 + ~10 | ~9,000 | none | 900–1,200 |
| Per-kind dispatch switches / instanceof chains | ~20 sites | | `Template` methods exist but are bypassed | ~400 |

Beyond the duplication, the copies have drifted. Twelve behaviour differences that look like bugs are listed in §6; each exists only because the same logic was written more than once.

## 1. Plot editors: one static class per kind, same lifecycle twelve times

Every editor re-implements plot layout → enter/teleport → stamp → snapshot → save → clear. Counts of private re-definitions across the editor package:

| Helper | Copies | Canonical home that exists but is bypassed |
|---|---|---|
| `record SaveResult(sourceAttempted, sourceWritten, sourceError)` | 7 (+ `template/SaveResult.java`) | `template/SaveResult` (identical). `CarriagePartEditor:212` javadoc even calls itself "a copy". 8 store adapters exist only to convert one to the other. |
| `setOutline` / `paintCage` / `drawCage` bedrock cage triple-loop | 11 | `EditorPlotCage.setOutline` (used by Carriage, Whole, Workbench only). `CarriageContentsEditor:1019` javadoc says it copies `CarriageEditor#setOutline`, which itself now delegates. |
| `plotContaining` 3-axis box test with +2 Y headroom | 10 | `WholeCarriageEditor.inBox:178` (private to Whole). Workbench uses `BoundingBox.isInside`, which lacks the headroom. |
| `record Session` + `rememberReturn` body | 3 (Carriage, Tunnel, PortalRoom) | none; `exit()` is a fallback chain in `EditorCommand:3766`. Only Carriage's `exit` calls `VariantOverlayRenderer.forget`. |
| `enter` overload cascade (null-server → origin → rememberReturn → stamp? → `EditorPlotArrival.land`) | 11, 4–6 overloads each, javadoc pasted verbatim | `EditorPlotArrival` covers only the landing. `player.getServer()` appears 36 times. |
| `save` tail: `if (!EditorDevMode.isEnabled()) return skipped(); try { saveToSource } catch → failed()` | 11 | none |
| `eraseAt` / `eraseSlot` / `eraseBox` / `fillBox` | 6 | none |
| `snapshotKey` string formats | 10, in 3 styles (bare id, `"kind:"+name`, enum-id) and rebuilt inline in `EditorDirtyCheck:150,184,504,519` and `BlockVariantPlot:554,678` | none |

Same concept, different spelling: `boolean onTop` (track-side, carriages) vs `boolean centre` (Building, ChunkFrame, LostCity, passed as `!centre`); `plotContaining` returns null / `Optional` / `boolean` (`TrackEditor:73`) / four different one-off records; stamp decision is a `boolean stamp` param, or `alreadyHere` computed inside `enter` (Part), or overridden by `ensureResident` (Contents); residency guard is `isActive(cat)` in most editors and `current().orElse(null) != cat` in Building/LostCity.

**Proposal.** Two tiers.

*Tier 1, helpers only (~450 lines, fixes §6 items 1–5):* delete the 7 `SaveResult` copies; route all 11 cages through `EditorPlotCage` (add a `QUIET`-flags overload and a guarded variant); `EditorPlotBox.contains/erase`; one `EditorSessions` map with `rememberReturn`/`exit`; one `EditorSave.toSource(primary, sidecars...)` for the dev-mode tail.

*Tier 2, instance-based `PlotEditor<T extends Template>`:* abstract `category()`, `snapshotKey(T)`, `origin(T, dims)`, `size(T, dims)`, `resident(dims)`, `place(...)`, `capture(...)`, `persist(...)`; optional `door(...)`, `captureVoid()`. Final `enter`, `walkTo`, `enterInside`, `stampPlot`, `clearPlot`, `stampAllJobs`, `clearAll`, `plotContaining → Optional<T>`, `save → SaveResult`. `Template.restampPlot` then delegates to `editorFor(t)`.

Clean fits: Track, Tunnel, Pillar section, Pillar adjunct (all `TrackSidePlots` + `TrackVariantStore` + `TrackVariantBlocks`, differing by `TrackKind` and fallback geometry; one `TrackSidePlotEditor(kind, fallback)` replaces ~650 lines), Carriage, Whole (three small subclasses), Building + ChunkFrame (shared `SlotRowLayout`). Hooks needed: PortalRoom (variable size, `primeSizes`, recorded box in snapshot), Contents (shell + interior, group columns, `ensureResident`), Part (hide filter shifts layout, no snapshot), Workbench (origin from world data, may allocate during stamp), LostCityRef (read-only).

## 2. Stores: seventeen hand-rolled three-tier stores, one exemplar

`ContentsAllowStore` is instance-based, holds `subdir`/`resourcePrefix`/`sourceRelPath`/cache, and its javadoc says the tier mechanics "were worth having once". None of the other sixteen stores use it or anything like it. `WholeHalfTemplateStore` is a verbatim copy of `WholeCarriageTemplateStore` (diff: folder, log noun, dims gate) and still carries the Room store's javadoc above its own.

Private copies per store (26 files define `sourceTreeAvailable`, 17 `resourcesRootOrNull`, 13 `loadFromConfig`/`loadFromResource`, 13 `trySaveToSource`, 11 `projectRootOrNull`, 7 identical `TemplateStore` adapter anonymous classes). `TemplateNbt.read` already does the two-tier NBT read and its javadoc says every store "already walks exactly these two tiers"; only `rawTag` uses it. `SourceTreeFiles.deleteWithClasspathTwin` is used by 5.

Drift in the copies:

- `projectRootOrNull` has three null contracts: no guard (10 stores), guard on `gameDir` (`StageStore:393`), guard on `getParent()` (`TemplateSizeStore`, `ShellWinsStore`, `TrackVariantGroupStore`).
- Dev-mode write-through gate is spelled three ways: `shipsWithGame` (ContentsAllow, VariantParts), `sourceTreeAvailable()` alone (ContentsGroup, TrackVariantGroup, which will write per-install sidecars into `src/main/resources`), `EditorDevMode.isEnabled()` (Stage, LootPrefab, ContainerContents, TemplateSize, ShellWins).
- `exists()` checks only the active write dir in Carriage/TrackVariant/ContentsAllow but the whole import search path in Whole/Half/Group.
- Size gate: Carriage/Whole cache raw and filter per call; `TrackVariantStore.get` filters inside `loadAndValidate` and caches the empty result, so a template rejected at one dims stays empty for every dims until `clearCache`.
- `BlockVariantPrefabStore.save` does not write through to source; its twin `LootPrefabStore.save` does. `BlockVariantPrefabStore.load` has no cache; Loot has `LOAD_CACHE`.
- `TrackVariantGroupStore.tryDeleteFromSource:348` catches `Exception`; siblings catch `IOException`.

`TemplateStores.clearCaches()` is a hand-kept list and already misses `WholeHalfTemplateStore`, `PortalRoomContentsAllowStore`, `TrackTemplateStore.CELLS_CACHE`, and the two prefab stores. A package switch leaves those stale.

**Proposal.** `ThreeTierStore<K, V>` built on `ContentsAllowStore`'s shape, parameterised by a `Descriptor`: `subdir(K)`, `fileName(K)`, `resourcePrefix`, `sourceRelPath`, `Codec<V>` (NBT-with-level or JSON), `SizeGate` (cache raw, filter on read), `SourcePolicy` (NEVER / ALWAYS / DEV_MODE / SHIPS_WITH_GAME), `DeleteCachePolicy` (REMOVE / TOMBSTONE), `logNoun`, `onSave`/`onDelete` hooks. One `SourceTree` utility for the path trio. A `TemplateAdapters.of(kind, saver, promoter)` factory replaces the 7 anonymous adapters. Each store registers into a `StoreRegistry` so `clearCaches()` becomes a loop. Clean fits: Whole, Half, Group (~30-line descriptors each), VariantParts, ContentsGroup core, TrackVariantGroup, both allow stores. Hooks: Carriage (HAS_BLOCKS, repair-on-save, ShellPool rename), Part (composite key), TrackVariant (legacy migration), prefabs (IDS roster), ContentsGroup (child indexes). Separate small `ManifestStore` for Stage/ShellWins/TemplateSize. Leave `ContainerContentsStore` and `WorkbenchVariantStore` alone.

## 3. Block-variant sidecars: five copies of one document class

`CarriageVariantBlocks` (1800), `CarriageContentsVariantBlocks` (676), `CarriagePartVariantBlocks` (599), `WholeVariantBlocks` (605), `track/variant/TrackVariantBlocks` each re-implement: mirror flags + getters, `loadFor` + static cache, `croppedTo`, `loadFromDisk` (user then bundled), `parse` envelope, `parsePos`/`formatPos`/`inBounds`, put/remove/clearAll/span/lock-id block, `toJson` envelope, save/writeConfig/saveToSource, delete/rename/invalidate/clearCache. The per-candidate and per-cell codecs (`parseVariantElement`, `appendVariantJson`, `parseCellValue`, `appendCellJson`) *are* shared, from `CarriageVariantBlocks`.

Drift: `CarriageVariantBlocks.parse:495-540` inlines an older version that ignores `roll`, `scope` and the legacy reroll key instead of calling its own `parseCellValue`. Copy roll/scope survive only in Track; the other four write `DEFAULT`/`BOTH`, correct only because `BlockVariantPlot.supportsCopySettings()` defaults false. `WholeVariantBlocks.resolve` inlines the band filter Contents exposes as `inBand`. Only Part's `rename` calls `StageBlockIndex.invalidateAll()`. Track has per-kind mirror defaults via `emptyFor(kind)`; the rest use all-false `empty()`.

**Proposal.** `WorkbenchVariantStore` (99 lines) already demonstrates it: it reuses `TrackVariantBlocks` as a kind-agnostic document via `fromJsonText`/`asJsonText` and delegates persistence to `TemplateSidecars.withFileText`. Split into `VariantDoc` (entries, locks, spans, mirror, roll, scope, parse/toJson) and `VariantSidecarStore<Address>` where the address supplies user path, bundled resource, source path, crop footprint, default mirror, post-rename hook. The address is what `BlockVariantPlot.key()` and `EditorTemplateAddress` already name. `TemplateSidecars.invalidateCaches`/`filesFor` then reduce to `store.invalidate(address)` / `store.paths(address)`.

## 4. Per-kind dispatch that bypasses `Template`

`Template.java:165` says `weight()` replaces "the per-kind instanceof chain in `VariantOverlayRenderer.weightFor`"; that method no longer exists. Elsewhere the chains were never collapsed:

- `EditorCommand.stampCategoryModel:3290` re-encodes `Template.restampPlot()` as a 12-arm instanceof chain with no else; it misses `WholeHalf` and `ChunkFrame` today and will miss every future kind.
- `EditorCommand.enterFirstModel:3262` same shape, same two gaps; "enter" has no `Template` method at all.
- `EditorCommand:3431` view chain re-encodes `editorPlotOrigin()`/`plotSize()` for 4 kinds; its Carriage arm duplicates `Template.Carriage.plotSize:320` line for line.
- `WholeCarriageEditor:124,227` if-chains over three whole kinds with no else.
- `EditorTemplateAddress.of:55` / `resolve:78` are hand-kept inverse tables; `resolve` has `default -> empty` and no STAGED arm.
- `EditorSaveAsKinds.of:69` is already a per-kind strategy object, attached by switch instead of living on the record; `restoreAndRestamp:387` has `default -> {}` which skips PortalRoom.
- The `WholeKind → store` mapping is written at `EditorSaveAsKinds:260,279`, `TemplateDeletes:230`, `WholeCarriageEditor:82,98`; `Template.store()` already answers it.
- `EditorDirtyCheck.dirtyKeyFor` exists twice (`:652` over `TemplateKind`, `:707` over `BuilderPhotoPaths.Kind`) and disagrees on CARRIAGE_GROUP (`model.id()` vs `null`).
- Plot keys (`"carriage:"+id`, `"contents:"+id`, `"part:"+kind+":"+name`) are spelled raw in `TemplateSidecars:663,667`, `CarriageEditor:311,503`, `CarriageContentsEditor:240,664,706`, `CarriagePartEditor:472,498,610`, `CarriagePlacer:1018`, `CarriageContentsPlacer` ×4, `CarriagePartPlacer:248`, while both `TemplateSidecars.plotKeyFor:158` and `BlockVariantPlot:295-313` claim to be "the one place these are spelled".
- `TemplateSidecars.invalidateCaches:658` is a statement switch missing CHUNK_FRAME, BUILDING, LOST_CITY; compiles silently for new kinds.
- Which weight store a kind uses: `TemplateSidecars:470,627`, `TemplateCopy:168`, and `trackKindOf` written identically in `TemplateCopy:180` and `TemplateSidecars:~484`.

Six parallel kind vocabularies drive this: `TemplateKind` (17), `EditorCategory` (8), `PlotCategory` (13), `BuilderPhotoPaths.Kind` (9), the `EditorTemplateAddress` string constants (15), `EditorSaveAsKinds.Adapter`, plus `TrackKind`/`WholeKind`.

Client side has its own copy: the per-`PlotCategory` command table (weight, phase, enter, remove/reset, label, builder) is switched in `EditorMenuScreen:733-995`, `EditorPlotTeleport:31-74,238`, `PhaseSelectScreen:69`, `EditorTemplateJump:72-103`, `CategoryTemplatesScreen:62-145`, `EditorScreenActions:297-391`, `TemplateDataSheet:232`. They disagree: `EditorMenuScreen:735` requires `named` for TRACKS weight, `EditorPlotTeleport:67` does not; `EditorMenuScreen:919` sends `portals reset <id>`, `EditorScreenActions:388` sends `portals reset <id> <name>`.

**Proposal.** Each record returns a `TemplateKindSpec` (or gains methods) for: `enter`, `address()` + registered parser, `plotKey()`/`dirtyKey()`/`snapshotKey()`, `sidecars()` (role, path, invalidator), `weightStore()` as `Optional`, `saveAsAdapter()`, `editorCategory()`/`plotCategory()`. Delete the `EditorCommand` and `WholeCarriageEditor` chains in favour of the existing methods. Resolve `BuilderPhotoPaths.Kind` + sub-kind to a `Template` once at the relay edge. On the client, put the command prefixes on `PlotCategory` so all seven files read one table. Keep as switches: `EditorCategory.models()`/`locateAt`, `EditorTypeMenus.forCategory`, `EditorPlotLabels.forCategory`, `EditorRelayWrite:113` (deliberately exhaustive), the `Pool` switches.

## 5. Menu controllers and X-menu panes

Six server-side `*MenuController` classes (`BlockVariant` 1815, `ContainerContents` 805, `PartPosition` 638, `TemplateBlocks` 397, `TemplateBlockGroups` 249, `StagePanel` 423) share no base type. Each re-implements: `Map<UUID, …> OPEN`, `forget`/`clearAll`/`resyncOpen`, the `toggle` opening (remove, send empty packet, `hasPermissions(2)`, `requires_op` action bar), the `applyEdit` guard, `actionBar` (5 identical copies), `computeUp` + `clampToFootprint` (`BlockVariantMenuController:194-209` and `ContainerContentsMenuController:260-274` are byte-identical). `EditorMenuResync.afterHistoryStep:35` lists every controller by hand. Undo recording is uneven: BlockVariant snapshots before its op switch, TemplateBlocks only around `SWAP_BLOCK`, ContainerContents marks dirty but no `EditorEditRecorder` call was found. Ten client input handlers repeat the press-arm/release-fire plumbing (`BlockVariantMenuInputHandler:73-118` vs `ContainerContentsMenuInputHandler:67-131`).

`EditorTypeMenus` row builders (`carriageRows:405`, `contentsRows:451`, `trackKindRows:473`, `EditorWholeTypeMenus.row:119`) build the same `Variant` shape differing only by weight store; a `WeightStore` interface would make them one method. They are already shared with the roster, so GUI and world menus do not duplicate row data.

`EditorDetailPane` vs `EditorStageDetailPane`: `drawIcons` (`:568` / `:405`), `tint` (`:716` / `:424`), `drawRows` (`:602` / `:509`) are copies; two paging models (`DetailPages` vs `EditorStageDetailPane.Pages`) feed the same `EditorPager`. About ten panes expose `hitTest`/`tooltipAt`/`scrollBy`/`layout`/`render`/`over` with no shared interface and differing signatures. `EditorGuiScreen` (1653) still holds creator-build download/stage/submit (~260 lines, `:508-765`), a 237-line `mouseClicked` router, scroll and tooltip routers that each repeat the "which pane is active" chain, and the key/inline-edit state machine.

**Proposal.** `PlayerMenuController<S>` base (open map, lifecycle, `requireOp`, `authorise`) + `MenuControllers` registry looped by resync/logout hooks; `MenuPanelGeometry` for `computeUp`/`clampToFootprint`; `WorldPanelInput<Hit>` for the client handlers; an `EditorPane` interface + one `SectionPages` type so `EditorGuiScreen`'s routers become a loop over active panes; move creator-build loading into its own controller.

## 6. Behaviour differences the duplication has already produced

Verified against the current tree unless marked.

1. `WholeCarriageEditor.saveHalf:336` skips `EditorRelaySave.afterSave`; `saveRoom:323` and `saveGroup:372` call it. Half saves never reach the relay path.
2. `WorkbenchEditor.enter:227` never calls `rememberReturn`, so `/editor exit` cannot return the player from a Workbench plot.
3. `EditorCommand.stampCategoryModel:3290` and `enterFirstModel:3262` have no arm for `WholeHalf` or `ChunkFrame`; both silently no-op.
4. `ChunkFrameEditor.plotContaining:89` has no resident-category gate; every other editor has one.
5. `CarriagePartEditor` has no dirty-check snapshot at all; `createFrom:405` and rename `:560` teleport with raw `player.teleportTo` instead of `EditorPlotArrival`.
6. Only `CarriageEditor.exit` calls `VariantOverlayRenderer.forget`; Tunnel and PortalRoom exits do not. Stacked entries across the three session maps need several exits (the comment at `EditorCommand:3766` admits it).
7. `CarriageTemplateStore.delete:301` (and CarriageContents, CarriagePart, TrackVariant, ContentsAllow, VariantParts, Pillar, Track) tombstones the cache with `Optional.empty()`, so a deleted user override of a *shipped* id stops resolving to the bundled copy until reload. Whole/Half/Group/LootPrefab/ContentsGroup `remove` instead and document fallback as intended.
8. `TrackVariantStore.get` caches a dims-rejected template as empty for all later dims.
9. `CarriageContentsGroupStore` and `TrackVariantGroupStore` write through to `src/main/resources` whenever the tree is writable, including for ids that do not ship; the allow-store javadoc says that case must be skipped.
10. `TemplateStores.clearCaches()` misses `WholeHalfTemplateStore`, `PortalRoomContentsAllowStore`, `TrackTemplateStore.CELLS_CACHE`, `BlockVariantPrefabStore`, `LootPrefabStore` (grep confirms no `WholeHalf` in the file).
11. `EditorDirtyCheck.dirtyKeyFor` (two overloads) disagree on CARRIAGE_GROUP. `EditorTemplateAddress.resolve` cannot resolve STAGED.
12. Client command spellings disagree: TRACKS weight `named` requirement (`EditorMenuScreen:735` vs `EditorPlotTeleport:67`); `portals reset` argument count (`EditorMenuScreen:919` vs `EditorScreenActions:388`).
13. `CarriageVariantBlocks.parse:495` drops `roll`/`scope`/legacy-reroll that its own shared `parseCellValue` handles. (Agent-reported, not re-verified by me.)

## 7. Suggested order

1. **Tier 1 editor helpers** (§1): `SaveResult` dedupe, `EditorPlotCage` everywhere, `EditorPlotBox`, `EditorSessions`, `EditorSave.toSource`. Fixes §6 items 1, 2, 4, 5, 6 as a side effect. Low risk, mechanical, ~450 lines.
2. **Delete the `EditorCommand` / `WholeCarriageEditor` chains** (§4) in favour of `Template.restampPlot`/`editorPlotOrigin`/`plotSize`; add `Template.enter`. Fixes §6 item 3. Low risk.
3. **`SourceTree` utility + `ThreeTierStore`** (§2), starting with Whole/Half/Group as the proving case, then the sidecar stores. Decide `SourcePolicy` and `DeleteCachePolicy` explicitly per store; that decision is where §6 items 7, 8, 9 get fixed. `StoreRegistry` closes item 10. Medium risk; the size-gate and tombstone semantics need tests per store. **Done** (slice 3): `editor/ThreeTierStore` + `StoreDescriptor` + `StoreCodecs` + `SourceTree` + `StoreRegistry`, `template/TemplateAdapters`; twelve stores migrated (Whole, Half, Group, VariantParts, both allow stores, ContentsGroup, TrackVariantGroup, Carriage, Part, Contents, TrackVariant), `ContentsAllowStore` deleted. Policies per store are in each facade's javadoc. **Left for a later slice:** `LootPrefabStore` and `BlockVariantPrefabStore` (IDS roster + legacy loaders; registered with `StoreRegistry` only), `ContainerContentsStore`, `WorkbenchVariantStore`, the manifest stores (`StageStore`, `ShellWinsStore`, `TemplateSizeStore`), and the internals of `PillarTemplateStore`/`TunnelTemplateStore`/`TrackTemplateStore`/`PortalRoomTemplateStore` (adapters moved only).
4. **`TemplateKindSpec` / per-record facts** (§4) for keys, sidecars, address, weight store; client `PlotCategory` command table. Fixes items 11, 12. Medium risk; the command strings are the server contract, so pin each with a golden-string test first.
5. **`VariantDoc` + `VariantSidecarStore`** (§3). Fixes item 13. Medium risk; this is the largest single consolidation.
6. **`PlotEditor<T>`** (§1 Tier 2), track-side editors first. — **Done (slice 6):** `editor/PlotEditor` + `TrackSidePlotEditor` (8 instances in `TrackSideEditors`), `WholePlotEditor` (room/half/group), `CarriagePlotEditor`; the static classes are delegates until a later slice drops them. Left static: PortalRoom, Contents, Part, Workbench, LostCityRef, Building, ChunkFrame.
7. **Menu controller base + pane interface** (§5). Lowest urgency; mostly readability.

Each step is one Gate 1 → Gate 3 feature on its own branch.
