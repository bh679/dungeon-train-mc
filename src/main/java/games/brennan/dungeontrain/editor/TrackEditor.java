package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackPalette;
import games.brennan.dungeontrain.track.TrackTemplate;
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
import java.util.Optional;

/**
 * Editor plot for the open-air track tile — fixed overworld location at
 * {@code (0, 250, 60)}, past the pillar row at {@code Z=40}. OPs build a
 * 4×2×W tile (X=tile length × Y=bed+rail rows × Z=carriage width), which
 * {@link games.brennan.dungeontrain.track.TrackGenerator} then re-stamps at
 * every world X along the train corridor.
 *
 * <p>Reuses the {@link CarriageEditor} session map via
 * {@link CarriageEditor#rememberReturn} so a single {@code /dungeontrain editor exit}
 * command restores the player regardless of which editor they entered.</p>
 */
public final class TrackEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PLOT_X = 0;
    private static final int PLOT_Y = 250;
    /** Track row Z-origin. Pillar row sits at {@code Z=40} with width up to
     *  {@code dims.width()} (default 7), so 60 keeps a clean gap even at
     *  max carriage width. */
    private static final int PLOT_Z = 60;

    private static final BlockState OUTLINE_BLOCK = Blocks.BARRIER.defaultBlockState();

    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    private TrackEditor() {}

    /** Fixed plot origin — the tile's min corner. */
    public static BlockPos plotOrigin() {
        return new BlockPos(PLOT_X, PLOT_Y, PLOT_Z);
    }

    /**
     * True if {@code pos} sits inside the 4×2×W plot plus 1-block outline
     * margin. Used to route {@code /dungeontrain editor save} to
     * {@link #save} when the player is standing in the plot.
     */
    public static boolean plotContaining(BlockPos pos, CarriageDims dims) {
        BlockPos o = plotOrigin();
        int w = dims.width();
        return pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + TrackTemplate.TILE_LENGTH
            && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + TrackTemplate.HEIGHT
            && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + w;
    }

    /**
     * Teleport {@code player} to the track plot: save return position, erase
     * the 4×2×W footprint, stamp the current template (or hardcoded fallback)
     * so the player sees what would paint today, then draw the barrier cage.
     */
    public static void enter(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin();

        CarriageEditor.rememberReturn(player);

        eraseAt(overworld, origin, dims);
        stampCurrent(overworld, origin, dims);
        setOutline(overworld, origin, dims);

        double tx = origin.getX() + TrackTemplate.TILE_LENGTH / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + dims.width() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Track editor enter: {} -> plot at {} (size={}x{}x{})",
            player.getName().getString(), origin,
            TrackTemplate.TILE_LENGTH, TrackTemplate.HEIGHT, dims.width());
    }

    /**
     * Capture the {@code 4 × 2 × width} region at the plot into a fresh
     * {@link StructureTemplate} and persist it via {@link TrackTemplateStore}.
     *
     * <p>Air is the ignore block, so air cells are NOT captured — null at
     * runtime means "leave passable, don't paint" (useful for viaduct gaps or
     * mid-tile holes). Blocks placed by the user, including rails at any Z,
     * are captured verbatim.</p>
     *
     * <p>When {@link EditorDevMode} is on, the template is also written to the
     * source tree so it ships with the next build.</p>
     */
    public static SaveResult save(ServerPlayer player) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin();

        StructureTemplate template = captureTemplate(overworld, origin, dims);
        TrackTemplateStore.save(template);

        LOGGER.info("[DungeonTrain] Track editor save: {} -> template ({}x{}x{})",
            player.getName().getString(),
            TrackTemplate.TILE_LENGTH, TrackTemplate.HEIGHT, dims.width());

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            TrackTemplateStore.saveToSource(template);
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Track editor save: source write failed: {}", e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /** Erase a 4×2×W region plus 1-block outline margin to air for a clean slate. */
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

    /**
     * Fill the 4×2×W footprint with the stored template if any, else stamp the
     * hardcoded fallback (bed at every Z on {@code y=0}, rails at the two
     * inner-edge Z rows on {@code y=1}) across every X offset so the player
     * has something concrete to edit.
     */
    private static void stampCurrent(ServerLevel level, BlockPos origin, CarriageDims dims) {
        Optional<StructureTemplate> stored = TrackTemplateStore.get(level, dims);
        if (stored.isPresent()) {
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            stored.get().placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
            return;
        }
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

    /** Barrier cage: 12 edges of a bounding box 1 block outside the 4×2×W footprint. */
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
