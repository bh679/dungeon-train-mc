package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomLengths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
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
import java.util.Map;
import java.util.UUID;

/**
 * Multi-plot editor for portal pocket rooms — the fourth editor category.
 *
 * <p>Modelled on {@link TunnelEditor}: one plot per registered variant name, laid out along
 * {@code +Z} at the {@link TrackSidePlots#X_PORTALS} column, with a bedrock cage and a dirty-check
 * snapshot per plot. Pre-enter session state is per-player; on exit the dispatcher tries
 * {@link #exit} first, falling back to {@link CarriageEditor#exit}.</p>
 *
 * <p><b>The one thing that is not like the others: length.</b> Every other plot kind has a footprint
 * fixed in code. A portal room's length is the author's — it is the distance a player walks
 * underneath, which is the dial the portal exists to turn — so the plot is stamped at whatever
 * {@link PortalRoomLengths} says, and {@link #setLength} restamps it at a new one. The built-in
 * geometry fills the plot whenever no template of exactly that size has been authored yet, which is
 * what gives the first author something to edit rather than an empty box.</p>
 */
public final class PortalRoomEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch,
                          GameType previousGameType) {}

    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private PortalRoomEditor() {}

    /** Every registered room name, {@code default} first. */
    public static java.util.List<String> names() {
        return TrackVariantRegistry.namesFor(TrackKind.PORTAL_ROOM);
    }

    /** Plot origin for {@code name}. */
    public static BlockPos plotOrigin(String name, CarriageDims dims) {
        return TrackSidePlots.plotOrigin(TrackKind.PORTAL_ROOM, name, dims);
    }

    /** The box {@code name}'s plot occupies — the length is per-variant, the rest per-world. */
    public static Vec3i plotSize(String name, CarriageDims dims) {
        return PortalRoomLayout.sizeOfLength(dims, PortalRoomLengths.lengthOf(name));
    }

    /**
     * The room name whose plot contains {@code pos}, or null. Includes the 1-block outline-cage
     * margin and the same +2 Y headroom every other plot uses for a player who landed on the cage.
     */
    public static String plotContaining(BlockPos pos, CarriageDims dims) {
        for (String name : names()) {
            BlockPos o = plotOrigin(name, dims);
            Vec3i size = plotSize(name, dims);
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + size.getX()
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + size.getY() + 2
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + size.getZ()) {
                return name;
            }
        }
        return null;
    }

    /** Teleport to {@code name}'s plot, stamping every room plot first. */
    public static void enter(ServerPlayer player, String name) {
        enter(player, name, true);
    }

    public static void enter(ServerPlayer player, String name, boolean onTop) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        // Prime every room's length from its template before anything reads the layout: the plot
        // grid has to know how long each room is, and only the templates know.
        primeLengths(overworld, dims);

        BlockPos origin = plotOrigin(name, dims);
        Vec3i size = plotSize(name, dims);

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

        stampAllPlots(overworld, dims);

        double tx = origin.getX() + size.getX() / 2.0;
        double ty = onTop ? origin.getY() + size.getY() + 1.0 : origin.getY() + 1.0;
        double tz = origin.getZ() + size.getZ() / 2.0;
        player.teleportTo(overworld, tx, ty, tz, player.getYRot(), player.getXRot());

        player.sendSystemMessage(Component.literal(
            "[DungeonTrain] Portal room editor: this is the room between a portal's two corridors. "
            + "Keep the way through clear on the walkway centre line — the corridors open onto it "
            + "at both ends. Change the room's length with /dt editor portals length <blocks>."));

        LOGGER.info("[DungeonTrain] Editor enter: {} -> portal room '{}' plot at {} ({} long, {} variants)",
            player.getName().getString(), name, origin, size.getX(), names().size());
    }

    /**
     * Load every registered room's template once so {@link PortalRoomLengths} knows how long each
     * one is. The plot layout resolves positions without a level to hand, so it cannot do this
     * itself.
     */
    public static void primeLengths(ServerLevel overworld, CarriageDims dims) {
        for (String name : names()) {
            PortalRoomTemplateStore.get(overworld, name, dims);
        }
    }

    /** Erase + restamp every registered room plot. Idempotent. */
    public static void stampAllPlots(ServerLevel overworld, CarriageDims dims) {
        for (String name : names()) {
            stampPlot(overworld, name, dims);
        }
    }

    /**
     * Erase + restamp the single plot for {@code name} — the authored template when one exists at
     * the plot's current length, the built-in room otherwise.
     */
    public static void stampPlot(ServerLevel overworld, String name, CarriageDims dims) {
        // Load first: the plot's length comes from the template, and until it has been read once
        // this session PortalRoomLengths only knows the built-in figure. Without this a room
        // authored at 21 blocks stamps as an 11-block built-in one after every server restart.
        PortalRoomTemplateStore.get(overworld, name, dims);

        BlockPos origin = plotOrigin(name, dims);
        Vec3i size = plotSize(name, dims);

        EditorPlotEntityClearer.discardNonPlayersIn(overworld, origin, size);
        PortalCarriageBuilder.stampRoomAt(overworld, origin, dims, name, size, /*relight*/ true);
        setOutline(overworld, origin, size, OUTLINE_BLOCK);
        captureSnapshot(overworld, origin, size, name);
    }

    /** Erase every room plot. */
    public static void clearAllPlots(ServerLevel overworld, CarriageDims dims) {
        for (String name : names()) {
            clearPlot(overworld, name, dims);
        }
    }

    /** Erase a single room plot — interior + outline cleared to air. */
    public static void clearPlot(ServerLevel overworld, String name, CarriageDims dims) {
        // Same reason as stampPlot: clear the box that is actually there, not the built-in one.
        PortalRoomTemplateStore.get(overworld, name, dims);

        BlockPos origin = plotOrigin(name, dims);
        Vec3i size = plotSize(name, dims);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    overworld.setBlock(
                        pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz), air, 3);
                }
            }
        }
        setOutline(overworld, origin, size, air);
        EditorPlotSnapshots.clear(snapshotKey(name));
    }

    /**
     * Restamp {@code name}'s plot at a new length.
     *
     * <p>Destructive by nature: the box changes size, so whatever was authored in the old one is
     * replaced with the built-in room at the new length. The change is only a plot state until the
     * next save writes a template of that length.</p>
     */
    public static int setLength(ServerLevel overworld, String name, int length, CarriageDims dims) {
        clearPlot(overworld, name, dims);
        int clamped = PortalRoomLayout.clampLength(length);
        PortalRoomLengths.pendingLength(name, clamped);
        stampPlot(overworld, name, dims);
        LOGGER.info("[DungeonTrain] Portal room '{}' plot restamped at length {}", name, clamped);
        return clamped;
    }

    /** Snapshot the freshly-stamped plot for {@link EditorDirtyCheck}'s baseline. */
    private static void captureSnapshot(ServerLevel overworld, BlockPos origin, Vec3i size, String name) {
        EditorPlotSnapshots.capture(snapshotKey(name), overworld, origin,
            size.getX(), size.getY(), size.getZ());
    }

    /** Snapshot key shared with {@link EditorDirtyCheck}. */
    public static String snapshotKey(String name) {
        return EditorPlotSnapshots.key("portals", "portal_room:" + name);
    }

    /**
     * Capture the plot the player is standing in as {@code name}'s template.
     *
     * <p>When {@link EditorDevMode} is on, also writes into the source tree at
     * {@code src/main/resources/data/dungeontrain/portals/room/} so authored rooms ship with the
     * next build — parity with {@link TunnelEditor#save}.</p>
     */
    public static SaveResult save(ServerPlayer player, String name) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        BlockPos origin = plotOrigin(name, dims);
        Vec3i size = plotSize(name, dims);

        // Author edits one master octant; rebuild the rest in-world before capture, so the stored
        // template (and every room stamped from it) matches what the author sees.
        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, name, size);
        EditorVariantMirror.rebuildFromMaster(overworld,
            new BlockVariantPlot.TrackPlot(TrackKind.PORTAL_ROOM, name, origin, size));
        EditorMirror.rebuildFromMaster(overworld, origin, size,
            sidecar.mirrorX(), sidecar.mirrorY(), sidecar.mirrorZ(),
            EditorMirror.markersOf(sidecar.entries()));

        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(overworld, origin, size, false, Blocks.STRUCTURE_VOID);

        PortalRoomTemplateStore.save(name, template);

        captureSnapshot(overworld, origin, size, name);

        LOGGER.info("[DungeonTrain] Editor save: {} -> portal room '{}' template ({} long)",
            player.getName().getString(), name, size.getX());

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            PortalRoomTemplateStore.saveToSource(name, template);
            try {
                TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, name, size)
                    .saveToSource(TrackKind.PORTAL_ROOM, name);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Portal room save: variant sidecar source write failed for {}: {}",
                    name, e.toString());
            }
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Portal room save: source write failed for {}: {}",
                name, e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /**
     * Restore player to pre-enter position/dimension/game mode. Returns false if no session —
     * caller should then try {@link CarriageEditor#exit}.
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

    /** Draw the bedrock cage along the 12 edges of the plot. */
    private static void setOutline(ServerLevel level, BlockPos origin, Vec3i size, BlockState state) {
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + size.getX();
        int y1 = origin.getY() + size.getY();
        int z1 = origin.getZ() + size.getZ();

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
