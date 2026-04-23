package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Carriage blueprint — a 9×9×7 (X×Z×Y) hollow box. Four visual variants
 * are selected per-carriage via {@link CarriageType} and
 * {@link #typeForIndex(int)} so a train of length ≥ 4 shows one of each.
 *
 * {@link #placeAt(ServerLevel, BlockPos, CarriageType)} first tries an
 * NBT-backed template from {@link CarriageTemplateStore}; if none is saved
 * (or the file is malformed), it falls back to the hardcoded generator in
 * {@link #legacyPlaceAt(ServerLevel, BlockPos, CarriageType)}.
 */
public final class CarriageTemplate {

    public enum CarriageType {
        STANDARD,
        WINDOWED,
        SOLID_ROOF,
        FLATBED
    }

    public static final int LENGTH = 9;
    public static final int WIDTH = 9;
    public static final int HEIGHT = 7;

    /**
     * Lazy-init holder for the {@link BlockState} templates. Keeping
     * {@code Blocks.*} access off {@link CarriageTemplate}'s own static init
     * means plain JUnit tests can call {@link #typeForIndex(int)} without
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

    public static Set<BlockPos> placeAt(ServerLevel level, BlockPos origin) {
        return placeAt(level, origin, CarriageType.STANDARD);
    }

    /**
     * Place a single carriage of the given type at origin (= minimum corner).
     * Returns the set of block positions filled — pass directly to
     * {@code ShipAssembler.assembleToShip()}.
     */
    public static Set<BlockPos> placeAt(ServerLevel level, BlockPos origin, CarriageType type) {
        Optional<StructureTemplate> stored = CarriageTemplateStore.get(level, type);
        if (stored.isPresent()) {
            stampTemplate(level, origin, stored.get());
            return collectFootprint(level, origin);
        }
        return legacyPlaceAt(level, origin, type);
    }

    /**
     * Deterministic type selector for carriage index {@code i}. Shared by the
     * initial spawn path and the rolling-window manager so carriage index N
     * always renders the same variant regardless of when it appears.
     */
    public static CarriageType typeForIndex(int i) {
        CarriageType[] types = CarriageType.values();
        int mod = Math.floorMod(i, types.length);
        return types[mod];
    }

    /**
     * Erase a carriage footprint — set every block in the 9×7×9 region at {@code origin}
     * to air. Used by the rolling-window manager to remove stale carriages from the
     * trailing end of the train.
     */
    public static void eraseAt(ServerLevel level, BlockPos origin) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dz = 0; dz < WIDTH; dz++) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), air, 3);
                }
            }
        }
    }

    private static void stampTemplate(ServerLevel level, BlockPos origin, StructureTemplate template) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
    }

    private static Set<BlockPos> collectFootprint(ServerLevel level, BlockPos origin) {
        Set<BlockPos> placed = new HashSet<>();
        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dz = 0; dz < WIDTH; dz++) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) {
                        placed.add(pos.immutable());
                    }
                }
            }
        }
        return placed;
    }

    private static Set<BlockPos> legacyPlaceAt(ServerLevel level, BlockPos origin, CarriageType type) {
        Set<BlockPos> placed = new HashSet<>();
        int doorZ = WIDTH / 2;

        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dz = 0; dz < WIDTH; dz++) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    BlockState state = stateAt(dx, dy, dz, doorZ, type);
                    if (state == null) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    level.setBlock(pos, state, 3);
                    placed.add(pos.immutable());
                }
            }
        }
        return placed;
    }

    private static BlockState stateAt(int dx, int dy, int dz, int doorZ, CarriageType type) {
        if (type == CarriageType.FLATBED) {
            if (dy == 0) return BlockStates.FLOOR;
            return null;
        }

        if (dy == 0) return BlockStates.FLOOR;
        if (dy == HEIGHT - 1) {
            return (type == CarriageType.SOLID_ROOF) ? BlockStates.SOLID_CEILING : BlockStates.GLASS_CEILING;
        }

        boolean onPerimeter = (dx == 0 || dx == LENGTH - 1 || dz == 0 || dz == WIDTH - 1);
        if (!onPerimeter) return null;

        boolean isEndWall = (dx == 0 || dx == LENGTH - 1);
        boolean isDoorGap = isEndWall && dz == doorZ && (dy == 1 || dy == 2);
        if (isDoorGap) return null;

        if (type == CarriageType.WINDOWED && dy == 2 && !isEndWall) {
            return BlockStates.WINDOW;
        }

        return BlockStates.WALL;
    }
}
