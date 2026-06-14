package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.debug.DebugFlags;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.EntityVariantApplicator;
import games.brennan.dungeontrain.editor.LootPrefabStore;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.narrative.block.NarrativeLecternBlock;
import games.brennan.dungeontrain.train.CarriageContents.ContentsType;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interior-contents blueprint — stamps the {@link CarriageContents} template
 * into the interior volume of a carriage at a given origin. Parallel to
 * {@link CarriagePlacer} but scoped to the {@code (length-2) × (height-2) × (width-2)}
 * interior region; the shell floor/walls/ceiling are placed separately by
 * {@link CarriagePlacer#placeAt} and are not affected.
 *
 * <p>{@link #placeAt(ServerLevel, BlockPos, CarriageContents, CarriageDims)}
 * first tries an NBT-backed template from {@link CarriageContentsStore}; if
 * none is saved (or the file's footprint doesn't match the world's current
 * interior dims), the {@code default} built-in falls back to a hardcoded
 * generator that places a single stone pressure plate at the interior floor
 * centre, and custom contents place nothing.
 */
public final class CarriageContentsPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Diagnostic tag prefix. Combined with a carriage pIdx as
     * {@code DT_CONTENTS_TAG_PREFIX + pIdx}, e.g.
     * {@code "dungeontrain_contents_pidx_42"}. Read by
     * {@code ContentsEntityDiagnostics} to filter lifecycle events.
     */
    public static final String DT_CONTENTS_TAG_PREFIX = "dungeontrain_contents_pidx_";

    /**
     * Returns the tag string used to mark contents-entities for the given pIdx.
     */
    public static String contentsTagFor(int carriagePIdx) {
        return DT_CONTENTS_TAG_PREFIX + carriagePIdx;
    }

    /**
     * NBT keys stamped on every spawned contents entity so the diagnostic
     * sampler can compare current position against original spawn pos.
     * All four are absolute world coords in shipyard space.
     */
    public static final String NBT_SPAWN_SHIPYARD_X = "DungeonTrainSpawnShipyardX";
    public static final String NBT_SPAWN_SHIPYARD_Y = "DungeonTrainSpawnShipyardY";
    public static final String NBT_SPAWN_SHIPYARD_Z = "DungeonTrainSpawnShipyardZ";
    public static final String NBT_SPAWN_GAME_TICK = "DungeonTrainSpawnGameTick";
    /** Carriage pIdx the entity was spawned for. Redundant with the tag but cheap. */
    public static final String NBT_SPAWN_CARRIAGE_PIDX = "DungeonTrainSpawnPIdx";

    private CarriageContentsPlacer() {}

    /**
     * Interior size (x=length-2, y=height-2, z=width-2) as a {@link Vec3i}.
     * Returns a size where any component may be &lt;= 0 when the shell dims are
     * at their minimums; callers should treat a zero-or-negative dimension as
     * "no interior volume" and skip placement.
     */
    public static Vec3i interiorSize(CarriageDims dims) {
        return new Vec3i(
            Math.max(0, dims.length() - 2),
            Math.max(0, dims.height() - 2),
            Math.max(0, dims.width() - 2)
        );
    }

    /**
     * Interior origin = {@code carriageOrigin.offset(1, 1, 1)}. This is the
     * minimum corner of the inside volume — one block in from each perimeter
     * wall and one block above the floor.
     */
    public static BlockPos interiorOrigin(BlockPos carriageOrigin) {
        return carriageOrigin.offset(1, 1, 1);
    }

    /**
     * Stamp the interior contents for {@code contents} at the given carriage
     * {@code carriageOrigin} (shell's min corner). Only places blocks inside
     * the interior volume — the shell's floor/walls/ceiling placed by
     * {@link CarriagePlacer#placeAt} are untouched.
     *
     * <p>This 4-arg overload is used by editor preview / template flows and
     * runs the contents pass synchronously: blocks AND entities together. The
     * carriagePIdx is unknown in these contexts (no train), so the entities
     * are spawned with the sentinel pIdx {@link #EDITOR_SENTINEL_PIDX}.</p>
     */
    public static void placeAt(ServerLevel level, BlockPos carriageOrigin, CarriageContents contents, CarriageDims dims) {
        // Editor preview / template flows have no real seed — pass 0 so the
        // entity-variant lookup behaves deterministically for previews too
        // (sidecar.resolve handles any seed value the same way).
        placeAtInternal(level, carriageOrigin, contents, dims, /*seed*/ 0L, EDITOR_SENTINEL_PIDX, /*placeBlocks*/ true, /*spawnEntities*/ true);
    }

    /**
     * Spawn-time variant-aware overload — stamps the base contents, then
     * overlays the per-position variants picked deterministically from
     * {@code (seed, carriageIndex, localPos)}. Editor calls go through the
     * 4-arg {@link #placeAt(ServerLevel, BlockPos, CarriageContents, CarriageDims)}
     * overload above so the author always sees the deterministic base, never
     * the random-pick view.
     *
     * <p>This 6-arg overload runs blocks AND entities together — kept for any
     * caller that needs the legacy combined pass. The train-spawn path uses
     * the split {@link #placeBlocksOnly} / {@link #placeEntitiesOnly} pair
     * instead, so the entity portion can be deferred until the carriage's
     * collision tracker confirms it's settled in place.</p>
     */
    public static void placeAt(ServerLevel level, BlockPos carriageOrigin, CarriageContents contents,
                               CarriageDims dims, long seed, int carriageIndex) {
        placeAtInternal(level, carriageOrigin, contents, dims, seed, carriageIndex, /*placeBlocks*/ true, /*spawnEntities*/ true);
        applyVariantBlocks(level, carriageOrigin, contents, dims, seed, carriageIndex);
        applyContentPools(level, carriageOrigin, contents, dims, seed, carriageIndex);
    }

    /**
     * Sentinel passed as {@code carriagePIdx} when the spawn context is the
     * editor / template preview rather than a real train carriage. Entities
     * tagged with this pIdx are still tagged so the diagnostics subscriber
     * can see them, but the value signals "not a train entity, ignore
     * placement-tracker correlation logic."
     */
    public static final int EDITOR_SENTINEL_PIDX = -1;

    /**
     * Train-spawn helper — stamp the contents BLOCKS at {@code carriageOrigin}
     * but skip the entity-spawn pass. Pair with {@link #placeEntitiesOnly}
     * fired after the placement-collision tracker confirms the carriage has
     * stopped shifting.
     *
     * <p>Why split: when a runtime-spawned carriage overlaps an existing
     * sibling, the appender's {@code runPlacementCollisionTracker} nudges its
     * {@code spawnWorldPos} every tick for up to {@code CLEAN_TICKS_FOR_SUCCESS}
     * ticks until placement is clean. The blocks live in shipyard chunks and
     * track that shift transparently; entities, by contrast, are added to the
     * level via {@code level.addFreshEntity()} and their attachment to the
     * ship is mediated by VS's shipyard-entity mixin — that mixin's timing
     * relative to the placement-tracker shifts is the suspected root cause of
     * the "entities stop showing up after a while" bug. Spawning entities
     * after the shifts complete eliminates that timing race entirely.</p>
     */
    public static void placeBlocksOnly(ServerLevel level, BlockPos carriageOrigin, CarriageContents contents,
                                        CarriageDims dims, long seed, int carriageIndex) {
        placeAtInternal(level, carriageOrigin, contents, dims, seed, carriageIndex, /*placeBlocks*/ true, /*spawnEntities*/ false);
        applyVariantBlocks(level, carriageOrigin, contents, dims, seed, carriageIndex);
        applyContentPools(level, carriageOrigin, contents, dims, seed, carriageIndex);
    }

    /**
     * Train-spawn helper — spawn ONLY the entities from the contents template
     * at {@code carriageOrigin}, without re-stamping blocks. Looks up the same
     * {@code (contents, dims)} template that {@link #placeBlocksOnly} used so
     * entity positions match the blocks the player can see. Tags every
     * spawned entity with {@link #contentsTagFor}{@code (carriagePIdx)} +
     * persistence flag + NBT spawn-coords for diagnostics.
     *
     * <p>Called by the placement-collision tracker once the carriage has
     * accumulated {@code CLEAN_TICKS_FOR_SUCCESS} consecutive collision-free
     * ticks (i.e. all shifts are done). Each pending spawn fires exactly
     * once — the appender clears the pending record before this call so a
     * double-shift can't double-spawn.</p>
     */
    public static void placeEntitiesOnly(ServerLevel level, BlockPos carriageOrigin, CarriageContents contents,
                                          CarriageDims dims, long seed, int carriagePIdx) {
        placeAtInternal(level, carriageOrigin, contents, dims, seed, carriagePIdx, /*placeBlocks*/ false, /*spawnEntities*/ true);
    }

    /**
     * Shared internal that resolves the template once and runs whichever of
     * {blocks, entities} the caller asked for. Centralising the template
     * lookup ensures {@link #placeBlocksOnly} and {@link #placeEntitiesOnly}
     * always agree on which template was used — they share the same
     * deterministic {@link CarriageContentsStore#get} result so entity local
     * coords match the placed blocks.
     */
    private static void placeAtInternal(ServerLevel level, BlockPos carriageOrigin, CarriageContents contents,
                                         CarriageDims dims, long seed, int carriagePIdx,
                                         boolean placeBlocks, boolean spawnEntities) {
        Vec3i size = interiorSize(dims);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            // Carriage at its minimum dims has zero or negative interior
            // along at least one axis — no room for contents.
            return;
        }
        BlockPos origin = interiorOrigin(carriageOrigin);

        Optional<StructureTemplate> stored = CarriageContentsStore.get(level, contents, size);
        if (stored.isPresent()) {
            StructureTemplate template = stored.get();
            if (placeBlocks) {
                stampTemplateBlocks(level, origin, template);
                // Narrative lecterns must spawn EMPTY so they resolve their book
                // lazily on first right-click (via BookFactory.buildOrRandomForLectern)
                // instead of showing a book baked into the template. Some carriage
                // templates (campire/fireside) were mis-authored with a resolved pip
                // book in the lectern's block-entity; strip any such book so every
                // narrative lectern starts virgin and the per-world picker runs.
                clearBakedNarrativeLecternBooks(level, origin, size);
            }
            if (spawnEntities) {
                spawnEntitiesFromTemplate(level, origin, template, carriagePIdx, contents, size, seed);
                // Cell-link entity spawn — mirrors the block-side
                // applyContentPools pass. Lets a player who placed a prefab
                // armor stand in the editor without saving the template still
                // get the stand at runtime: the link in ContainerContentsStore
                // drives the entity spawn directly.
                spawnLinkedEntities(level, origin, contents, carriagePIdx, seed);
                // Mob-variant entity spawn — re-rolls the variant sidecar
                // and spawns mobs at cells whose pick has entityId != null.
                // Block pass already AIRed those cells via the existing
                // empty-placeholder branch.
                spawnVariantMobsForContents(level, origin, contents, dims, seed, carriagePIdx);
            }
            if (placeBlocks) {
                LOGGER.info("[DungeonTrain] Placed contents {} at {} source=stored pIdx={} mode={}{}",
                    contents.id(), origin, carriagePIdx,
                    placeBlocks ? "blocks" : "",
                    spawnEntities ? "+entities" : "");
            }
            return;
        }
        if (contents instanceof CarriageContents.Builtin b && b.type() == ContentsType.DEFAULT) {
            if (placeBlocks) {
                legacyPlaceDefault(level, origin, size);
                LOGGER.info("[DungeonTrain] Placed contents {} at {} source=legacy pIdx={}",
                    contents.id(), origin, carriagePIdx);
            }
            // No entities in the legacy fallback — placeEntitiesOnly is a no-op
            // for a contents-pick that resolves to the hardcoded default.
            return;
        }
        if (placeBlocks) {
            LOGGER.warn("[DungeonTrain] No contents placed — contents={} origin={} reason=no-nbt-no-fallback. Check {} exists and matches interior size {}x{}x{}.",
                contents.id(), origin, CarriageContentsStore.fileFor(contents),
                size.getX(), size.getY(), size.getZ());
        }
    }

    /**
     * Force every {@code dungeontrain:narrative_lectern} in the just-placed
     * interior to spawn with an EMPTY block-entity, so it resolves its book
     * lazily on first right-click instead of showing a book baked into the
     * template. Defends against carriage templates saved in the editor with a
     * resolved lectern book (the "always pip" bug — campire/fireside shipped
     * with a pip book baked into the lectern BE).
     */
    private static void clearBakedNarrativeLecternBooks(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!(level.getBlockState(cursor).getBlock() instanceof NarrativeLecternBlock)) {
                        continue;
                    }
                    if (level.getBlockEntity(cursor) instanceof LecternBlockEntity lectern
                            && !lectern.getBook().isEmpty()) {
                        lectern.setBook(ItemStack.EMPTY);
                        lectern.setChanged();
                    }
                }
            }
        }
    }

    /**
     * Overlay any {@link CarriageContentsVariantBlocks} entries for
     * {@code contents} onto the stamped interior. Each picked
     * {@link VariantState} is silent-placed at {@code interiorOrigin + localPos}
     * with the optional block-entity NBT applied. The empty-placeholder
     * sentinel resolves to AIR so authors can randomise "block or empty"
     * positions inside an interior.
     */
    private static void applyVariantBlocks(ServerLevel level, BlockPos carriageOrigin,
                                            CarriageContents contents, CarriageDims dims,
                                            long seed, int carriageIndex) {
        Vec3i size = interiorSize(dims);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;
        CarriageContentsVariantBlocks sidecar = CarriageContentsVariantBlocks.loadFor(contents, size);
        if (sidecar.isEmpty()) return;

        BlockPos origin = interiorOrigin(carriageOrigin);
        for (var entry : sidecar.entries()) {
            VariantState picked = sidecar.resolve(entry.localPos(), seed, carriageIndex);
            if (picked == null) continue;
            BlockPos world = origin.offset(entry.localPos());
            if (CarriageVariantBlocks.isEmptyPlaceholder(picked.state())) {
                SilentBlockOps.setBlockSilent(level, world, Blocks.AIR.defaultBlockState());
            } else {
                net.minecraft.world.level.block.state.BlockState rotated =
                    games.brennan.dungeontrain.editor.RotationApplier.apply(
                        picked.state(), picked.rotation(), picked.half(),
                        entry.localPos(), seed, carriageIndex,
                        sidecar.lockIdAt(entry.localPos()));
                // First-band starter loot: swap rich loot/loot_irongold chests for the starter
                // prefab while in the peaceful opening band. Skip the player scan for non-chest
                // cells (null id) and never downgrade editor previews (sentinel pIdx).
                String lootId = picked.linkedLootPrefabId();
                if (lootId != null && carriageIndex != EDITOR_SENTINEL_PIDX) {
                    lootId = DifficultyProgression.effectiveLootPrefabId(level, lootId);
                }
                games.brennan.dungeontrain.editor.ContainerContentsPlacement.place(
                    level, world, rotated, picked.blockEntityNbt(),
                    "contents:" + contents.id(), entry.localPos(), seed, carriageIndex,
                    lootId);
            }
        }
    }

    /**
     * Apply any {@link games.brennan.dungeontrain.editor.ContainerContentsStore}
     * pool at a cell that the static template stamped (rather than the variant
     * sidecar). The pool can be either a per-cell LINK to a {@code LootPrefabStore}
     * template ("this cell uses prefab X") or a LOCAL pool authored directly in
     * the C-menu. {@link games.brennan.dungeontrain.editor.ContainerContentsStore#poolAt}
     * read-throughs the link; either way we end up rolling the right entries.
     *
     * <p>For cells that also appear in the variants sidecar, the variant flow
     * already rolls; this pass covers cells the variant flow didn't touch —
     * static-template chests/furnaces/barrels whose contents are authored in
     * the C-menu without a block-variant override.</p>
     *
     * <p>Runs after {@link #applyVariantBlocks} so variant-overlaid cells aren't
     * touched twice (the pool read-through would otherwise re-roll into a
     * variant cell that already has its own roll).</p>
     */
    private static void applyContentPools(ServerLevel level, BlockPos carriageOrigin,
                                          CarriageContents contents, CarriageDims dims,
                                          long seed, int carriageIndex) {
        String plotKey = "contents:" + contents.id();
        games.brennan.dungeontrain.editor.ContainerContentsStore store =
            games.brennan.dungeontrain.editor.ContainerContentsStore.loadFor(plotKey);
        BlockPos origin = interiorOrigin(carriageOrigin);
        // Skip cells already handled by a variant entry — the variant flow
        // already rolled into them.
        Vec3i size = interiorSize(dims);
        games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks variants =
            games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks.loadFor(contents, size);
        java.util.Set<BlockPos> variantPositions = new java.util.HashSet<>();
        if (!variants.isEmpty()) {
            for (var entry : variants.entries()) variantPositions.add(entry.localPos());
        }
        for (BlockPos localPos : store.allPositions()) {
            if (variantPositions.contains(localPos)) continue;
            // Single read-through — store.poolAt resolves a link if present,
            // else returns the local pool, else empty. Empty → skip.
            games.brennan.dungeontrain.editor.ContainerContentsPool pool = store.poolAt(localPos);
            if (pool.isEmpty()) continue;
            BlockPos worldPos = origin.offset(localPos);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(worldPos);
            if (!state.hasBlockEntity()) continue;
            if (!games.brennan.dungeontrain.editor.ContainerContentsRoller.isContainerState(state)
                && !games.brennan.dungeontrain.editor.ContainerContentsRoller.isDecoratedPot(state)) continue;
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(worldPos);
            if (be == null) continue;
            net.minecraft.nbt.CompoundTag baseNbt = be.saveWithFullMetadata(level.registryAccess());
            net.minecraft.nbt.CompoundTag rolled = games.brennan.dungeontrain.editor.ContainerContentsRoller.roll(
                pool, state, localPos, seed, carriageIndex, baseNbt,
                level.registryAccess(), level);
            if (rolled == null) continue;
            be.loadCustomOnly(rolled, level.registryAccess());
            be.setChanged();
        }
    }

    /**
     * Hardcoded fallback for the {@code default} built-in when no NBT is on
     * disk: a single {@link Blocks#STONE_PRESSURE_PLATE} at the floor centre
     * of the interior volume.
     *
     * <p>Pressure plates need a supporting block below them; the shell floor
     * at {@code interiorOrigin.below()} always provides it (the shell's floor
     * row is stamped by the shell template before contents are placed).</p>
     */
    private static void legacyPlaceDefault(ServerLevel level, BlockPos interiorOrigin, Vec3i interiorSize) {
        BlockState plate = Blocks.STONE_PRESSURE_PLATE.defaultBlockState();
        int dx = interiorSize.getX() / 2;
        int dz = interiorSize.getZ() / 2;
        BlockPos pos = interiorOrigin.offset(dx, 0, dz);
        SilentBlockOps.setBlockSilent(level, pos, plate);
    }

    /**
     * Stamp ONLY the blocks from {@code template} into the level at
     * {@code origin}. Entity spawning is the caller's responsibility — see
     * {@link #spawnEntitiesFromTemplate}.
     *
     * <p>StructureTemplate's built-in entity placement was unreliable in
     * shipyard chunks (possibly due to VS's entity section mixin timing), so
     * we always parse the entity list ourselves. {@code setIgnoreEntities(true)}
     * tells {@code placeInWorld} to skip its own entity pass.</p>
     */
    private static void stampTemplateBlocks(ServerLevel level, BlockPos origin, StructureTemplate template) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
    }

    /**
     * Manually iterate the template's saved entity list and spawn each entity
     * at {@code origin + localPos}. Bypasses {@link StructureTemplate}'s
     * built-in entity placement because it proved unreliable at shipyard
     * coordinates. Each entity's UUID is cleared before deserialisation so MC
     * assigns a fresh one — otherwise every carriage would try to spawn the
     * same-UUID armor stand and all but the first would drop silently.
     *
     * <p>Every spawned entity gets:
     * <ul>
     *   <li>The tag {@code dungeontrain_contents_pidx_<carriagePIdx>} so the
     *       diagnostics subscriber can identify it across save/load and
     *       chunk reload.</li>
     *   <li>NBT fields recording the original spawn shipyard coords + game
     *       tick, so the per-20-tick position sampler can detect drift.</li>
     *   <li>{@code PersistenceRequired=true} on {@code Mob} instances so the
     *       vanilla despawn rules don't silently remove animals/villagers
     *       inside shipyard chunks (despawn caps look at world-loaded mobs,
     *       which inside-the-ship entities count toward by accident).</li>
     * </ul>
     */
    private static void spawnEntitiesFromTemplate(ServerLevel level, BlockPos origin, StructureTemplate template,
                                                   int carriagePIdx, CarriageContents contents, Vec3i interiorSize,
                                                   long seed) {
        CompoundTag saved = template.save(new CompoundTag());
        if (!saved.contains("entities", Tag.TAG_LIST)) {
            return;
        }
        ListTag entries = saved.getList("entities", Tag.TAG_COMPOUND);
        if (entries.isEmpty()) return;
        long spawnTick = level.getGameTime();
        String tag = contentsTagFor(carriagePIdx);
        // Variant sidecar drives armor stand / item frame equipment via the
        // same linkedLootPrefabId machinery as block-side container loot.
        // loadFor is cached + size-validated; null result means no variants
        // for this contents id — the applicator no-ops on null/empty.
        CarriageContentsVariantBlocks variantSidecar =
            CarriageContentsVariantBlocks.loadFor(contents, interiorSize);
        // Cell-level link / authored pool — populated when the player edits
        // an armor stand or item frame via the C menu (the menu writes to
        // this store, keyed by interior-local block pos). Mirrors the block-
        // side applyContentPools pass.
        ContainerContentsStore contentsLinkStore =
            ContainerContentsStore.loadFor("contents:" + contents.id());
        int spawned = 0;
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.contains("nbt", Tag.TAG_COMPOUND) || !entry.contains("pos", Tag.TAG_LIST)) continue;
            ListTag posList = entry.getList("pos", Tag.TAG_DOUBLE);
            if (posList.size() != 3) continue;
            double localX = posList.getDouble(0);
            double localY = posList.getDouble(1);
            double localZ = posList.getDouble(2);
            double worldX = origin.getX() + localX;
            double worldY = origin.getY() + localY;
            double worldZ = origin.getZ() + localZ;

            CompoundTag entityNbt = entry.getCompound("nbt").copy();
            // Fresh UUID so spawning the same template at N carriages doesn't
            // collide on the UUID index (MC silently drops duplicate UUIDs).
            entityNbt.putUUID("UUID", UUID.randomUUID());
            // Rewrite the Pos field to the new world coordinates. Paintings /
            // item frames also use this field for their hanging position.
            ListTag newPos = new ListTag();
            newPos.add(DoubleTag.valueOf(worldX));
            newPos.add(DoubleTag.valueOf(worldY));
            newPos.add(DoubleTag.valueOf(worldZ));
            entityNbt.put("Pos", newPos);
            // For hanging entities (paintings, item frames) the TileX/Y/Z fields
            // anchor the attached block. Offset those too if present.
            if (entityNbt.contains("TileX", Tag.TAG_INT) && entry.contains("blockPos", Tag.TAG_INT_ARRAY)) {
                int[] localBlock = entry.getIntArray("blockPos");
                if (localBlock.length == 3) {
                    entityNbt.putInt("TileX", origin.getX() + localBlock[0]);
                    entityNbt.putInt("TileY", origin.getY() + localBlock[1]);
                    entityNbt.putInt("TileZ", origin.getZ() + localBlock[2]);
                }
            }
            // Persistent vanilla tag — survives save/load and chunk reload.
            // The CompoundTag "Tags" list is the NBT-side form of
            // {@code Entity.getTags()} and is read back by Entity.load.
            ListTag tagList = entityNbt.contains("Tags", Tag.TAG_LIST)
                ? entityNbt.getList("Tags", Tag.TAG_STRING)
                : new ListTag();
            tagList.add(net.minecraft.nbt.StringTag.valueOf(tag));
            entityNbt.put("Tags", tagList);
            // Force-persistence on mobs so vanilla despawn (which kicks in
            // for animals/villagers under certain conditions) can't silently
            // eat our carriage entities.
            entityNbt.putBoolean("PersistenceRequired", true);

            // Variant-driven equipment for armor stands / item frames. Looks
            // up the variant cell at the entity's interior-local block pos
            // (floored from the float Pos) and, if the picked variant has a
            // linkedLootPrefabId, rolls the prefab into the entity's
            // ArmorItems/HandItems (stand) or Item (frame) before
            // EntityType.create so the entity is born equipped. No-op when
            // the sidecar has no entry at the position, no prefab link, or
            // the entity isn't a supported type.
            BlockPos localBlock = BlockPos.containing(localX, localY, localZ);
            EntityVariantApplicator.applyTo(entityNbt, localBlock, seed, carriagePIdx,
                variantSidecar, contentsLinkStore, level);

            try {
                Optional<Entity> created = EntityType.create(entityNbt, level);
                if (created.isEmpty()) {
                    LOGGER.warn("[DungeonTrain] Contents: failed to create entity from nbt (id={})",
                        entityNbt.getString("id"));
                    continue;
                }
                Entity entity = created.get();
                // First-band easy mobs: replace a (rare) baked hostile with a small slime/magma
                // cube while in the opening raw-tier-0 band; otherwise spawn it as authored.
                if (trySpawnFirstBandSubstitute(level, entity, new Vec3(worldX, worldY, worldZ), carriagePIdx)) continue;
                entity.moveTo(worldX, worldY, worldZ, entity.getYRot(), entity.getXRot());
                // Diagnostic spawn-coords + tick on the persistent-data
                // subtree (the standard cross-mod-safe location for custom
                // per-entity state — survives save/load via NeoForge's
                // entity-save mixin). Written AFTER EntityType.create so it
                // doesn't conflict with vanilla NBT field handling.
                CompoundTag persistent = entity.getPersistentData();
                persistent.putDouble(NBT_SPAWN_SHIPYARD_X, worldX);
                persistent.putDouble(NBT_SPAWN_SHIPYARD_Y, worldY);
                persistent.putDouble(NBT_SPAWN_SHIPYARD_Z, worldZ);
                persistent.putLong(NBT_SPAWN_GAME_TICK, spawnTick);
                persistent.putInt(NBT_SPAWN_CARRIAGE_PIDX, carriagePIdx);
                // Belt + braces for PersistenceRequired — the NBT-time set
                // covers EntityType.create's path, this covers Mob subclasses
                // that override readAdditional and might re-read the flag.
                if (entity instanceof Mob mob) {
                    mob.setPersistenceRequired();
                }
                if (level.addFreshEntity(entity)) {
                    spawned++;
                    // Per-entity verbose log gated on the diagnostic flag —
                    // bounded log volume in production, full detail when
                    // investigating disappearance regressions.
                    //
                    // Logs both the REQUESTED spawn position (worldX/Y/Z —
                    // the coords we asked vanilla to place the entity at)
                    // and the ACTUAL post-add position read off the entity.
                    // A non-zero delta on the same line means vanilla's
                    // "no entity inside solid block" resolution displaced
                    // the mob synchronously inside {@code addFreshEntity} —
                    // the spawn target was clipping carriage geometry.
                    if (DebugFlags.logContentsEntities()) {
                        double dx0 = entity.getX() - worldX;
                        double dy0 = entity.getY() - worldY;
                        double dz0 = entity.getZ() - worldZ;
                        LOGGER.info("[DungeonTrain] Contents: spawned entity type={} uuid={} reqPos=({},{},{}) actualPos=({},{},{}) delta=({},{},{}) pIdx={} tag={}",
                            entity.getType().getDescriptionId(), entity.getUUID(),
                            String.format("%.3f", worldX),
                            String.format("%.3f", worldY),
                            String.format("%.3f", worldZ),
                            String.format("%.3f", entity.getX()),
                            String.format("%.3f", entity.getY()),
                            String.format("%.3f", entity.getZ()),
                            String.format("%+.3f", dx0),
                            String.format("%+.3f", dy0),
                            String.format("%+.3f", dz0),
                            carriagePIdx, tag);
                        // Register for per-tick drift sampling at +1, +5,
                        // +20, +60 elapsed ticks. Bounded lifetime; the
                        // tracker self-evicts after the final milestone.
                        TrainCarriageAppender.trackEntityDrift(
                            entity.getUUID(), spawnTick,
                            worldX, worldY, worldZ, carriagePIdx);
                    }
                } else {
                    // Always log rejections — those are real failures, not
                    // routine bookkeeping.
                    LOGGER.warn("[DungeonTrain] Contents: addFreshEntity rejected {} at ({},{},{}) pIdx={}",
                        entity.getType().getDescriptionId(), worldX, worldY, worldZ, carriagePIdx);
                }
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Contents: entity spawn threw for id={} pIdx={}: {}",
                    entityNbt.getString("id"), carriagePIdx, t.toString());
            }
        }
        if (spawned > 0) {
            LOGGER.info("[DungeonTrain] Contents: spawned {} entities at origin={} (template listed {}) pIdx={} tag={}",
                spawned, origin, entries.size(), carriagePIdx, tag);
        }
    }

    /**
     * Cell-link entity spawn pass — mirror of
     * {@link #applyContentPools} for entities. Iterates every cell in the
     * carriage's {@link ContainerContentsStore}; for each link whose prefab
     * has {@code category="armor_stand"}, spawns a fresh armor stand at the
     * cell's world position and applies the rolled equipment via
     * {@link EntityVariantApplicator#applyPoolToLiveEntity}.
     *
     * <p>Skips cells that already host an armor stand (template-captured
     * entity from a saved template wins the dedup). Item frames are
     * deferred — they need facing data we don't currently persist in the
     * store.</p>
     *
     * <p>Runs every carriage spawn (and every editor preview re-stamp) so a
     * link → entity association at the cell is the source of truth, the
     * same way `applyContentPools` makes the pool → chest-loot association
     * the source of truth on the block side.</p>
     */
    private static void spawnLinkedEntities(ServerLevel level, BlockPos interiorOrigin,
                                             CarriageContents contents,
                                             int carriagePIdx, long seed) {
        ContainerContentsStore store = ContainerContentsStore.loadFor("contents:" + contents.id());
        java.util.Set<BlockPos> allPositions = store.allPositions();
        if (allPositions.isEmpty()) return;

        net.minecraft.core.HolderLookup.Provider registries = level.registryAccess();
        String tag = contentsTagFor(carriagePIdx);
        long spawnTick = level.getGameTime();
        int spawned = 0;

        for (BlockPos localPos : allPositions) {
            String linkId = store.linkAt(localPos);
            if (linkId == null) continue;
            Optional<LootPrefabStore.Data> loaded = LootPrefabStore.load(linkId);
            if (loaded.isEmpty()) continue;
            LootPrefabStore.Data data = loaded.get();
            if (!LootPrefabStore.CATEGORY_ARMOR_STAND.equals(data.category())) continue;
            ContainerContentsPool pool = data.pool();
            if (pool == null || pool.isEmpty()) continue;

            BlockPos worldPos = interiorOrigin.offset(localPos);
            // Dedup: skip if a captured stand from the template was already
            // placed at this cell. spawnEntitiesFromTemplate runs first, so
            // captured entities are in the level by the time we get here.
            AABB cellAabb = new AABB(worldPos);
            if (!level.getEntitiesOfClass(ArmorStand.class, cellAabb).isEmpty()) continue;

            Vec3 spawnPos = Vec3.atBottomCenterOf(worldPos);
            ArmorStand stand = new ArmorStand(level, spawnPos.x, spawnPos.y, spawnPos.z);
            stand.addTag(tag);
            CompoundTag persistent = stand.getPersistentData();
            persistent.putDouble(NBT_SPAWN_SHIPYARD_X, spawnPos.x);
            persistent.putDouble(NBT_SPAWN_SHIPYARD_Y, spawnPos.y);
            persistent.putDouble(NBT_SPAWN_SHIPYARD_Z, spawnPos.z);
            persistent.putLong(NBT_SPAWN_GAME_TICK, spawnTick);
            persistent.putInt(NBT_SPAWN_CARRIAGE_PIDX, carriagePIdx);

            if (!level.addFreshEntity(stand)) {
                LOGGER.warn("[DungeonTrain] LinkedEntities: addFreshEntity rejected armor_stand at {} pIdx={} link={}",
                    worldPos, carriagePIdx, linkId);
                continue;
            }
            EntityVariantApplicator.applyPoolToLiveEntity(stand, pool, seed, carriagePIdx, registries);
            spawned++;
        }
        if (spawned > 0) {
            LOGGER.info("[DungeonTrain] LinkedEntities: spawned {} armor stand(s) for contents={} pIdx={} tag={}",
                spawned, contents.id(), carriagePIdx, tag);
        }
    }

    /**
     * Entity types that become a small <b>magma cube</b> (instead of a small slime) when
     * substituted in the first band — nether mobs and pillager/illager raiders. Backed by the
     * {@code dungeontrain:first_band_magma_mobs} data tag (which includes
     * {@code #minecraft:raiders}), so the roster is tunable without recompiling.
     */
    private static final TagKey<EntityType<?>> FIRST_BAND_MAGMA_MOBS =
        TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "first_band_magma_mobs"));

    /**
     * First-band mob substitution. When {@link DifficultyProgression#firstLevelEasyMobs} holds
     * (config on + the run still in raw tier 0) and {@code original} is hostile ({@link Enemy}),
     * spawns a small Slime — or a small Magma Cube when {@code original}'s type is in
     * {@link #FIRST_BAND_MAGMA_MOBS} — at {@code pos}, tagged + persisted exactly like a
     * carriage-contents mob, and returns {@code true} so the caller skips the original. Returns
     * {@code false} (caller spawns the original as authored) for editor previews (sentinel pIdx),
     * non-hostile mobs, or when not in the easy-mobs band.
     */
    private static boolean trySpawnFirstBandSubstitute(ServerLevel level, Entity original,
                                                       Vec3 pos, int carriagePIdx) {
        if (carriagePIdx == EDITOR_SENTINEL_PIDX) return false;
        if (!(original instanceof Enemy)) return false;
        if (!DifficultyProgression.firstLevelEasyMobs(level)) return false;

        Slime sub = original.getType().builtInRegistryHolder().is(FIRST_BAND_MAGMA_MOBS)
            ? EntityType.MAGMA_CUBE.create(level)
            : EntityType.SLIME.create(level);
        if (sub == null) return true; // creation failed — still suppress the original hostile
        // "small or next size up" — size 1 or 2.
        sub.setSize(1 + level.getRandom().nextInt(2), true);
        sub.setUUID(UUID.randomUUID());
        sub.moveTo(pos.x, pos.y, pos.z, level.getRandom().nextFloat() * 360.0f, 0.0f);
        sub.addTag(contentsTagFor(carriagePIdx));
        CompoundTag persistent = sub.getPersistentData();
        persistent.putDouble(NBT_SPAWN_SHIPYARD_X, pos.x);
        persistent.putDouble(NBT_SPAWN_SHIPYARD_Y, pos.y);
        persistent.putDouble(NBT_SPAWN_SHIPYARD_Z, pos.z);
        persistent.putLong(NBT_SPAWN_GAME_TICK, level.getGameTime());
        persistent.putInt(NBT_SPAWN_CARRIAGE_PIDX, carriagePIdx);
        sub.setPersistenceRequired();
        if (!level.addFreshEntity(sub)) {
            LOGGER.warn("[DungeonTrain] First-band substitute: addFreshEntity rejected {} at {} pIdx={}",
                sub.getType().getDescriptionId(), pos, carriagePIdx);
        }
        return true;
    }

    /**
     * Mob-variant entity-pass for a carriage's interior. Re-rolls the
     * contents variant sidecar with the same {@code (seed, carriagePIdx)}
     * the block pass used and spawns a mob at every cell whose pick has
     * {@code entityId != null}.
     *
     * <p>The block pass already cleared those cells to AIR through the
     * existing empty-placeholder sentinel branch (the canonical
     * {@link VariantState} constructor force-stamps the COMMAND_BLOCK
     * sentinel onto mob entries), so this pass only spawns entities — it
     * never touches blocks.</p>
     *
     * <p>Spawn position is the bottom-centre of the cell. Subject to the
     * existing 48-block player-distance gate that wraps the entity pass.</p>
     */
    private static void spawnVariantMobsForContents(ServerLevel level, BlockPos carriageOrigin,
                                                     CarriageContents contents, CarriageDims dims,
                                                     long seed, int carriagePIdx) {
        // Editor preview / template-load path uses EDITOR_SENTINEL_PIDX —
        // skip variant mob spawning so authoring doesn't drop random live
        // mobs into the editor plot every time the template is restamped.
        // Template-baked entities (armor stands etc. captured into the
        // .nbt file) still spawn here via spawnEntitiesFromTemplate; only
        // the stochastic per-spawn variant rolls are suppressed.
        if (carriagePIdx == EDITOR_SENTINEL_PIDX) return;
        Vec3i size = interiorSize(dims);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;
        CarriageContentsVariantBlocks sidecar = CarriageContentsVariantBlocks.loadFor(contents, size);
        if (sidecar.isEmpty()) return;
        BlockPos origin = interiorOrigin(carriageOrigin);
        int spawned = 0;
        for (var entry : sidecar.entries()) {
            VariantState picked = sidecar.resolve(entry.localPos(), seed, carriagePIdx);
            if (picked == null || !picked.isMob()) continue;
            BlockPos world = origin.offset(entry.localPos());
            if (spawnVariantMob(level, world, picked, carriagePIdx)) spawned++;
        }
        if (spawned > 0) {
            LOGGER.info("[DungeonTrain] Mob-variant: spawned {} mobs for contents={} pIdx={} seed={}",
                spawned, contents.id(), carriagePIdx, seed);
        }
    }

    /**
     * Spawn a single mob at {@code worldPos} from a mob-variant pick.
     * Reuses the same tag / persistence / NBT-stamping pattern as
     * {@link #spawnEntitiesFromTemplate} so mobs from variant rolls are
     * indistinguishable from template-baked entities for the diagnostics
     * subscriber and despawn rules.
     *
     * @param level        Target level.
     * @param worldPos     Cell world position; the mob spawns at the cell's
     *                     bottom-centre.
     * @param picked       Mob variant entry (must satisfy {@link VariantState#isMob}).
     * @param carriagePIdx Carriage's pIdx for tag + diagnostics.
     * @return {@code true} when the mob was successfully added to the
     *         level; {@code false} on registry-resolve failure or
     *         {@code addFreshEntity} rejection.
     */
    public static boolean spawnVariantMob(ServerLevel level, BlockPos worldPos,
                                           VariantState picked, int carriagePIdx) {
        if (picked == null || !picked.isMob()) return false;
        Optional<EntityType<?>> typeOpt = EntityType.byString(picked.entityId().toString());
        if (typeOpt.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Mob-variant: unknown entity id '{}' at {} pIdx={} — skipping.",
                picked.entityId(), worldPos, carriagePIdx);
            return false;
        }
        EntityType<?> type = typeOpt.get();
        Entity entity;
        try {
            CompoundTag mobNbt = picked.blockEntityNbt();
            if (mobNbt != null) {
                CompoundTag spawnNbt = mobNbt.copy();
                spawnNbt.putString("id", picked.entityId().toString());
                Optional<Entity> created = EntityType.create(spawnNbt, level);
                if (created.isEmpty()) {
                    LOGGER.warn("[DungeonTrain] Mob-variant: EntityType.create failed for {} at {} pIdx={}",
                        picked.entityId(), worldPos, carriagePIdx);
                    return false;
                }
                entity = created.get();
            } else {
                entity = type.create(level);
                if (entity == null) {
                    LOGGER.warn("[DungeonTrain] Mob-variant: type.create returned null for {} at {} pIdx={}",
                        picked.entityId(), worldPos, carriagePIdx);
                    return false;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Mob-variant: spawn threw for {} at {} pIdx={}: {}",
                picked.entityId(), worldPos, carriagePIdx, t.toString());
            return false;
        }
        // First-band easy mobs: replace an authored hostile with a small slime (magma cube for
        // nether/raider mobs) while in the opening raw-tier-0 band; no-op otherwise. The
        // substitute is spawned + tagged inside the helper, so we early-return here.
        if (trySpawnFirstBandSubstitute(level, entity, Vec3.atBottomCenterOf(worldPos), carriagePIdx)) return true;
        // Fresh UUID so the same template at multiple carriages doesn't
        // collide on the UUID index (MC silently drops duplicate UUIDs).
        entity.setUUID(UUID.randomUUID());
        // Spawn at cell bottom-centre — matches the mob's footprint to the
        // cell so it doesn't clip the floor / walls.
        Vec3 pos = Vec3.atBottomCenterOf(worldPos);
        // Y-rot: zero by default. Future: reuse VariantRotation.dirMask to
        // pick a Direction and convert via Direction.toYRot.
        entity.moveTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);

        String tag = contentsTagFor(carriagePIdx);
        entity.addTag(tag);
        CompoundTag persistent = entity.getPersistentData();
        persistent.putDouble(NBT_SPAWN_SHIPYARD_X, pos.x);
        persistent.putDouble(NBT_SPAWN_SHIPYARD_Y, pos.y);
        persistent.putDouble(NBT_SPAWN_SHIPYARD_Z, pos.z);
        persistent.putLong(NBT_SPAWN_GAME_TICK, level.getGameTime());
        persistent.putInt(NBT_SPAWN_CARRIAGE_PIDX, carriagePIdx);

        // Slimes/magma cubes: the variant path skips Mob#finalizeSpawn (where
        // vanilla rolls slime size), so without this every variant slime spawns
        // at the default size 1. Roll a uniform random size 1–4 unless the
        // variant author baked an explicit Size into the egg NBT (anvil-renamed
        // / EntityTag egg) — then respect it.
        if (entity instanceof Slime slime) {
            CompoundTag mobNbt = picked.blockEntityNbt();
            boolean authoredSize = mobNbt != null && mobNbt.contains("Size");
            if (!authoredSize) {
                int size = 1 + level.getRandom().nextInt(4); // 1, 2, 3, or 4
                slime.setSize(size, true); // resizes bbox + resets health/damage
            }
        }
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        if (!level.addFreshEntity(entity)) {
            LOGGER.warn("[DungeonTrain] Mob-variant: addFreshEntity rejected {} at {} pIdx={}",
                picked.entityId(), worldPos, carriagePIdx);
            return false;
        }
        return true;
    }

    /**
     * Capture the interior volume at {@code carriageOrigin} into a fresh
     * {@link StructureTemplate} — used by the contents editor save flow.
     * Includes entities (armor stands, paintings, item frames, etc.) so a
     * decorated interior round-trips through save/load. Air positions are
     * excluded so the saved NBT only describes placed blocks. An all-air
     * interior with no entities still saves as a zero-size template, which
     * {@link CarriageContentsStore#save} writes so the author can clear
     * contents explicitly.
     *
     * <p>The 4th {@code fillFromWorld} argument is {@code includeEntities}
     * in 1.20.1 — passing {@code true} is what pulls entities in the AABB
     * into the template's {@code StructureEntityInfo} list.</p>
     */
    public static StructureTemplate captureTemplate(ServerLevel level, BlockPos carriageOrigin, CarriageDims dims) {
        Vec3i size = interiorSize(dims);
        // Strip dropped-item entities from the capture region first. Door /
        // parts overlay re-stamps can break supporting blocks underneath
        // buttons (or other small attachments) during edit sessions, leaving
        // the dropped-item form drifting on the floor; capturing those into
        // the saved template bakes phantom items into every future spawn of
        // this carriage's contents. Other entities (armor stands, paintings,
        // item frames) are kept — they're legitimate decoration the author
        // placed and wants to round-trip through save/load.
        discardDroppedItemsIn(level, interiorOrigin(carriageOrigin), size);
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, interiorOrigin(carriageOrigin), size, true, Blocks.AIR);
        return template;
    }

    /**
     * Remove every {@link net.minecraft.world.entity.item.ItemEntity}
     * (a dropped item floating in the world) whose bounding box overlaps
     * the given region. Leaves armor stands, item frames, paintings, and
     * other non-item entities alone — those are the legitimate decoration
     * the author wants captured.
     */
    private static void discardDroppedItemsIn(ServerLevel level, BlockPos origin, Vec3i size) {
        AABB box = new AABB(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX(),
            origin.getY() + size.getY(),
            origin.getZ() + size.getZ()
        );
        List<net.minecraft.world.entity.item.ItemEntity> drops =
            level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box);
        for (net.minecraft.world.entity.item.ItemEntity item : drops) {
            item.discard();
        }
    }

    /**
     * Reset the interior volume at {@code carriageOrigin} to air and discard
     * any non-player entities inside it. Used by the editor before re-stamping
     * contents on {@code enter}, so previously placed blocks AND entities
     * don't blend with the newly chosen contents.
     */
    public static void eraseAt(ServerLevel level, BlockPos carriageOrigin, CarriageDims dims) {
        Vec3i size = interiorSize(dims);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;
        BlockPos origin = interiorOrigin(carriageOrigin);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    level.setBlock(origin.offset(dx, dy, dz), air, 3);
                }
            }
        }
        discardEntitiesIn(level, origin, size);
    }

    /**
     * Public entity-only clear for the interior volume at
     * {@code carriageOrigin}. Used by the rolling-window spawn path (before
     * stamping new contents) so leftover armor stands / paintings from a
     * previous carriage at this shipyard position don't accumulate. The
     * block-only erase in {@link CarriagePlacer#eraseAt} already handles
     * stale interior blocks.
     */
    public static void discardEntitiesAt(ServerLevel level, BlockPos carriageOrigin, CarriageDims dims) {
        Vec3i size = interiorSize(dims);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;
        discardEntitiesIn(level, interiorOrigin(carriageOrigin), size);
    }

    /**
     * Remove every non-player entity whose bounding box overlaps the interior
     * volume. Players are spared so an author who slipped through the barrier
     * cage mid-edit doesn't get kicked out of existence. Block-entity data is
     * already handled by the {@code setBlock(air)} pass in
     * {@link #eraseAt}.
     */
    private static void discardEntitiesIn(ServerLevel level, BlockPos interiorOrigin, Vec3i interiorSize) {
        AABB box = new AABB(
            interiorOrigin.getX(), interiorOrigin.getY(), interiorOrigin.getZ(),
            interiorOrigin.getX() + interiorSize.getX(),
            interiorOrigin.getY() + interiorSize.getY(),
            interiorOrigin.getZ() + interiorSize.getZ()
        );
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player));
        for (Entity e : entities) {
            e.discard();
        }
    }
}
