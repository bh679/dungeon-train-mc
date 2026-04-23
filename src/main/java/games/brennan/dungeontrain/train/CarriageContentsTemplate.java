package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.train.CarriageContents.ContentsType;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Interior-contents blueprint — stamps the {@link CarriageContents} template
 * into the interior volume of a carriage at a given origin. Parallel to
 * {@link CarriageTemplate} but scoped to the {@code (length-2) × (height-2) × (width-2)}
 * interior region; the shell floor/walls/ceiling are placed separately by
 * {@link CarriageTemplate#placeAt} and are not affected.
 *
 * <p>{@link #placeAt(ServerLevel, BlockPos, CarriageContents, CarriageDims)}
 * first tries an NBT-backed template from {@link CarriageContentsStore}; if
 * none is saved (or the file's footprint doesn't match the world's current
 * interior dims), the {@code default} built-in falls back to a hardcoded
 * generator that places a single stone pressure plate at the interior floor
 * centre, and custom contents place nothing.
 */
public final class CarriageContentsTemplate {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CarriageContentsTemplate() {}

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
     * {@link CarriageTemplate#placeAt} are untouched.
     */
    public static void placeAt(ServerLevel level, BlockPos carriageOrigin, CarriageContents contents, CarriageDims dims) {
        Vec3i size = interiorSize(dims);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            // Carriage at its minimum dims has zero or negative interior
            // along at least one axis — no room for contents.
            return;
        }
        BlockPos origin = interiorOrigin(carriageOrigin);

        Optional<StructureTemplate> stored = CarriageContentsStore.get(level, contents, size);
        if (stored.isPresent()) {
            stampTemplate(level, origin, stored.get());
            LOGGER.info("[DungeonTrain] Placed contents {} at {} source=stored", contents.id(), origin);
            return;
        }
        if (contents instanceof CarriageContents.Builtin b && b.type() == ContentsType.DEFAULT) {
            legacyPlaceDefault(level, origin, size);
            LOGGER.info("[DungeonTrain] Placed contents {} at {} source=legacy", contents.id(), origin);
            return;
        }
        LOGGER.warn("[DungeonTrain] No contents placed — contents={} origin={} reason=no-nbt-no-fallback. Check {} exists and matches interior size {}x{}x{}.",
            contents.id(), origin, CarriageContentsStore.fileFor(contents),
            size.getX(), size.getY(), size.getZ());
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

    private static void stampTemplate(ServerLevel level, BlockPos origin, StructureTemplate template) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
    }

    /**
     * Capture the interior volume at {@code carriageOrigin} into a fresh
     * {@link StructureTemplate} — used by the contents editor save flow. Air
     * positions are excluded, so an all-air interior saves as a zero-size
     * template (which {@link CarriageContentsStore#save} still writes so the
     * author can clear contents explicitly).
     */
    public static StructureTemplate captureTemplate(ServerLevel level, BlockPos carriageOrigin, CarriageDims dims) {
        Vec3i size = interiorSize(dims);
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, interiorOrigin(carriageOrigin), size, false, Blocks.AIR);
        return template;
    }

    /**
     * Reset the interior volume at {@code carriageOrigin} to air. Used by the
     * editor before re-stamping contents on {@code enter}, so a previously
     * placed contents template doesn't blend with the newly chosen one.
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
    }
}
