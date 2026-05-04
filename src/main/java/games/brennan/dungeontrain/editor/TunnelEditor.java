package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.LegacyTunnelPaint;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Multi-plot editor for tunnel kinds. Layout follows
 * {@link TrackSidePlots}: SECTION at the {@link TrackSidePlots#X_TUNNELS}
 * column, PORTAL stacked above SECTION on Y; each kind's registered
 * variant names lay out along {@code +Z} with
 * {@link EditorLayout#GAP}-block spacing.
 *
 * <p>Pre-enter session state is per-player; on exit the dispatcher tries
 * {@link #exit} first, falling back to {@link CarriageEditor#exit}.</p>
 */
public final class TunnelEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch, GameType previousGameType) {}

    /** Resolved variant + name pair for a player position. */
    public record TunnelPlot(TunnelVariant variant, String name) {}

    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private TunnelEditor() {}

    /** Plot origin for {@code (variant, default)}. */
    public static BlockPos plotOrigin(TunnelVariant variant) {
        CarriageDims dims = CarriageDims.clamp(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
        return TrackSidePlots.plotOriginDefault(TunnelTemplateStore.tunnelKind(variant), dims);
    }

    /** Plot origin for {@code (variant, name)}. Tunnel dims are fixed; CarriageDims is ignored. */
    public static BlockPos plotOrigin(TunnelVariant variant, String name) {
        CarriageDims dims = CarriageDims.clamp(
            CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT);
        return TrackSidePlots.plotOrigin(TunnelTemplateStore.tunnelKind(variant), name, dims);
    }

    /** Returns the variant whose plot contains {@code pos}, or null. Legacy entry point. */
    public static TunnelVariant plotContaining(BlockPos pos) {
        TunnelPlot loc = plotContainingNamed(pos);
        return loc != null ? loc.variant() : null;
    }

    /**
     * Returns the {@code (variant, name)} pair whose plot contains
     * {@code pos}, or null. Includes the 1-block outline-cage margin.
     */
    public static TunnelPlot plotContainingNamed(BlockPos pos) {
        for (TunnelVariant variant : TunnelVariant.values()) {
            for (String name : TrackVariantRegistry.namesFor(TunnelTemplateStore.tunnelKind(variant))) {
                BlockPos o = plotOrigin(variant, name);
                if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + TunnelPlacer.LENGTH
                    && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + TunnelPlacer.HEIGHT
                    && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + TunnelPlacer.WIDTH) {
                    return new TunnelPlot(variant, name);
                }
            }
        }
        return null;
    }

    /** Teleport to the default plot for {@code variant}, stamping every variant first. */
    public static void enter(ServerPlayer player, TunnelVariant variant) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        BlockPos origin = plotOrigin(variant, TrackKind.DEFAULT_NAME);

        if (!SESSIONS.containsKey(player.getUUID())) {
            GameType previous = player.gameMode.getGameModeForPlayer();
            SESSIONS.put(player.getUUID(), new Session(
                player.level().dimension(),
                player.position(),
                player.getYRot(),
                player.getXRot(),
                previous
            ));
            if (previous != GameType.CREATIVE) {
                player.setGameMode(GameType.CREATIVE);
            }
        }

        stampPlot(overworld, variant);

        double tx = origin.getX() + TunnelPlacer.LENGTH / 2.0;
        double ty = origin.getY() + 1.0;
        double tz = origin.getZ() + TunnelPlacer.WIDTH / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        LOGGER.info("[DungeonTrain] Editor enter: {} -> tunnel_{} default plot at {} ({} variants)",
            player.getName().getString(), variant.name().toLowerCase(java.util.Locale.ROOT), origin,
            TrackVariantRegistry.namesFor(TunnelTemplateStore.tunnelKind(variant)).size());
    }

    /** Erase + restamp every registered variant for {@code variant}. Idempotent. */
    public static void stampPlot(ServerLevel overworld, TunnelVariant variant) {
        for (String name : TrackVariantRegistry.namesFor(TunnelTemplateStore.tunnelKind(variant))) {
            stampPlot(overworld, variant, name);
        }
    }

    /** Erase + restamp the single plot for {@code (variant, name)}. */
    public static void stampPlot(ServerLevel overworld, TunnelVariant variant, String name) {
        BlockPos origin = plotOrigin(variant, name);
        TunnelPlacer.eraseAt(overworld, origin);
        if (variant == TunnelVariant.SECTION) {
            TunnelPlacer.placeSectionNamed(overworld, origin, name);
        } else {
            // Always render the unmirrored (entrance) orientation in the editor;
            // the exit variant at world paint time is the same template with
            // StructurePlaceSettings.setMirror(Mirror.FRONT_BACK).
            TunnelPlacer.placePortalNamed(overworld, origin, false, name);
        }
        // STRUCTURE_VOID corner-wedge overlay so saved templates strip those
        // positions and in-world stamps leave the surrounding rock alone.
        TunnelGeometry tg = LegacyTunnelPaint.geometryForPlot(origin);
        LegacyTunnelPaint.fillCornersWithVoid(overworld, origin.getX(), tg);
        setOutline(overworld, origin, OUTLINE_BLOCK);
        captureTunnelSnapshot(overworld, origin, variant, name);
    }

    /** Erase every variant plot for {@code variant}. */
    public static void clearPlot(ServerLevel overworld, TunnelVariant variant) {
        for (String name : TrackVariantRegistry.namesFor(TunnelTemplateStore.tunnelKind(variant))) {
            BlockPos origin = plotOrigin(variant, name);
            TunnelPlacer.eraseAt(overworld, origin);
            setOutline(overworld, origin, Blocks.AIR.defaultBlockState());
            EditorPlotSnapshots.clear(tunnelSnapshotKey(variant, name));
        }
    }

    /** Erase a single named variant plot for {@code variant} — interior + outline cleared to air. */
    public static void clearPlot(ServerLevel overworld, TunnelVariant variant, String name) {
        BlockPos origin = plotOrigin(variant, name);
        TunnelPlacer.eraseAt(overworld, origin);
        setOutline(overworld, origin, Blocks.AIR.defaultBlockState());
        EditorPlotSnapshots.clear(tunnelSnapshotKey(variant, name));
    }

    /** Snapshot the freshly-stamped tunnel region for {@link EditorDirtyCheck}'s baseline. */
    private static void captureTunnelSnapshot(ServerLevel overworld, BlockPos origin, TunnelVariant variant, String name) {
        EditorPlotSnapshots.capture(tunnelSnapshotKey(variant, name), overworld, origin,
            TunnelPlacer.LENGTH, TunnelPlacer.HEIGHT, TunnelPlacer.WIDTH);
    }

    /** Snapshot key shared with {@link EditorDirtyCheck} for tunnel rows. */
    public static String tunnelSnapshotKey(TunnelVariant variant, String name) {
        return EditorPlotSnapshots.key("tracks",
            "tunnel_" + variant.name().toLowerCase(java.util.Locale.ROOT) + ":" + name);
    }

    /**
     * Save the captured 10×14×13 region for the {@code (variant, name)}
     * the player is currently standing in. When {@link EditorDevMode} is on,
     * also writes the captured template (and its variant-blocks sidecar) into
     * the source tree at {@code src/main/resources/data/dungeontrain/tunnels/...}
     * so authored tunnels ship with the next build — parity with
     * {@link TrackEditor#save} and {@link PillarEditor#save}.
     */
    public static SaveResult save(ServerPlayer player, TunnelVariant variant) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();

        TunnelPlot loc = plotContainingNamed(player.blockPosition());
        if (loc == null || loc.variant() != variant) {
            throw new IOException("Player is not inside any tunnel_"
                + variant.name().toLowerCase(java.util.Locale.ROOT) + " plot.");
        }
        String name = loc.name();
        BlockPos origin = plotOrigin(variant, name);

        StructureTemplate template = new StructureTemplate();
        Vec3i size = new Vec3i(TunnelPlacer.LENGTH, TunnelPlacer.HEIGHT, TunnelPlacer.WIDTH);
        template.fillFromWorld(overworld, origin, size, false, Blocks.STRUCTURE_VOID);

        TrackKind kind = TunnelTemplateStore.tunnelKind(variant);
        TrackVariantStore.save(kind, name, template);

        // Refresh the dirty-check baseline.
        captureTunnelSnapshot(overworld, origin, variant, name);

        LOGGER.info("[DungeonTrain] Editor save: {} -> tunnel_{}/{} template",
            player.getName().getString(),
            variant.name().toLowerCase(java.util.Locale.ROOT), name);

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            TunnelTemplateStore.saveToSource(variant, name, template);
            try {
                Vec3i footprint = kind.dims(CarriageDims.clamp(
                    CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT));
                TrackVariantBlocks.loadFor(kind, name, footprint).saveToSource(kind, name);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Tunnel editor save: variant sidecar source write failed for {}: {}",
                    variant.name().toLowerCase(java.util.Locale.ROOT), e.toString());
            }
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Tunnel editor save: source write failed for {}: {}",
                variant.name().toLowerCase(java.util.Locale.ROOT), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /**
     * Restore player to pre-enter position/dimension/game mode. Returns false
     * if no session — caller should then try {@link CarriageEditor#exit}.
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
        if (player.gameMode.getGameModeForPlayer() != session.previousGameType()) {
            player.setGameMode(session.previousGameType());
        }
        return true;
    }

    /** Draw the bedrock cage along the 12 edges of the 10×14×13 plot. */
    private static void setOutline(ServerLevel level, BlockPos origin, BlockState state) {
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + TunnelPlacer.LENGTH;
        int y1 = origin.getY() + TunnelPlacer.HEIGHT;
        int z1 = origin.getZ() + TunnelPlacer.WIDTH;

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

    /** Reference to silence "unused" warnings for the world-data import. */
    @SuppressWarnings("unused")
    private static final Class<?> WORLD_DATA = DungeonTrainWorldData.class;

    /** Reference to silence "unused" warning for the list import. */
    @SuppressWarnings("unused")
    private static final Class<?> LIST_REF = List.class;
}
