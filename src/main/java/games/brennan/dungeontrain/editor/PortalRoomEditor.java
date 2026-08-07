package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomSizes;
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
 * <p><b>The one thing that is not like the others: size.</b> Every other plot kind has a footprint
 * fixed in code. A portal room's is the author's — free above a floor on every axis, with length
 * free outright, because it is the distance a player walks underneath and that is the dial the
 * portal exists to turn. The plot is stamped at whatever {@link PortalRoomSizes} says, and
 * {@link #setSize} restamps it at a new one. The built-in geometry fills the plot whenever no
 * template of exactly that size has been authored yet, which is what gives the first author
 * something to edit rather than an empty box.</p>
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

    /** The box {@code name}'s plot occupies — per-variant on every axis, clamped to this world. */
    public static Vec3i plotSize(String name, CarriageDims dims) {
        return PortalRoomSizes.sizeOf(name, dims);
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

        // Prime every room's size from its template before anything reads the layout: the plot
        // grid has to know how big each room is, and only the templates know.
        primeSizes(overworld, dims);

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
            + "at both ends. Resize it from the X menu, or with "
            + "/dt editor portals length|width|height <blocks>."));

        LOGGER.info("[DungeonTrain] Editor enter: {} -> portal room '{}' plot at {} ({}x{}x{}, {} variants)",
            player.getName().getString(), name, origin,
            size.getX(), size.getY(), size.getZ(), names().size());
    }

    /**
     * Load every registered room's template once so {@link PortalRoomSizes} knows how big each one
     * is. The plot layout resolves positions without a level to hand, so it cannot do this
     * itself.
     */
    public static void primeSizes(ServerLevel overworld, CarriageDims dims) {
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
     * the plot's current size, the built-in room otherwise.
     */
    public static void stampPlot(ServerLevel overworld, String name, CarriageDims dims) {
        // Load first: the plot's size comes from the template, and until it has been read once
        // this session PortalRoomSizes only knows the built-in figure. Without this a room
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
        clearBox(overworld, new PlotBox(plotOrigin(name, dims), plotSize(name, dims)), name);
    }

    /** Erase an explicit box — used to clear a plot at the position it occupied before a move. */
    private static void clearBox(ServerLevel overworld, PlotBox box, String name) {
        BlockPos origin = box.origin();
        Vec3i size = box.size();
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
     * Create a new room variant seeded from the built-in geometry.
     *
     * <p>The "New" button normally duplicates the source variant's stored template. A portal room
     * ships none — its fallback is code — so there is nothing to copy until somebody has saved one.
     * Seeding from the built-in room gives the new variant exactly the starting point
     * {@code default} itself has.</p>
     *
     * <p>The capture at the end is not optional bookkeeping: without a file on disk the registry's
     * next directory scan would not find the name, and the variant would vanish on server restart.
     * The new room also inherits {@code sourceName}'s size, so duplicating a 21-block room gives
     * another 21-block room rather than silently reverting to the built-in 11.</p>
     */
    public static void createFromBuiltIn(ServerLevel overworld, String sourceName, String name,
                                         CarriageDims dims) throws IOException {
        Vec3i inherited = PortalRoomSizes.sizeOf(sourceName, dims);
        // Registering inserts the name alphabetically, which shifts every plot after it along the
        // cumulatively-packed row — so the whole row is cleared first and restamped after.
        relayout(overworld, dims, () -> {
            PortalRoomSizes.pending(name, inherited);
            TrackVariantRegistry.register(TrackKind.PORTAL_ROOM, name);
        });

        BlockPos origin = plotOrigin(name, dims);
        Vec3i size = plotSize(name, dims);
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(overworld, origin, size, false, Blocks.STRUCTURE_VOID);
        PortalRoomTemplateStore.save(name, template);

        // Fresh baseline, or the brand-new plot reads as already edited.
        captureSnapshot(overworld, origin, size, name);

        LOGGER.info("[DungeonTrain] Portal room '{}' created from the built-in room ({}x{}x{})",
            name, size.getX(), size.getY(), size.getZ());
    }

    /** Which axis of a room a size command addresses. */
    public enum Axis { LENGTH, WIDTH, HEIGHT }

    /**
     * Restamp {@code name}'s plot with one axis changed.
     *
     * <p>Destructive by nature: the box changes size, so whatever was authored in the old one is
     * replaced with the built-in room at the new size. The change is only a plot state until the
     * next save writes a template of that size.</p>
     *
     * @return the size actually applied, after clamping to what this world's corridor allows
     */
    public static Vec3i setSize(ServerLevel overworld, String name, Axis axis, int value,
                                CarriageDims dims) {
        Vec3i current = plotSize(name, dims);
        Vec3i wanted = switch (axis) {
            case LENGTH -> new Vec3i(value, current.getY(), current.getZ());
            case WIDTH -> new Vec3i(current.getX(), current.getY(), value);
            case HEIGHT -> new Vec3i(current.getX(), value, current.getZ());
        };
        Vec3i clamped = PortalRoomLayout.clampSize(dims, wanted);

        // Through relayout, which erases and restamps only what actually moves. A room resizes
        // freely inside its reserved slot, so this is usually just this one plot; crossing a slot
        // boundary is what pays for shifting the rest of the row.
        relayout(overworld, dims, () -> PortalRoomSizes.pending(name, clamped));

        LOGGER.info("[DungeonTrain] Portal room '{}' plot restamped at {}x{}x{} ({} -> {})",
            name, clamped.getX(), clamped.getY(), clamped.getZ(), axis, value);
        return clamped;
    }

    /** Where one plot sits and how big it is — enough to erase it later. */
    private record PlotBox(BlockPos origin, Vec3i size) {}

    /**
     * Apply a change to the row, erasing and restamping only the plots it actually moves.
     *
     * <p>A room's plot sits in a reserved slot that only grows in {@link TrackSidePlots#SLOT_STEP}
     * jumps (see {@link TrackSidePlots#slotZ}), so most resizes shift nothing and this touches one
     * plot. When a room does outgrow its slot — or a name is added or removed — the later plots
     * move, and each one that moved has to be erased at its <b>old</b> box before being stamped at
     * the new one, or the row fills up with abandoned rooms nothing will ever clear.</p>
     *
     * <p>Boxes are snapshotted either side of {@code change} rather than assuming what moved, so a
     * future layout rule cannot silently leave debris behind.</p>
     */
    public static void relayout(ServerLevel overworld, CarriageDims dims, Runnable change) {
        Map<String, PlotBox> before = snapshotBoxes(dims);
        change.run();
        Map<String, PlotBox> after = snapshotBoxes(dims);

        int moved = 0;
        for (Map.Entry<String, PlotBox> e : before.entrySet()) {
            PlotBox now = after.get(e.getKey());
            if (now == null || !now.equals(e.getValue())) {
                clearBox(overworld, e.getValue(), e.getKey());
                moved++;
            }
        }
        for (Map.Entry<String, PlotBox> e : after.entrySet()) {
            PlotBox was = before.get(e.getKey());
            if (was == null || !was.equals(e.getValue())) {
                stampPlot(overworld, e.getKey(), dims);
            }
        }
        if (moved > 1) {
            LOGGER.info("[DungeonTrain] Portal room row re-laid out — {} plots moved", moved);
        }
    }

    private static Map<String, PlotBox> snapshotBoxes(CarriageDims dims) {
        Map<String, PlotBox> out = new java.util.LinkedHashMap<>();
        for (String name : names()) {
            out.put(name, new PlotBox(plotOrigin(name, dims), plotSize(name, dims)));
        }
        return out;
    }

    /** Current value of {@code axis} for {@code name}. */
    public static int axisOf(Vec3i size, Axis axis) {
        return switch (axis) {
            case LENGTH -> size.getX();
            case WIDTH -> size.getZ();
            case HEIGHT -> size.getY();
        };
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

        LOGGER.info("[DungeonTrain] Editor save: {} -> portal room '{}' template ({}x{}x{})",
            player.getName().getString(), name, size.getX(), size.getY(), size.getZ());

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
