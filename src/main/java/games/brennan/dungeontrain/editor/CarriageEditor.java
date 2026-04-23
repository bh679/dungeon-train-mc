package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTemplate;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Editor plots for {@link CarriageType}s — fixed high-Y overworld locations
 * where players build their own carriage variants. Four plots are arranged
 * along +X so all variants are visible at once.
 *
 * Session state (pre-enter position + dimension + look angles) is kept
 * per-player in RAM. Lost on server restart, which is acceptable for an
 * OP-only dev tool.
 */
public final class CarriageEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PLOT_Y = 250;
    private static final int PLOT_Z = 0;
    private static final int PLOT_STEP_X = 20;
    private static final int FIRST_PLOT_X = 0;

    private static final BlockState OUTLINE_BLOCK = Blocks.BARRIER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final Map<CarriageType, BlockPos> PLOT_ORIGINS = buildPlots();

    private static Map<CarriageType, BlockPos> buildPlots() {
        Map<CarriageType, BlockPos> map = new EnumMap<>(CarriageType.class);
        int x = FIRST_PLOT_X;
        for (CarriageType type : CarriageType.values()) {
            map.put(type, new BlockPos(x, PLOT_Y, PLOT_Z));
            x += PLOT_STEP_X;
        }
        return Map.copyOf(map);
    }

    private CarriageEditor() {}

    public static BlockPos plotOrigin(CarriageType type) {
        return PLOT_ORIGINS.get(type);
    }

    /**
     * Returns the carriage type whose plot contains {@code pos} (within the
     * footprint plus 1-block outline margin), or {@code null} if none.
     */
    public static CarriageType plotContaining(BlockPos pos, CarriageDims dims) {
        for (Map.Entry<CarriageType, BlockPos> entry : PLOT_ORIGINS.entrySet()) {
            BlockPos o = entry.getValue();
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + dims.length()
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + dims.height()
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + dims.width()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Teleport {@code player} to the plot for {@code type}: save return
     * position, clear the footprint, stamp the current template (or fallback
     * geometry) so the player sees what would spawn today, then place the
     * barrier-block cage around the footprint.
     */
    public static void enter(ServerPlayer player, CarriageType type) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        BlockPos origin = PLOT_ORIGINS.get(type);
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        EditorSessions.saveIfAbsent(player.getUUID(), new EditorSessions.Session(
            player.level().dimension(),
            player.position(),
            player.getYRot(),
            player.getXRot()
        ));

        CarriageTemplate.eraseAt(overworld, origin, dims);
        CarriageTemplate.placeAt(overworld, origin, type, dims);
        setOutline(overworld, origin, OUTLINE_BLOCK, dims);

        double tx = origin.getX() + dims.length() / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + dims.width() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Editor enter: {} -> {} plot at {} dims={}x{}x{}",
            player.getName().getString(), type, origin, dims.length(), dims.width(), dims.height());
    }

    /**
     * Capture the {@code length × height × width} region at the plot for
     * {@code type} into a fresh {@link StructureTemplate} and persist it via
     * {@link CarriageTemplateStore}. Air positions are excluded so the saved
     * template only describes placed blocks.
     */
    public static void save(ServerPlayer player, CarriageType type) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        BlockPos origin = PLOT_ORIGINS.get(type);
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        StructureTemplate template = new StructureTemplate();
        Vec3i size = new Vec3i(dims.length(), dims.height(), dims.width());
        template.fillFromWorld(overworld, origin, size, false, Blocks.AIR);
        CarriageTemplateStore.save(type, template);

        LOGGER.info("[DungeonTrain] Editor save: {} -> {} template dims={}x{}x{}",
            player.getName().getString(), type, dims.length(), dims.width(), dims.height());
    }

    /** Restore player to pre-enter position/dimension. Returns false if no session. */
    public static boolean exit(ServerPlayer player) {
        EditorSessions.Session session = EditorSessions.remove(player.getUUID());
        if (session == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        ServerLevel dim = server.getLevel(session.dimension());
        if (dim == null) return false;
        player.teleportTo(dim, session.pos().x, session.pos().y, session.pos().z,
            session.yaw(), session.pitch());
        return true;
    }

    /**
     * Draw the cage: barrier blocks along the 12 edges of the bounding box
     * that sits 1 block outside the {@code length × height × width} footprint.
     * Faces are left empty so the player can fly in and out freely; barriers
     * are invisible in survival and render as translucent red in
     * creative/spectator.
     */
    private static void setOutline(ServerLevel level, BlockPos origin, BlockState state, CarriageDims dims) {
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + dims.length();
        int y1 = origin.getY() + dims.height();
        int z1 = origin.getZ() + dims.width();

        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int extremes = (x == x0 || x == x1 ? 1 : 0)
                        + (y == y0 || y == y1 ? 1 : 0)
                        + (z == z0 || z == z1 ? 1 : 0);
                    if (extremes < 2) continue;
                    level.setBlock(new BlockPos(x, y, z), state, 3);
                }
            }
        }
    }
}
