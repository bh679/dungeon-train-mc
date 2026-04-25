package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.tunnel.LegacyTunnelPaint;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.tunnel.TunnelTemplate;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Editor plots for {@link TunnelVariant}s — mirror of {@link CarriageEditor}.
 * Two plots sit past the carriage row at {@code x = 80} / {@code x = 100},
 * {@code y = 250}, {@code z = 0}, so the tunnel and carriage plots are all
 * visible from a single vantage point when debugging templates.
 *
 * <p>Session state (pre-enter position) is kept per-player in RAM — same
 * shape as {@link CarriageEditor}'s {@code Session} but in a separate map.
 * On {@code /dungeontrain editor exit}, the dispatcher tries
 * {@link #exit(ServerPlayer)} first; if the player has no tunnel session,
 * it falls through to {@link CarriageEditor#exit(ServerPlayer)}. This lets a
 * player who enters a carriage plot, then a tunnel plot, run exit twice to
 * step back through both sessions.</p>
 */
public final class TunnelEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int PLOT_Y = 250;
    private static final int PLOT_Z = 0;
    private static final int FIRST_PLOT_X = 80;
    private static final int PLOT_STEP_X = 20;

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static final Map<TunnelVariant, BlockPos> PLOT_ORIGINS = buildPlots();

    private static Map<TunnelVariant, BlockPos> buildPlots() {
        Map<TunnelVariant, BlockPos> map = new EnumMap<>(TunnelVariant.class);
        int x = FIRST_PLOT_X;
        for (TunnelVariant variant : TunnelVariant.values()) {
            map.put(variant, new BlockPos(x, PLOT_Y, PLOT_Z));
            x += PLOT_STEP_X;
        }
        return Map.copyOf(map);
    }

    private TunnelEditor() {}

    public static BlockPos plotOrigin(TunnelVariant variant) {
        return PLOT_ORIGINS.get(variant);
    }

    /**
     * Returns the tunnel variant whose plot contains {@code pos} (within the
     * 10×14×13 footprint plus 1-block outline margin), or {@code null} if
     * none.
     */
    public static TunnelVariant plotContaining(BlockPos pos) {
        for (Map.Entry<TunnelVariant, BlockPos> entry : PLOT_ORIGINS.entrySet()) {
            BlockPos o = entry.getValue();
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + TunnelTemplate.LENGTH
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + TunnelTemplate.HEIGHT
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + TunnelTemplate.WIDTH) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Teleport {@code player} to the plot for {@code variant}: save return
     * position, clear the footprint, stamp the saved template (or render the
     * procedural fallback) so the player sees what would spawn today, then
     * draw the barrier cage around the footprint.
     */
    public static void enter(ServerPlayer player, TunnelVariant variant) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        BlockPos origin = PLOT_ORIGINS.get(variant);

        SESSIONS.putIfAbsent(player.getUUID(), new Session(
            player.level().dimension(),
            player.position(),
            player.getYRot(),
            player.getXRot()
        ));

        stampPlot(overworld, variant);

        double tx = origin.getX() + TunnelTemplate.LENGTH / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + TunnelTemplate.WIDTH / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Editor enter: {} -> tunnel_{} plot at {}",
            player.getName().getString(), variant.name().toLowerCase(java.util.Locale.ROOT), origin);
    }

    /**
     * Erase, place, and cage the plot for {@code variant} without teleporting.
     * Used by {@link #enter} and by category-wide stamps
     * ({@code /dt editor tracks}). Idempotent.
     */
    public static void stampPlot(ServerLevel overworld, TunnelVariant variant) {
        BlockPos origin = PLOT_ORIGINS.get(variant);
        TunnelTemplate.eraseAt(overworld, origin);
        TrackKind kind = TunnelTemplateStore.tunnelKind(variant);
        String activeName = TrackEditorState.activeName(kind);
        if (variant == TunnelVariant.SECTION) {
            TunnelTemplate.placeSectionNamed(overworld, origin, activeName);
        } else {
            // Always render the unmirrored (entrance) orientation in the editor;
            // the exit variant at world paint time is the same template with
            // StructurePlaceSettings.setMirror(Mirror.FRONT_BACK).
            TunnelTemplate.placePortalNamed(overworld, origin, false, activeName);
        }
        // Mark the corner wedges above the arch with STRUCTURE_VOID so they
        // are stripped from the saved template and the in-world stamp leaves
        // whatever's at those positions (mountain rock) alone.
        TunnelGeometry tg = LegacyTunnelPaint.geometryForPlot(origin);
        LegacyTunnelPaint.fillCornersWithVoid(overworld, origin.getX(), tg);
        setOutline(overworld, origin, OUTLINE_BLOCK);
    }

    /**
     * Erase the plot for {@code variant} — footprint + outline cage cleared
     * to air. Used on category switch and editor exit.
     */
    public static void clearPlot(ServerLevel overworld, TunnelVariant variant) {
        BlockPos origin = PLOT_ORIGINS.get(variant);
        TunnelTemplate.eraseAt(overworld, origin);
        setOutline(overworld, origin, Blocks.AIR.defaultBlockState());
    }

    /**
     * Capture the 10×14×13 region at the plot for {@code variant} into a
     * fresh {@link StructureTemplate} and persist it via
     * {@link TunnelTemplateStore}.
     *
     * <p><b>Ignore block is {@code STRUCTURE_VOID}</b> (not {@code null} or
     * {@code AIR}). The editor {@link #enter} pass marks corner wedges
     * above the arch profile as {@code STRUCTURE_VOID} — those positions
     * sit inside the bounding box but are rock in-world, so we strip them
     * from the saved template. Air inside the tunnel IS captured, so the
     * stamp carves out the interior when placed underground. Anything the
     * user explicitly puts in a corner (e.g. glowstone) overrides the
     * void and is captured normally.</p>
     */
    public static void save(ServerPlayer player, TunnelVariant variant) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        BlockPos origin = PLOT_ORIGINS.get(variant);

        StructureTemplate template = new StructureTemplate();
        Vec3i size = new Vec3i(TunnelTemplate.LENGTH, TunnelTemplate.HEIGHT, TunnelTemplate.WIDTH);
        template.fillFromWorld(overworld, origin, size, false, Blocks.STRUCTURE_VOID);

        TrackKind kind = TunnelTemplateStore.tunnelKind(variant);
        String activeName = TrackEditorState.activeName(kind);
        TrackVariantStore.save(kind, activeName, template);

        LOGGER.info("[DungeonTrain] Editor save: {} -> tunnel_{} template {}",
            player.getName().getString(),
            variant.name().toLowerCase(java.util.Locale.ROOT), activeName);
    }

    /**
     * Restore player to pre-enter position/dimension. Returns false if no
     * session — the caller should then try {@link CarriageEditor#exit}.
     */
    public static boolean exit(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
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
     * that sits 1 block outside the 10×14×13 footprint.
     */
    private static void setOutline(ServerLevel level, BlockPos origin, BlockState state) {
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + TunnelTemplate.LENGTH;
        int y1 = origin.getY() + TunnelTemplate.HEIGHT;
        int z1 = origin.getZ() + TunnelTemplate.WIDTH;

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
