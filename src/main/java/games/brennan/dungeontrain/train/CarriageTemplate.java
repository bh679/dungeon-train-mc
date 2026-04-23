package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

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
     */
    public static Set<BlockPos> placeAt(ServerLevel level, BlockPos origin, CarriageVariant variant, CarriageDims dims) {
        Optional<StructureTemplate> stored = CarriageTemplateStore.get(level, variant, dims);
        if (stored.isPresent()) {
            stampTemplate(level, origin, stored.get());
            return collectFootprint(level, origin, dims);
        }
        if (variant instanceof CarriageVariant.Builtin b) {
            return legacyPlaceAt(level, origin, b.type(), dims);
        }
        // Custom variant with no (or mismatched) NBT — nothing to place.
        return new HashSet<>();
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
