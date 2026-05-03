package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantPartsStore;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carriage blueprint — a hollow box whose dimensions are captured per-world
 * in {@link CarriageDims} (default 9×7×7, X×Z×Y). Variants are selected
 * per-carriage via {@link CarriageVariant} and
 * {@link #variantForIndex(int, CarriageGenerationConfig)}; the generation
 * mode decides whether indices cycle deterministically through the full
 * registered set, roll seeded dice, or emit fixed-spacing flatbed separators.
 *
 * <p>{@link #placeAt(ServerLevel, BlockPos, CarriageVariant, CarriageDims)}
 * first tries an NBT-backed template from {@link CarriageTemplateStore}; if
 * none is saved (or the file's footprint doesn't match the world's current
 * dims), built-ins fall back to the hardcoded generator in
 * {@link #legacyPlaceAt}, while custom variants place nothing (their blocks
 * only exist on disk, never in fallback).
 */
public final class CarriageTemplate {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum CarriageType {
        STANDARD,
        WINDOWED,
        FLATBED
    }

    /**
     * Which end of a sub-level a half-flatbed pad sits on. Both sides
     * stamp the SAME {@code halfPadLen × height × width} half-template
     * derived from {@link CarriageType#FLATBED}'s NBT — {@link #BACK}
     * places it as-is; {@link #FRONT} places it with
     * {@link Mirror#FRONT_BACK} (X-axis mirror) so the two pads visually
     * mirror each other across the sub-level interior, and the seam
     * between two adjacent sub-levels reads as one continuous bed.
     *
     * <p>See {@link TrainAssembler#spawnGroup} for the world layout:
     * {@code [BACK pad | groupSize × enclosed | FRONT pad]} where each
     * pad occupies {@code halfPadLen} blocks (= {@code (length+1)/2}).</p>
     */
    public enum HalfPadSide { BACK, FRONT }

    /** Cached immutable handle to the flatbed built-in — used as the Random-Grouped separator. */
    private static final CarriageVariant FLATBED_VARIANT = CarriageVariant.of(CarriageType.FLATBED);

    /**
     * In-memory cache of half-sized flatbed templates derived once per
     * {@link CarriageDims} from the full {@link CarriageType#FLATBED}'s
     * stored NBT. Keyed on {@code CarriageDims} (record-based equality).
     * Cleared on {@link net.neoforged.neoforge.event.server.ServerStoppedEvent}
     * via {@link #clearHalfFlatbedCache()} so a hot-reload between worlds
     * with different dims doesn't reuse a stale half-template.
     *
     * <p>{@code Optional.empty()} entries are negative-cache markers —
     * they record "tried to extract for these dims and the FLATBED has
     * no NBT," so subsequent spawns short-circuit straight to the
     * hardcoded floor fallback without re-attempting the extraction.</p>
     */
    private static final Map<CarriageDims, Optional<StructureTemplate>> HALF_FLATBED_CACHE
        = new ConcurrentHashMap<>();

    /**
     * Lazy-init holder for the {@link BlockState} templates. Keeping
     * {@code Blocks.*} access off {@link CarriageTemplate}'s own static init
     * means plain JUnit tests can call
     * {@link #variantForIndex(int, CarriageGenerationConfig)} without
     * requiring a Forge/Minecraft {@code Bootstrap}. The holder is only
     * loaded on first reference from {@link #stateAt} (i.e. from a live
     * server-thread {@code placeAt} call), so there is no behavioural change.
     */
    private static final class BlockStates {
        static final BlockState FLOOR = Blocks.STONE_BRICKS.defaultBlockState();
        static final BlockState WALL = Blocks.STONE_BRICKS.defaultBlockState();
        static final BlockState GLASS_CEILING = Blocks.GLASS.defaultBlockState();
        static final BlockState WINDOW = Blocks.GLASS.defaultBlockState();
    }

    private CarriageTemplate() {}

    /**
     * Place a single carriage of the given variant + dims at origin (= minimum
     * corner). Returns the set of block positions filled — pass directly to
     * {@code ShipAssembler.assembleToShip()}.
     *
     * <p><b>Composition model</b> (overlay, not replace): the carriage's own
     * monolithic NBT is always stamped first as the base (or, for built-ins
     * with no NBT, the legacy hardcoded generator runs). If the variant has a
     * {@code <id>.parts.json} sidecar with non-{@link CarriagePartKind#NONE}
     * slots, each declared part template is stamped <em>on top</em> of that
     * base — replacing only the blocks inside its footprint. A part whose
     * slot is {@code "none"} or whose template is missing from disk becomes a
     * no-op overlay, leaving the monolithic content in place for that region.
     * This lets authors customise individual kinds (e.g. swap just the floor)
     * without having to re-author every kind from scratch.</p>
     *
     * <p>This 4-arg overload is used by the editor's in-plot preview and the
     * duplicate flow — no per-position variant-block randomisation, and the
     * parts pick is seeded deterministically with {@code (0, 0)} so the
     * preview is stable across re-entries.</p>
     */
    public static Set<BlockPos> placeAt(ServerLevel level, BlockPos origin, CarriageVariant variant, CarriageDims dims) {
        String base = stampBase(level, origin, variant, dims);
        String overlay = stampPartsOverlay(level, origin, variant, dims, 0L, 0);
        return finishPlace(level, origin, variant, dims, base, overlay);
    }

    /**
     * 6-arg spawn variant — same monolithic-base + parts-overlay composition
     * as the 4-arg overload, but also resolves any
     * {@link CarriageVariantBlocks} sidecar entries after the stamp pass by
     * picking a random candidate state per flagged position, deterministically
     * seeded on {@code (world seed, carriage index, local pos)}. Same seed +
     * same index = same carriage on reload; different indices along the same
     * track visibly vary.
     *
     * <p>Variant blocks apply on top of NBT-backed content (monolithic stored
     * template and / or parts overlay). If a built-in falls back to
     * {@link #legacyPlaceAt} because it has no stored NBT, the sidecar is
     * skipped — the legacy geometry doesn't define a stable local position
     * basis for the sidecar entries.</p>
     */
    public static Set<BlockPos> placeAt(
        ServerLevel level, BlockPos origin, CarriageVariant variant,
        CarriageDims dims, CarriageGenerationConfig config, int carriageIndex
    ) {
        return placeAt(level, origin, variant, dims, config, carriageIndex, true);
    }

    /**
     * Overload that lets the caller skip the contents pass. Used by the
     * initial-spawn path in {@code TrainAssembler.spawnTrain} which places
     * carriages in WORLD space and then calls {@code ShipAssembler.assembleToShip}
     * to move the blocks into the ship's shipyard space. Entities from
     * {@code applyContents} would be spawned in world space and stay there
     * (VS's shipyard-entity mixin only handles entities already in shipyard
     * chunks), so TrainAssembler defers the contents pass until after assembly
     * and runs it at the shipyard coordinates of each carriage.
     *
     * <p>The rolling-window spawns in {@code TrainWindowManager} place
     * directly at shipyard coordinates and so keep {@code applyContents=true}.
     */
    public static Set<BlockPos> placeAt(
        ServerLevel level, BlockPos origin, CarriageVariant variant,
        CarriageDims dims, CarriageGenerationConfig config, int carriageIndex,
        boolean applyContents
    ) {
        String base = stampBase(level, origin, variant, dims);
        String overlay = stampPartsOverlay(level, origin, variant, dims, config.seed(), carriageIndex);

        // Variant-block overlays are position-based and assume a stable NBT-
        // backed basis. legacyPlaceAt's hardcoded geometry doesn't qualify,
        // so skip the sidecar in that case (same gate as before the refactor).
        boolean nbtBacked = "stored".equals(base) || overlay != null;
        if (nbtBacked) {
            applyVariantBlocks(level, origin, variant, dims, config, carriageIndex);
        } else if ("legacy".equals(base)) {
            CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(variant, dims);
            if (!sidecar.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Variant sidecar for '{}' ignored — built-in using hardcoded fallback.",
                    variant.id());
            }
        }

        // Contents apply on any source (stored / legacy / parts overlay) —
        // the interior volume at (1..L-2, 1..H-2, 1..W-2) is identical across
        // shell variants, so CarriageContentsTemplate doesn't care which one
        // it lands inside. finishPlace's collectFootprint re-reads the region
        // afterwards so contents blocks are included in the returned set.
        if (applyContents && (base != null || overlay != null)) {
            applyContents(level, origin, variant, dims, config, carriageIndex);
        }

        return finishPlace(level, origin, variant, dims, base, overlay);
    }

    /**
     * Public wrapper around the private {@code applyContents} method — exposes
     * the contents pass so {@code TrainAssembler} can call it in a second pass
     * after {@code ShipAssembler.assembleToShip}. Passes the variant so
     * FLATBED skips automatically.
     */
    public static void applyContentsAt(
        ServerLevel level, BlockPos origin, CarriageVariant variant,
        CarriageDims dims, CarriageGenerationConfig config, int carriageIndex
    ) {
        applyContents(level, origin, variant, dims, config, carriageIndex);
    }

    /**
     * Pick a {@link CarriageContents} variant deterministically for this
     * carriage and stamp its interior blocks on top of the already-placed
     * shell. Wrapped in try/catch so a contents-load failure can't abort the
     * spawn — worst case the interior stays empty with a warning.
     *
     * <p>Skipped for the {@link CarriageType#FLATBED} built-in: a flatbed is
     * just a floor with no walls or roof, so any interior contents would
     * appear as floating blocks visible from every side. Keep flatbeds empty
     * so they read as the train's "separators" between enclosed carriages.</p>
     */
    private static void applyContents(
        ServerLevel level, BlockPos origin, CarriageVariant variant,
        CarriageDims dims, CarriageGenerationConfig config, int carriageIndex
    ) {
        if (variant instanceof CarriageVariant.Builtin b && b.type() == CarriageType.FLATBED) {
            return;
        }
        try {
            CarriageContents contents = CarriageContentsRegistry.pick(config.seed(), carriageIndex, variant);
            // Clear any entities left over from a previous carriage at this
            // shipyard position — the block-only clearBoundingBox in
            // TrainAssembler doesn't discard entities, so armor stands and
            // paintings from prior contents at this index would otherwise
            // accumulate each rolling-window cycle.
            CarriageContentsTemplate.discardEntitiesAt(level, origin, dims);
            CarriageContentsTemplate.placeAt(level, origin, contents, dims, config.seed(), carriageIndex);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Failed to apply contents at origin={} carriageIndex={}: {}",
                origin, carriageIndex, t.toString());
        }
    }

    private static void applyVariantBlocks(
        ServerLevel level, BlockPos origin, CarriageVariant variant,
        CarriageDims dims, CarriageGenerationConfig config, int carriageIndex
    ) {
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(variant, dims);
        if (sidecar.isEmpty()) return;
        for (CarriageVariantBlocks.Entry e : sidecar.entries()) {
            VariantState picked = sidecar.resolve(e.localPos(), config.seed(), carriageIndex);
            if (picked == null) continue;
            BlockPos world = origin.offset(e.localPos());
            if (CarriageVariantBlocks.isEmptyPlaceholder(picked.state())) {
                SilentBlockOps.setBlockSilent(level, world, Blocks.AIR.defaultBlockState());
            } else {
                BlockState rotated = games.brennan.dungeontrain.editor.RotationApplier.apply(
                    picked.state(), picked.rotation(),
                    e.localPos(), config.seed(), carriageIndex,
                    sidecar.lockIdAt(e.localPos()));
                games.brennan.dungeontrain.editor.ContainerContentsPlacement.place(
                    level, world, rotated, picked.blockEntityNbt(),
                    "carriage:" + variant.id(), e.localPos(), config.seed(), carriageIndex);
            }
        }
    }

    /**
     * Stamp the carriage's monolithic base layer: tier-1 config override →
     * tier-2 bundled resource (handled inside {@link CarriageTemplateStore})
     * → tier-3 legacy hardcoded generator (built-ins only). Returns a source
     * tag for logging, or {@code null} when nothing was stamped (custom
     * variants without any NBT on disk).
     *
     * <p>Half-flatbed pads are NOT placed via this method — they're
     * placed directly by {@link TrainAssembler#spawnGroup} via
     * {@link #placeHalfFlatbedPad}, OUTSIDE the integer carriage-slot
     * grid.</p>
     */
    private static String stampBase(ServerLevel level, BlockPos origin, CarriageVariant variant, CarriageDims dims) {
        Optional<StructureTemplate> stored = CarriageTemplateStore.get(level, variant, dims);
        if (stored.isPresent()) {
            stampTemplate(level, origin, stored.get());
            return "stored";
        }
        if (variant instanceof CarriageVariant.Builtin b) {
            legacyPlaceAt(level, origin, b.type(), dims);
            return "legacy";
        }
        return null;
    }

    // ─── Half-flatbed pad placement ─────────────────────────────────────
    // Half-flatbed pads sit OUTSIDE the integer carriage-slot grid at
    // each end of a sub-level: [BACK pad | groupSize × enclosed | FRONT pad].
    // Both pads use a single half-sized template derived once from the
    // FLATBED's stored NBT — BACK stamps it as-is, FRONT stamps it with
    // Mirror.FRONT_BACK so the two pads are visual mirror images.
    // Adjacent sub-levels' BACK + FRONT pads at every seam combine to
    // 2 × halfPadLen blocks of contiguous floor — for length=9 (the
    // default), halfPadLen=5 → 10 contiguous floor blocks straddling
    // each seam.
    // ────────────────────────────────────────────────────────────────────

    /**
     * Width (in blocks along the carriage's X axis) of one half-flatbed
     * pad. Defined as {@code (length + 1) / 2} so a SINGLE half-sized
     * NBT works for both pads at any length, and adjacent groups'
     * BACK + FRONT pads at every seam combine to {@code 2 × halfPadLen}
     * blocks of contiguous floor (= {@code length + 1} for odd lengths,
     * exact {@code length} for even).
     *
     * <p>For length=9, halfPadLen=5 → 5+5=10 contiguous floor at every
     * seam (1 block of overshoot vs a single carriage's floor —
     * invisible visually). For length=8, halfPadLen=4 → 4+4=8, exact.</p>
     */
    public static int halfPadLen(CarriageDims dims) {
        return (dims.length() + 1) / 2;
    }

    /** Invalidate the half-flatbed template cache. Wired to {@code ServerStoppedEvent}. */
    public static void clearHalfFlatbedCache() {
        HALF_FLATBED_CACHE.clear();
    }

    /**
     * Resolve the half-sized flatbed template for {@code dims}, deriving
     * it from the full FLATBED's stored NBT on first call and caching
     * the result. {@code Optional.empty()} marks "FLATBED has no NBT
     * for these dims" so subsequent spawns short-circuit straight to
     * {@link #legacyHalfFlatbedFloor} without re-attempting extraction.
     */
    private static Optional<StructureTemplate> getOrBuildHalfFlatbedTemplate(ServerLevel level, CarriageDims dims) {
        Optional<StructureTemplate> cached = HALF_FLATBED_CACHE.get(dims);
        if (cached != null) return cached;
        Optional<StructureTemplate> built = buildHalfFlatbedTemplate(level, dims);
        HALF_FLATBED_CACHE.put(dims, built);
        return built;
    }

    /**
     * Build a {@code halfPadLen × height × width} sub-template from
     * FLATBED's stored NBT by saving the full template's CompoundTag,
     * shrinking the {@code "size"} field's X dimension to halfPadLen,
     * filtering the {@code "blocks"} list to keep only entries with
     * {@code pos[0] < halfPadLen}, and reloading the trimmed tag into a
     * fresh {@link StructureTemplate}. The {@code "palette"} and
     * {@code "entities"} lists carry over unchanged — orphaned palette
     * entries don't hurt placement.
     */
    private static Optional<StructureTemplate> buildHalfFlatbedTemplate(ServerLevel level, CarriageDims dims) {
        Optional<StructureTemplate> source = CarriageTemplateStore.get(level, FLATBED_VARIANT, dims);
        if (source.isEmpty()) {
            LOGGER.debug("[DungeonTrain] No FLATBED NBT for dims {}x{}x{} — half-flatbed pads will use hardcoded floor fallback.",
                dims.length(), dims.height(), dims.width());
            return Optional.empty();
        }

        int padLen = halfPadLen(dims);
        CompoundTag fullTag = source.get().save(new CompoundTag());

        ListTag sizeList = fullTag.getList("size", Tag.TAG_INT);
        if (sizeList.size() != 3) {
            LOGGER.warn("[DungeonTrain] FLATBED template size list malformed (size={}); skipping half-flatbed extraction.",
                sizeList.size());
            return Optional.empty();
        }
        sizeList.set(0, IntTag.valueOf(padLen));

        ListTag fullBlocks = fullTag.getList("blocks", Tag.TAG_COMPOUND);
        ListTag filtered = new ListTag();
        for (int i = 0; i < fullBlocks.size(); i++) {
            CompoundTag blockTag = fullBlocks.getCompound(i);
            ListTag posList = blockTag.getList("pos", Tag.TAG_INT);
            if (posList.size() != 3) continue;
            if (posList.getInt(0) < padLen) filtered.add(blockTag);
        }
        fullTag.put("blocks", filtered);

        HolderGetter<Block> blockHolders = level.holderLookup(Registries.BLOCK);
        StructureTemplate half = new StructureTemplate();
        half.load(blockHolders, fullTag);

        LOGGER.info("[DungeonTrain] Built half-flatbed template for dims {}x{}x{} — halfPadLen={}, kept {}/{} blocks.",
            dims.length(), dims.height(), dims.width(), padLen, filtered.size(), fullBlocks.size());
        return Optional.of(half);
    }

    /**
     * Place a half-flatbed pad at world {@code origin}, occupying
     * {@code halfPadLen × dims.height() × dims.width()} blocks at world
     * X range {@code [origin.x, origin.x + halfPadLen - 1]}.
     * {@link HalfPadSide#BACK} stamps the half-template as-is;
     * {@link HalfPadSide#FRONT} stamps it with {@link Mirror#FRONT_BACK}
     * (X-axis mirror) so the two pads are visual mirrors of each other
     * across each sub-level's interior.
     *
     * <p>{@code Mirror.FRONT_BACK} negates template-local X relative to
     * the stamp pivot, so a naive {@code placeInWorld(origin, origin, …)}
     * with mirror would land blocks at world X ∈ {@code [origin.x − padLen + 1, origin.x]}
     * (extending BACKWARDS from origin, overlapping the previous enclosed
     * carriage). To keep the FRONT pad's footprint at
     * {@code [origin.x, origin.x + padLen - 1]}, the stamp position is
     * pre-shifted by {@code padLen − 1} on X (same trick as
     * {@link games.brennan.dungeontrain.tunnel.TunnelTemplate#placePortalNamed}).</p>
     *
     * <p>If FLATBED has no NBT, falls back to a hardcoded stone-bricks
     * floor over the pad footprint via {@link #legacyHalfFlatbedFloor}.</p>
     *
     * @return the set of block positions filled — pass directly to
     *     {@code Shipyards.assemble()} alongside the enclosed carriages.
     */
    public static Set<BlockPos> placeHalfFlatbedPad(ServerLevel level, BlockPos origin, HalfPadSide side, CarriageDims dims) {
        int padLen = halfPadLen(dims);
        Optional<StructureTemplate> halfTemplate = getOrBuildHalfFlatbedTemplate(level, dims);
        if (halfTemplate.isPresent()) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            BlockPos stampOrigin;
            if (side == HalfPadSide.FRONT) {
                settings.setMirror(Mirror.FRONT_BACK);
                // Shift the stamp pivot to origin + padLen - 1 so that
                // Mirror.FRONT_BACK's negation of local-x lands the
                // template's blocks at world X ∈ [origin.x, origin.x + padLen - 1]
                // instead of [origin.x - padLen + 1, origin.x].
                stampOrigin = origin.offset(padLen - 1, 0, 0);
            } else {
                stampOrigin = origin;
            }
            halfTemplate.get().placeInWorld(level, stampOrigin, stampOrigin, settings, level.getRandom(), 3);
        } else {
            legacyHalfFlatbedFloor(level, origin, padLen, dims);
        }
        Set<BlockPos> placed = collectHalfPadFootprint(level, origin, padLen, dims);
        LOGGER.debug("[DungeonTrain] Placed half-flatbed pad side={} origin={} padLen={} expectedExtent=[{}, {}]x[y,y+{}]x[z,z+{}] blocks={}",
            side, origin, padLen,
            origin.getX(), origin.getX() + padLen - 1,
            dims.height() - 1, dims.width() - 1,
            placed.size());
        return placed;
    }

    private static void legacyHalfFlatbedFloor(ServerLevel level, BlockPos origin, int padLen, CarriageDims dims) {
        BlockState floor = BlockStates.FLOOR;
        for (int dx = 0; dx < padLen; dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                level.setBlock(origin.offset(dx, 0, dz), floor, 3);
            }
        }
    }

    private static Set<BlockPos> collectHalfPadFootprint(ServerLevel level, BlockPos origin, int padLen, CarriageDims dims) {
        Set<BlockPos> placed = new HashSet<>();
        for (int dx = 0; dx < padLen; dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) {
                        placed.add(pos.immutable());
                    }
                }
            }
        }
        return placed;
    }

    /**
     * Stamp any declared part templates on top of whatever base already sits
     * in the carriage footprint. Each slot's pick is resolved deterministically
     * from {@link CarriagePartAssignment#pick}; slots set to
     * {@link CarriagePartKind#NONE} or whose picked template is missing from
     * disk are skipped (leaving the base's content in that region untouched).
     * Returns a short descriptor of which kinds were overlaid, or {@code null}
     * when no overlay ran.
     */
    private static String stampPartsOverlay(ServerLevel level, BlockPos origin, CarriageVariant variant,
                                             CarriageDims dims, long seed, int carriageIndex) {
        Optional<CarriagePartAssignment> assignment = CarriageVariantPartsStore.get(variant);
        if (assignment.isEmpty()) return null;
        CarriagePartAssignment a = assignment.get();
        if (a.allNone()) return null;

        StringBuilder desc = new StringBuilder();
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            // Pick once per placement so walls / doors honour the per-entry
            // SideMode (BOTH = mirror; ONE = always pick a different name
            // for the other side; EITHER = seeded coin-flip). Floor / roof
            // resolves to a single-element list and behaves as before.
            java.util.List<String> picks = a.pickPerPlacement(kind, seed, carriageIndex);
            boolean stamped = false;
            for (String picked : picks) {
                if (!CarriagePartKind.NONE.equals(picked)
                    && CarriagePartTemplateStore.get(level, kind, picked, dims).isPresent()) {
                    stamped = true;
                    break;
                }
            }
            if (!stamped) continue;
            CarriagePartTemplate.placeAtPerPlacement(level, origin, kind, picks, dims, seed, carriageIndex);
            if (desc.length() > 0) desc.append(",");
            desc.append(kind.id()).append("=");
            if (picks.size() == 1 || picks.get(0).equals(picks.get(picks.size() - 1))) {
                desc.append(picks.get(0));
            } else {
                desc.append(String.join("/", picks));
            }
        }
        return desc.length() == 0 ? null : "parts(" + desc + ")";
    }

    private static Set<BlockPos> finishPlace(ServerLevel level, BlockPos origin,
                                             CarriageVariant variant, CarriageDims dims,
                                             String base, String overlay) {
        Set<BlockPos> placed = collectFootprint(level, origin, dims);
        String sources = joinSources(base, overlay);
        if (placed.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Empty carriage placed — variant={} origin={} sources={}{}",
                variant.id(), origin, sources.isEmpty() ? "none" : sources,
                sources.isEmpty() ? " (custom variant missing NBT and no parts overlay; "
                    + CarriageTemplateStore.fileFor(variant) + " not found)" : "");
        } else {
            LOGGER.info("[DungeonTrain] Placed carriage variant={} origin={} sources={} blocks={}",
                variant.id(), origin, sources, placed.size());
        }
        return placed;
    }

    private static String joinSources(String base, String overlay) {
        if (base != null && overlay != null) return base + "+" + overlay;
        if (base != null) return base;
        if (overlay != null) return overlay;
        return "";
    }

    /**
     * Deterministic variant selector for carriage index {@code i}, dispatched
     * on {@link CarriageGenerationMode}. Delegates to the weighted overload
     * using the active server's {@link CarriageWeights#current()}. Weight 1
     * (the default for any variant not mentioned in {@code weights.json}) makes
     * the pick uniform over the pool, matching the pre-weighting behaviour.
     */
    public static CarriageVariant variantForIndex(int i, CarriageGenerationConfig config) {
        return variantForIndex(i, config, CarriageWeights.current());
    }

    /**
     * Like {@link #variantForIndex(int, CarriageGenerationConfig)} but
     * guaranteed to return a non-flatbed (enclosed) variant — used by
     * {@link TrainAssembler#spawnGroup} for the inner slots of a group
     * (where the half-flatbeds at the ends already provide the bed at
     * sub-level seams; a mid-group flatbed would be a wasted slot).
     *
     * <p>If {@code variantForIndex} returns a flatbed-like variant
     * (full flatbed or half-flatbed), this falls back to a weighted seeded
     * pick from the non-flatbed pool.</p>
     */
    public static CarriageVariant enclosedVariantForIndex(int i, CarriageGenerationConfig config) {
        CarriageVariant v = variantForIndex(i, config);
        if (!isAnyFlatbed(v)) return v;
        List<CarriageVariant> pool = filterOutFlatbed(CarriageVariantRegistry.allVariants());
        if (pool.isEmpty()) return v; // fallback to whatever variantForIndex gave
        return weightedSeededPick(config.seed(), i, pool, CarriageWeights.current());
    }

    /**
     * Weighted deterministic variant selector for carriage index {@code i},
     * dispatched on {@link CarriageGenerationMode}:
     *
     * <ul>
     *   <li><b>LOOPING</b> — cycles through {@link CarriageVariantRegistry#allVariants()}
     *       so built-ins come first, customs after. Ignores {@code weights} —
     *       the deterministic cycle doesn't apply to random distributions.</li>
     *   <li><b>RANDOM</b> — seeded {@code (seed, index)} pick from all variants,
     *       biased by {@code weights}. Weight 0 excludes a variant from the pool.</li>
     *   <li><b>RANDOM_GROUPED</b> — every {@code (groupSize + 1)}th index is the built-in
     *       flatbed regardless of weights (the separator slot is a fixed visual rhythm);
     *       every other index is a weighted seeded pick from all non-flatbed variants.</li>
     * </ul>
     *
     * <p>All three modes are pure functions of {@code (i, config, registry state,
     * weights state)} — no {@code java.util.Random} state escapes the call. So
     * long as the registry and weights are stable, walking back over a stretch
     * of track always re-places the identical carriage.</p>
     */
    public static CarriageVariant variantForIndex(int i, CarriageGenerationConfig config, CarriageWeights weights) {
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        if (variants.isEmpty()) {
            // Defensive: registry should always have the four built-ins, but
            // if tests clear() it we still need a well-defined answer.
            return FLATBED_VARIANT;
        }
        return switch (config.mode()) {
            case LOOPING -> variants.get(Math.floorMod(i, variants.size()));
            case RANDOM -> weightedSeededPick(config.seed(), i, variants, weights);
            case RANDOM_GROUPED -> {
                int cycleLen = config.groupSize() + 1;
                int pos = Math.floorMod(i, cycleLen);
                if (pos == config.groupSize()) {
                    yield FLATBED_VARIANT;
                }
                List<CarriageVariant> nonFlatbed = filterOutFlatbed(variants);
                if (nonFlatbed.isEmpty()) {
                    // Only the flatbed registered — no meaningful "group member"
                    // choice, so fall back to flatbed for those slots too.
                    yield FLATBED_VARIANT;
                }
                yield weightedSeededPick(config.seed(), i, nonFlatbed, weights);
            }
        };
    }

    private static List<CarriageVariant> filterOutFlatbed(List<CarriageVariant> variants) {
        List<CarriageVariant> out = new ArrayList<>(variants.size());
        for (CarriageVariant v : variants) {
            if (isAnyFlatbed(v)) continue;
            out.add(v);
        }
        return out;
    }

    /**
     * True for the full flatbed — the only floor-only carriage variant
     * after the Gate B.2 pad refactor (half-flatbeds are no longer
     * carriage variants; they're placed directly as sub-level boundary
     * pads via {@link #placeHalfFlatbedPad}). Kept as
     * {@code isAnyFlatbed} for caller-API stability.
     */
    static boolean isAnyFlatbed(CarriageVariant v) {
        if (!(v instanceof CarriageVariant.Builtin b)) return false;
        return b.type() == CarriageType.FLATBED;
    }

    /**
     * Deterministic {@code [0, bound)} pick from {@code (seed, index)}. Mixes
     * the index through the 64-bit golden-ratio constant before seeding
     * {@code Random} so adjacent indices don't produce correlated outputs —
     * fresh {@code Random} per call is cheap (HotSpot allocates and inlines
     * away) and keeps the helper pure.
     */
    private static int seededPick(long seed, int index, int bound) {
        long mixed = seed ^ ((long) index * 0x9E3779B97F4A7C15L);
        return new Random(mixed).nextInt(bound);
    }

    /**
     * Weighted counterpart to {@link #seededPick}. Returns a variant from
     * {@code pool} with probability proportional to its weight as reported by
     * {@code weights}. Same {@code (seed, index)} mixing as the uniform pick,
     * so swapping in a {@link CarriageWeights#EMPTY} / all-default map
     * reproduces the uniform distribution exactly.
     *
     * <p>If every variant in the pool has weight 0 the function falls back to
     * a uniform pick so the carriage placer never has to deal with a degenerate
     * empty pool. The fallback logs a warning once per server session via
     * {@link CarriageTemplate#warnAllZeroOnce}.</p>
     */
    private static CarriageVariant weightedSeededPick(long seed, int index, List<CarriageVariant> pool, CarriageWeights weights) {
        int n = pool.size();
        int[] cumulative = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            total += weights.weightFor(pool.get(i).id());
            cumulative[i] = total;
        }
        long mixed = seed ^ ((long) index * 0x9E3779B97F4A7C15L);
        Random rng = new Random(mixed);
        if (total <= 0) {
            warnAllZeroOnce();
            return pool.get(rng.nextInt(n));
        }
        int r = rng.nextInt(total);
        for (int i = 0; i < n; i++) {
            if (r < cumulative[i]) return pool.get(i);
        }
        // Unreachable: r < total and cumulative[n-1] == total. Defensive tail.
        return pool.get(n - 1);
    }

    /** Throttle for the all-zero-weights fallback warning — one log line per server session. */
    private static volatile boolean ALL_ZERO_WARNED = false;

    private static void warnAllZeroOnce() {
        if (ALL_ZERO_WARNED) return;
        ALL_ZERO_WARNED = true;
        LOGGER.warn("[DungeonTrain] Every variant in the random pool has weight 0 — falling back to uniform pick. Check weights.json.");
    }

    /**
     * Erase a carriage footprint — set every block in the
     * {@code length × height × width} region at {@code origin} to air. Used
     * by the rolling-window manager to remove stale carriages from the
     * trailing end of the train.
     */
    public static void eraseAt(ServerLevel level, BlockPos origin, CarriageDims dims) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < dims.length(); dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), air, 3);
                }
            }
        }
    }

    private static void stampTemplate(ServerLevel level, BlockPos origin, StructureTemplate template) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
    }

    public static Set<BlockPos> collectFootprint(ServerLevel level, BlockPos origin, CarriageDims dims) {
        Set<BlockPos> placed = new HashSet<>();
        for (int dx = 0; dx < dims.length(); dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) {
                        placed.add(pos.immutable());
                    }
                }
            }
        }
        return placed;
    }

    /**
     * True when the NBT template's recorded size matches the provided dims
     * (X=length, Y=height, Z=width per the {@code StructureTemplate} ordering).
     * Used by {@link CarriageTemplateStore} to reject stale templates saved
     * for a different world's dims.
     */
    public static boolean sizeMatches(Vec3i templateSize, CarriageDims dims) {
        return templateSize.getX() == dims.length()
                && templateSize.getY() == dims.height()
                && templateSize.getZ() == dims.width();
    }

    private static Set<BlockPos> legacyPlaceAt(ServerLevel level, BlockPos origin, CarriageType type, CarriageDims dims) {
        Set<BlockPos> placed = new HashSet<>();
        int doorZ = dims.width() / 2;

        for (int dx = 0; dx < dims.length(); dx++) {
            for (int dz = 0; dz < dims.width(); dz++) {
                for (int dy = 0; dy < dims.height(); dy++) {
                    BlockState state = stateAt(dx, dy, dz, doorZ, type, dims);
                    if (state == null) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    level.setBlock(pos, state, 3);
                    placed.add(pos.immutable());
                }
            }
        }
        return placed;
    }

    /**
     * Decide what block (if any) sits at carriage-local offset {@code (dx,dy,dz)}
     * for a given type + dims. Returns {@code null} for air — makes the
     * iteration loops skip without placing.
     *
     * <p>Package-private so {@link games.brennan.dungeontrain.train.CarriageTemplateTest}
     * can pin the perimeter/door/window geometry at non-default dims
     * (e.g. 5×5×5).</p>
     */
    static BlockState stateAt(int dx, int dy, int dz, int doorZ, CarriageType type, CarriageDims dims) {
        if (type == CarriageType.FLATBED) {
            if (dy == 0) return BlockStates.FLOOR;
            return null;
        }

        if (dy == 0) return BlockStates.FLOOR;
        if (dy == dims.height() - 1) {
            return BlockStates.GLASS_CEILING;
        }

        boolean onPerimeter = (dx == 0 || dx == dims.length() - 1 || dz == 0 || dz == dims.width() - 1);
        if (!onPerimeter) return null;

        boolean isEndWall = (dx == 0 || dx == dims.length() - 1);
        boolean isDoorGap = isEndWall && dz == doorZ && (dy == 1 || dy == 2);
        if (isDoorGap) return null;

        if (type == CarriageType.WINDOWED && dy == 2 && !isEndWall) {
            return BlockStates.WINDOW;
        }

        return BlockStates.WALL;
    }
}
