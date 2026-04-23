package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

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
        SOLID_ROOF,
        FLATBED
    }

    /** Cached immutable handle to the flatbed built-in — used as the Random-Grouped separator. */
    private static final CarriageVariant FLATBED_VARIANT = CarriageVariant.of(CarriageType.FLATBED);

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
        static final BlockState SOLID_CEILING = Blocks.STONE_BRICKS.defaultBlockState();
        static final BlockState WINDOW = Blocks.GLASS.defaultBlockState();
    }

    private CarriageTemplate() {}

    /**
     * Place a single carriage of the given variant + dims at origin (= minimum
     * corner). Returns the set of block positions filled — pass directly to
     * {@code ShipAssembler.assembleToShip()}.
     *
     * <p>This 4-arg overload is kept for call sites that don't have a
     * carriage index (e.g. the editor's in-plot stamp on {@code enter}): it
     * places the template as-is without resolving any variant-block sidecar
     * entries — the editor doesn't want randomised blocks while you're
     * editing.</p>
     */
    public static Set<BlockPos> placeAt(ServerLevel level, BlockPos origin, CarriageVariant variant, CarriageDims dims) {
        Optional<StructureTemplate> stored = CarriageTemplateStore.get(level, variant, dims);
        if (stored.isPresent()) {
            stampTemplate(level, origin, stored.get());
            Set<BlockPos> placed = collectFootprint(level, origin, dims);
            if (placed.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Empty carriage placed — variant={} origin={} reason=stored-template-all-air",
                    variant.id(), origin);
            } else {
                LOGGER.info("[DungeonTrain] Placed carriage variant={} origin={} source=stored blocks={}",
                    variant.id(), origin, placed.size());
            }
            return placed;
        }
        if (variant instanceof CarriageVariant.Builtin b) {
            Set<BlockPos> placed = legacyPlaceAt(level, origin, b.type(), dims);
            if (placed.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Empty carriage placed — variant={} origin={} reason=legacy-generator-empty",
                    variant.id(), origin);
            } else {
                LOGGER.info("[DungeonTrain] Placed carriage variant={} origin={} source=legacy blocks={}",
                    variant.id(), origin, placed.size());
            }
            return placed;
        }
        // Custom variant with no (or mismatched) NBT — nothing to place.
        LOGGER.warn("[DungeonTrain] Empty carriage placed — variant={} origin={} reason=custom-variant-missing-nbt. Check {} exists and matches world dims {}x{}x{}.",
            variant.id(), origin, CarriageTemplateStore.fileFor(variant),
            dims.length(), dims.width(), dims.height());
        return new HashSet<>();
    }

    /**
     * 6-arg spawn variant — after stamping the NBT template, resolve any
     * {@link CarriageVariantBlocks} sidecar entries for this variant by picking
     * a random candidate state per flagged position, deterministically seeded
     * on {@code (world seed, carriage index, local pos)}. Same seed + same
     * index = same carriage on reload; different indices along the same track
     * visibly vary.
     *
     * <p>Only applies when the NBT-backed path runs. If a built-in variant
     * falls back to {@code legacyPlaceAt} (no saved edits), any sidecar is
     * skipped with a log warning — the legacy geometry doesn't define a stable
     * position basis for variant blocks.</p>
     */
    public static Set<BlockPos> placeAt(
        ServerLevel level, BlockPos origin, CarriageVariant variant,
        CarriageDims dims, CarriageGenerationConfig config, int carriageIndex
    ) {
        Optional<StructureTemplate> stored = CarriageTemplateStore.get(level, variant, dims);
        if (stored.isPresent()) {
            stampTemplate(level, origin, stored.get());
            applyVariantBlocks(level, origin, variant, dims, config, carriageIndex);
            applyContents(level, origin, dims, config, carriageIndex);
            Set<BlockPos> placed = collectFootprint(level, origin, dims);
            if (placed.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Empty carriage placed — variant={} origin={} reason=stored-template-all-air",
                    variant.id(), origin);
            } else {
                LOGGER.info("[DungeonTrain] Placed carriage variant={} origin={} source=stored blocks={}",
                    variant.id(), origin, placed.size());
            }
            return placed;
        }
        if (variant instanceof CarriageVariant.Builtin b) {
            CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(variant, dims);
            if (!sidecar.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Variant sidecar for '{}' ignored — built-in using hardcoded fallback.",
                    variant.id());
            }
            Set<BlockPos> placed = legacyPlaceAt(level, origin, b.type(), dims);
            // Contents still apply on legacy-shell spawns — the interior
            // volume layout (1,1,1)..(length-2,height-2,width-2) is fixed
            // across stored and legacy shells, so contents placement is
            // stable either way.
            applyContents(level, origin, dims, config, carriageIndex);
            if (placed.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Empty carriage placed — variant={} origin={} reason=legacy-generator-empty",
                    variant.id(), origin);
            } else {
                LOGGER.info("[DungeonTrain] Placed carriage variant={} origin={} source=legacy blocks={}",
                    variant.id(), origin, placed.size());
            }
            // Re-collect footprint so any contents blocks stamped into the
            // interior after legacyPlaceAt() returned are included in the
            // assembler's block set.
            return collectFootprint(level, origin, dims);
        }
        LOGGER.warn("[DungeonTrain] Empty carriage placed — variant={} origin={} reason=custom-variant-missing-nbt. Check {} exists and matches world dims {}x{}x{}.",
            variant.id(), origin, CarriageTemplateStore.fileFor(variant),
            dims.length(), dims.width(), dims.height());
        return new HashSet<>();
    }

    /**
     * Pick a {@link CarriageContents} variant deterministically for this
     * carriage and stamp its interior blocks on top of the already-placed
     * shell. Wrapped in try/catch so a contents-load failure can't abort the
     * spawn — worst case the interior stays empty with a warning.
     */
    private static void applyContents(
        ServerLevel level, BlockPos origin, CarriageDims dims,
        CarriageGenerationConfig config, int carriageIndex
    ) {
        try {
            CarriageContents contents = CarriageContentsRegistry.pick(config.seed(), carriageIndex);
            CarriageContentsTemplate.placeAt(level, origin, contents, dims);
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
            BlockState picked = sidecar.resolve(e.localPos(), config.seed(), carriageIndex);
            if (picked == null) continue;
            BlockPos world = origin.offset(e.localPos());
            SilentBlockOps.setBlockSilent(level, world, picked);
        }
    }

    /**
     * Deterministic variant selector for carriage index {@code i}, dispatched
     * on {@link CarriageGenerationMode}:
     *
     * <ul>
     *   <li><b>LOOPING</b> — cycles through {@link CarriageVariantRegistry#allVariants()}
     *       so built-ins come first, customs after (preserves the pre-generation-mode behaviour).</li>
     *   <li><b>RANDOM</b> — seeded {@code (seed, index)} pick from all variants including
     *       the flatbed and any registered customs.</li>
     *   <li><b>RANDOM_GROUPED</b> — every {@code (groupSize + 1)}th index is the built-in
     *       flatbed; every other index is a seeded pick from all non-flatbed variants
     *       (built-ins minus FLATBED, plus all customs).</li>
     * </ul>
     *
     * <p>All three modes are pure functions of {@code (i, config, registry state)} —
     * no {@code java.util.Random} state escapes the call. So long as the registry
     * is stable, walking back over a stretch of track always re-places the identical
     * carriage.</p>
     */
    public static CarriageVariant variantForIndex(int i, CarriageGenerationConfig config) {
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        if (variants.isEmpty()) {
            // Defensive: registry should always have the four built-ins, but
            // if tests clear() it we still need a well-defined answer.
            return FLATBED_VARIANT;
        }
        return switch (config.mode()) {
            case LOOPING -> variants.get(Math.floorMod(i, variants.size()));
            case RANDOM -> variants.get(seededPick(config.seed(), i, variants.size()));
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
                yield nonFlatbed.get(seededPick(config.seed(), i, nonFlatbed.size()));
            }
        };
    }

    private static List<CarriageVariant> filterOutFlatbed(List<CarriageVariant> variants) {
        List<CarriageVariant> out = new ArrayList<>(variants.size());
        for (CarriageVariant v : variants) {
            if (v instanceof CarriageVariant.Builtin b && b.type() == CarriageType.FLATBED) continue;
            out.add(v);
        }
        return out;
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
            return (type == CarriageType.SOLID_ROOF) ? BlockStates.SOLID_CEILING : BlockStates.GLASS_CEILING;
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
