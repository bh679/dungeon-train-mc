package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackPalette;
import games.brennan.dungeontrain.track.TrackTemplate;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Editor plots for the open-air track tile. Mirrors
 * {@link CarriagePartEditor}: one plot per registered variant name in
 * {@link TrackVariantRegistry}, laid out along {@code +Z} with
 * {@link EditorLayout#GAP}-block spacing between footprints, sharing the
 * column at {@link TrackSidePlots#X_TRACK}. Plot positioning lives in
 * {@link TrackSidePlots} so every track-side editor uses the same grid.
 *
 * <p>{@link CarriageEditor#rememberReturn} is reused for the session map
 * so a single {@code /dungeontrain editor exit} restores the player no
 * matter which sub-editor they entered last.</p>
 */
public final class TrackEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    private TrackEditor() {}

    /** Plot origin for the synthetic-default variant. */
    public static BlockPos plotOrigin(CarriageDims dims) {
        return TrackSidePlots.plotOriginDefault(TrackKind.TILE, dims);
    }

    /** Plot origin for {@code (TILE, name)}. Used by the new/remove command. */
    public static BlockPos plotOrigin(String name, CarriageDims dims) {
        return TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
    }

    /**
     * True if {@code pos} sits inside any registered track-tile variant's
     * plot. Used as the legacy entry point for back-compat callers; new
     * code should prefer {@link TrackSidePlots#locate} which returns the
     * matched name.
     */
    public static boolean plotContaining(BlockPos pos, CarriageDims dims) {
        return resolveName(pos, dims) != null;
    }

    /**
     * Resolved variant name for the plot {@code pos} sits inside, or null
     * if outside every track-tile plot. Includes the 1-block outline-cage
     * margin used uniformly across the track-side grid.
     */
    public static String resolveName(BlockPos pos, CarriageDims dims) {
        for (String name : TrackVariantRegistry.namesFor(TrackKind.TILE)) {
            BlockPos o = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
            int w = dims.width();
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + TrackTemplate.TILE_LENGTH
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + TrackTemplate.HEIGHT
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + w) {
                return name;
            }
        }
        return null;
    }

    /**
     * Teleport {@code player} to the default-variant plot. Stamps every
     * registered tile variant's plot first so the row is fully visible.
     */
    public static void enter(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        CarriageEditor.rememberReturn(player);
        stampAllPlots(overworld, dims);

        BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, TrackKind.DEFAULT_NAME, dims);
        double tx = origin.getX() + TrackTemplate.TILE_LENGTH / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + dims.width() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Track editor enter: {} -> default plot at {} ({} variants registered)",
            player.getName().getString(), origin,
            TrackVariantRegistry.namesFor(TrackKind.TILE).size());
    }

    /**
     * Erase + restamp every registered track-tile variant plot. Idempotent.
     * Called from {@link #enter} and from category-wide stamps
     * ({@code /dt editor tracks}).
     */
    public static void stampPlot(ServerLevel overworld, CarriageDims dims) {
        stampAllPlots(overworld, dims);
    }

    /** Erase + restamp the single plot for {@code name}. */
    public static void stampPlot(ServerLevel overworld, String name, CarriageDims dims) {
        BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
        eraseAt(overworld, origin, dims);
        stampNameAt(overworld, origin, name, dims);
        setOutline(overworld, origin, dims);
    }

    /** Erase every track-tile plot — footprint + outline cage cleared to air. */
    public static void clearPlot(ServerLevel overworld, CarriageDims dims) {
        for (String name : TrackVariantRegistry.namesFor(TrackKind.TILE)) {
            BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
            eraseAt(overworld, origin, dims);
        }
    }

    /**
     * Capture the {@code 4 × 2 × width} region at the plot the player is
     * standing in into a fresh {@link StructureTemplate} and persist it
     * via {@link TrackVariantStore} for the resolved {@code name}.
     */
    public static SaveResult save(ServerPlayer player) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        String name = resolveName(player.blockPosition(), dims);
        if (name == null) throw new IOException("Player is not inside any track-tile plot.");

        BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
        StructureTemplate template = captureTemplate(overworld, origin, dims);
        TrackVariantStore.save(TrackKind.TILE, name, template);

        LOGGER.info("[DungeonTrain] Track editor save: {} -> tile/{} ({}x{}x{})",
            player.getName().getString(), name,
            TrackTemplate.TILE_LENGTH, TrackTemplate.HEIGHT, dims.width());

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            TrackVariantStore.saveToSource(TrackKind.TILE, name, template);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Track editor save: source write failed: {}", e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    private static void stampAllPlots(ServerLevel overworld, CarriageDims dims) {
        List<String> names = TrackVariantRegistry.namesFor(TrackKind.TILE);
        for (String name : names) {
            BlockPos origin = TrackSidePlots.plotOrigin(TrackKind.TILE, name, dims);
            eraseAt(overworld, origin, dims);
            stampNameAt(overworld, origin, name, dims);
            setOutline(overworld, origin, dims);
        }
    }

    private static void eraseAt(ServerLevel level, BlockPos origin, CarriageDims dims) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int w = dims.width();
        for (int dx = -1; dx <= TrackTemplate.TILE_LENGTH; dx++) {
            for (int dz = -1; dz <= w; dz++) {
                for (int dy = -1; dy <= TrackTemplate.HEIGHT; dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), air, 3);
                }
            }
        }
    }

    private static void stampNameAt(ServerLevel level, BlockPos origin, String name, CarriageDims dims) {
        Optional<StructureTemplate> stored = TrackVariantStore.get(level, TrackKind.TILE, name, dims);
        if (stored.isPresent()) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            stored.get().placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
            return;
        }
        // Fallback for unauthored "default" — hardcoded bed + 2-rail stamp.
        int w = dims.width();
        for (int dx = 0; dx < TrackTemplate.TILE_LENGTH; dx++) {
            for (int dz = 0; dz < w; dz++) {
                level.setBlock(origin.offset(dx, 0, dz), TrackPalette.BED, 3);
                if (dz == 1 || dz == w - 2) {
                    level.setBlock(origin.offset(dx, 1, dz), TrackPalette.RAIL, 3);
                }
            }
        }
    }

    private static StructureTemplate captureTemplate(ServerLevel level, BlockPos origin, CarriageDims dims) {
        StructureTemplate template = new StructureTemplate();
        Vec3i size = new Vec3i(TrackTemplate.TILE_LENGTH, TrackTemplate.HEIGHT, dims.width());
        template.fillFromWorld(level, origin, size, false, Blocks.AIR);
        return template;
    }

    private static void setOutline(ServerLevel level, BlockPos origin, CarriageDims dims) {
        int w = dims.width();
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + TrackTemplate.TILE_LENGTH;
        int y1 = origin.getY() + TrackTemplate.HEIGHT;
        int z1 = origin.getZ() + w;

        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int extremes = (x == x0 || x == x1 ? 1 : 0)
                        + (y == y0 || y == y1 ? 1 : 0)
                        + (z == z0 || z == z1 ? 1 : 0);
                    if (extremes < 2) continue;
                    level.setBlock(new BlockPos(x, y, z), OUTLINE_BLOCK, 3);
                }
            }
        }
    }
}
