package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomResize;
import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.PortalRoomResizeMemory;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
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
            "[DungeonTrain] Dimensional carriage editor: this is the room between a portal's two corridors. "
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

    /**
     * The editor's Reset for a room: back to the last saved template, at the last saved <b>size</b>.
     *
     * <p>Reset restamps a plot from what is on disk, which for every other plot kind is the whole
     * story — their footprints are fixed in code. A room's is not, so a reset that only restamped the
     * blocks left the plot standing at whatever size the steppers had most recently produced: the
     * author's unsaved geometry was discarded but their unsaved dimensions were not. Dropping the
     * override in {@link PortalRoomSizes} is what makes Reset mean the same thing here as everywhere
     * else.</p>
     *
     * <p>Clearing the snapshot first is what tells {@link #relayout} not to carry the live plot
     * across — resetting is precisely the case where the author wants the live blocks thrown away.
     * The rows a shrink filed go with them: they are keyed to sizes this room is no longer walking
     * through, and keeping them would let a later grow restore a row from an abandoned resize.</p>
     */
    public static void resetToSaved(ServerLevel overworld, String name, CarriageDims dims) {
        Vec3i before = plotSize(name, dims);
        EditorPlotSnapshots.clear(snapshotKey(name));
        PortalRoomResizeMemory.get(overworld).forget(name);
        // The relayout is for the rest of the row — reverting a size can re-pack the plots after this
        // one. It restamps nothing when no box moved, so the reset itself is the stampPlot below,
        // which goes to disk for its blocks and is idempotent either way.
        relayout(overworld, dims, () -> PortalRoomSizes.revert(name));
        stampPlot(overworld, name, dims);

        Vec3i after = plotSize(name, dims);
        if (!before.equals(after)) {
            LOGGER.info("[DungeonTrain] Portal room '{}' reset — size back to {}x{}x{} from {}x{}x{}",
                name, after.getX(), after.getY(), after.getZ(),
                before.getX(), before.getY(), before.getZ());
        }
    }

    /**
     * Wipe what has been built in {@code name}'s plot and leave the plain built-in shell behind.
     *
     * <p>What the editor's Clear does, and deliberately not what {@link #clearPlot} does: that one
     * erases the box to nothing, which is right as a teardown primitive but leaves an author staring
     * into a hole. This leaves a room to keep building in, at the plot's <b>current</b> size — the
     * authored size is preserved, so this is not a reset to {@code builtInSize}.</p>
     *
     * <p>Nothing on disk changes. The saved {@code .nbt} survives until the next save, the same way
     * Clear behaves for carriages and contents; removing the variant itself is
     * {@code /dt editor portals reset}. The caller is responsible for the sidecars — the per-cell
     * variants and the container-contents pools — which live in their own files and would otherwise
     * put the old chests straight back on the next stamp.</p>
     */
    public static void clearToBuiltIn(ServerLevel overworld, String name, CarriageDims dims) {
        // Same reason as stampPlot: resolve the box that is actually there, not the built-in one.
        PortalRoomTemplateStore.get(overworld, name, dims);

        BlockPos origin = plotOrigin(name, dims);
        Vec3i size = plotSize(name, dims);

        // Also sweeps up loose items — including anything an earlier build spilled on the floor.
        EditorPlotEntityClearer.discardNonPlayersIn(overworld, origin, size);
        // Clear before stamping, not just because the shell does not write every cell, but because
        // stampRoomBuiltIn writes its interior as AIR through setBlock — straight over any chest
        // still holding its block entity. PortalClear evicts first, so nothing drops.
        PortalClear.clearBoxRelit(overworld, boxOf(origin, size), PortalCorridorMask.NONE);
        PortalCarriageBuilder.stampRoomBuiltIn(overworld, origin, size, /*relight*/ true);
        setOutline(overworld, origin, size, OUTLINE_BLOCK);
        captureSnapshot(overworld, origin, size, name);
    }

    /**
     * The editor's Clear: {@link #clearToBuiltIn}, plus everything authored on top of the blocks.
     *
     * <p>A portal room keeps its authored state in three files, and clearing only the blocks would
     * put the other two back on the next stamp — the per-cell variant sidecar, and the
     * container-contents pools each copy's chests are rolled from. Both go.</p>
     *
     * <p>The pools are the part that differs from every other category: carriages and contents leave
     * their pools alone on Clear. A portal room's chests are re-rolled per copy from that file, so
     * leaving it would restock exactly the chests the author had just cleared out.</p>
     *
     * @return how many authored entries were dropped across both sidecars
     */
    public static int clearEverything(ServerLevel overworld, String name, CarriageDims dims)
            throws IOException {
        clearToBuiltIn(overworld, name, dims);

        Vec3i size = plotSize(name, dims);
        int cleared = 0;

        // Delete the sidecar file rather than emptying it entry by entry: a cleared room has no
        // variants left to describe, and delete() drops the session cache with it.
        int variants = TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, name, size).size();
        TrackVariantBlocks.delete(TrackKind.PORTAL_ROOM, name);
        cleared += variants;

        // Same reasoning as the pools: a cleared room has nothing left to restore, and a remembered
        // row would put the chests back on the next grow.
        cleared += PortalRoomResizeMemory.get(overworld).forget(name);

        String plotKey = ContainerContentsStore.trackPlotKey(TrackKind.PORTAL_ROOM, name);
        ContainerContentsStore store = ContainerContentsStore.loadFor(plotKey);
        // Copy the positions before mutating — removePool writes to the map they are drawn from.
        for (BlockPos local : List.copyOf(store.allPositions())) {
            store.clearLink(local);
            if (store.removePool(local)) cleared++;
        }
        store.save();
        ContainerContentsStore.invalidate(plotKey);

        return cleared;
    }

    private static BoundingBox boxOf(BlockPos origin, Vec3i size) {
        return new BoundingBox(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX() - 1,
            origin.getY() + size.getY() - 1,
            origin.getZ() + size.getZ() - 1);
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
        BlockState air = Blocks.AIR.defaultBlockState();
        // Through PortalClear rather than a setBlock loop: a plot being erased may hold authored
        // chests, and replacing a container that still has its block entity spills its contents as
        // items. This path runs on relayout too, so the debris would outlive the plot that made it.
        PortalClear.clearBoxRelit(overworld, boxOf(origin, size), PortalCorridorMask.NONE);
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

    /**
     * Restamp {@code name}'s plot with one axis changed.
     *
     * <p>Keeps what the author can see, not what they last saved. Each block of the change is a
     * {@link PortalRoomResize.Step} — which face moves, and where that leaves the room's contents —
     * and the live plot is carried across each one. Length and width alternate faces, so a room grows
     * outwards from where it was built rather than always off the far end; a shrink files the row it
     * removes so stepping back up puts it back.</p>
     *
     * <p>Nothing is written to disk. The change is a plot state until {@code /dt save} writes a
     * template of the new size.</p>
     *
     * @return the size actually applied, after clamping to what this world's corridor allows
     */
    public static Vec3i setSize(ServerLevel overworld, String name, PortalRoomResize.Axis axis,
                                int value, CarriageDims dims) {
        Vec3i current = plotSize(name, dims);
        Vec3i clamped = heldUnderTheSky(overworld, dims, PortalRoomLayout.clampSize(dims,
            PortalRoomResize.with(current, axis, value)));
        applySteps(overworld, name, dims, PortalRoomResize.plan(dims, axis, current,
            PortalRoomResize.of(clamped, axis)));

        LOGGER.info("[DungeonTrain] Portal room '{}' plot restamped at {}x{}x{} ({} -> {})",
            name, clamped.getX(), clamped.getY(), clamped.getZ(), axis, value);
        return clamped;
    }

    /**
     * Walk {@code steps}, applying each single-block change to the plot in turn.
     *
     * <p>One block at a time rather than one jump: every step is then a resize of exactly the shape
     * the stepper makes, so the alternation, the row filed by a shrink and the row a grow takes back
     * are the same whether the author typed a number or tapped the button that many times. Each step
     * goes through {@link #relayout}, which erases and restamps only the plots that actually move — a
     * room resizes freely inside its reserved slot, so that is usually just this one.</p>
     */
    private static void applySteps(ServerLevel overworld, String name, CarriageDims dims,
                                   java.util.List<PortalRoomResize.Step> steps) {
        for (PortalRoomResize.Step step : steps) {
            Vec3i after = step.sizeAfter();
            relayout(overworld, dims, () -> PortalRoomSizes.pending(name, after), name, step);
        }
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
        relayout(overworld, dims, change, null, null);
    }

    /**
     * {@link #relayout} carrying one plot's resize step — which face moved, and where that leaves the
     * room's contents.
     *
     * <p>{@code resizing} names the plot the step belongs to. Every other plot that moves is carried
     * across unchanged: it is being shoved along the row by a neighbour, not resized.</p>
     */
    private static void relayout(ServerLevel overworld, CarriageDims dims, Runnable change,
                                 String resizing, PortalRoomResize.Step step) {
        Map<String, PlotBox> before = snapshotBoxes(dims);
        // Nothing in `change` touches the world — it moves numbers in PortalRoomSizes — so the plots
        // are still standing untouched after it runs, and which ones actually move is known.
        change.run();
        Map<String, PlotBox> after = snapshotBoxes(dims);

        // Read the plots that are about to move out of the world before anything is cleared. This is
        // what makes a resize non-destructive: re-stamping from the saved template — which is what
        // used to happen — replaced everything the author had built since their last save, on every
        // stepper click. A plot that has never been stamped this session has nothing live to carry
        // and falls back to the saved template, which is right on the way into the editor.
        Map<String, StructureTemplate> live = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, PlotBox> e : before.entrySet()) {
            PlotBox now = after.get(e.getKey());
            if (now != null && now.equals(e.getValue())) continue;
            StructureTemplate captured = captureLive(overworld, e.getKey(), e.getValue());
            if (captured != null) live.put(e.getKey(), captured);
        }

        // A shrink's row has to be filed while it is still standing in the world.
        if (step != null && !step.grow() && live.containsKey(resizing)) {
            PlotBox box = before.get(resizing);
            PortalRoomResizeSlabs.stash(overworld, resizing, box.origin(), box.size(), step);
        }

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
            if (was != null && was.equals(e.getValue())) continue;
            String name = e.getKey();
            StructureTemplate captured = live.get(name);
            if (captured == null) {
                stampPlot(overworld, name, dims);
                continue;
            }
            restampLive(overworld, name, dims, e.getValue(), captured,
                name.equals(resizing) ? step : null);
        }
        if (moved > 1) {
            LOGGER.info("[DungeonTrain] Portal room row re-laid out — {} plots moved", moved);
        }
    }

    /**
     * The plot as it currently stands in the world, or null when it is not standing there yet.
     *
     * <p>The snapshot {@link EditorPlotSnapshots} takes on every stamp is the test for "has this plot
     * been built this session" — without one the box is still solid rock, and capturing it would
     * carry a plot-shaped lump of deepslate into the new size.</p>
     */
    private static StructureTemplate captureLive(ServerLevel overworld, String name, PlotBox box) {
        if (!EditorPlotSnapshots.has(snapshotKey(name))) return null;
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(overworld, box.origin(), box.size(), false, Blocks.STRUCTURE_VOID);
        return template;
    }

    /** Lay a captured plot back down at its new box, applying {@code step}'s shift and stashed row. */
    private static void restampLive(ServerLevel overworld, String name, CarriageDims dims,
                                    PlotBox box, StructureTemplate captured,
                                    PortalRoomResize.Step step) {
        Vec3i shift = step == null ? Vec3i.ZERO : step.shift();
        PortalCarriageBuilder.stampRoomFromLive(overworld, box.origin(), box.size(), captured,
            shift, /*relight*/ true);

        // Cells first, then the row: both are addressed in plot-local coordinates, and a restored
        // row's coordinates are already in the new frame.
        PortalRoomResizeSlabs.shiftSidecars(name, box.size(), shift);
        if (step != null && step.grow()) {
            PortalRoomResizeSlabs.restore(overworld, name, box.origin(), box.size(), step);
        }

        setOutline(overworld, box.origin(), box.size(), OUTLINE_BLOCK);
        captureSnapshot(overworld, box.origin(), box.size(), name);
    }

    private static Map<String, PlotBox> snapshotBoxes(CarriageDims dims) {
        Map<String, PlotBox> out = new java.util.LinkedHashMap<>();
        for (String name : names()) {
            out.put(name, new PlotBox(plotOrigin(name, dims), plotSize(name, dims)));
        }
        return out;
    }

    /**
     * Restamp {@code name}'s plot at an explicit whole box.
     *
     * <p>The typed-entry counterpart to the per-axis steppers: setting all three at once is one
     * relayout instead of three, and lets the author jump straight to a size rather than walking
     * there a block at a time.</p>
     *
     * @return the size actually applied, after clamping to what this world's corridor allows
     */
    public static Vec3i setSize(ServerLevel overworld, String name, Vec3i wanted, CarriageDims dims) {
        Vec3i clamped = heldUnderTheSky(overworld, dims, PortalRoomLayout.clampSize(dims, wanted));
        applySteps(overworld, name, dims,
            PortalRoomResize.plan(dims, plotSize(name, dims), clamped));
        LOGGER.info("[DungeonTrain] Portal room '{}' plot restamped at {}x{}x{} (typed {}x{}x{})",
            name, clamped.getX(), clamped.getY(), clamped.getZ(),
            wanted.getX(), wanted.getY(), wanted.getZ());
        return clamped;
    }

    /**
     * {@code size} with its height held to what a plot can actually show.
     *
     * <p>Plots sit in the sky at {@link TrackSidePlots#Y_BASELINE}, which leaves 90 blocks under a
     * stock DT world's build ceiling — enough for the whole of {@link PortalRoomLayout#MAX_HEIGHT},
     * so in an ordinary world this holds nothing back. It is not dead code: the plot floor is what
     * sets the room ceiling, and at the floor's old height (250) it was 70. Without it a stepper
     * would report a height the plot cannot hold, the rows above the ceiling would go nowhere, and a
     * save would bake them back as air. The room a player is looking at is the template, so the
     * number has to be one the plot can stand up.</p>
     *
     * <p>Only the editor's own ceiling. What a <b>stamped</b> room is held to is a different and
     * lower number in most worlds — the basement's, via
     * {@link games.brennan.dungeontrain.portal.PortalTwinLanes#maxStructureHeight}.</p>
     */
    private static Vec3i heldUnderTheSky(ServerLevel overworld, CarriageDims dims, Vec3i size) {
        int ceiling = Math.max(PortalRoomLayout.minHeight(dims),
            overworld.getMaxBuildHeight() - TrackSidePlots.Y_BASELINE);
        return size.getY() <= ceiling
            ? size
            : new Vec3i(size.getX(), ceiling, size.getZ());
    }

    /** Current value of {@code axis} for {@code name}. */
    public static int axisOf(Vec3i size, PortalRoomResize.Axis axis) {
        return PortalRoomResize.of(size, axis);
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

        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(overworld, origin, size, false, Blocks.STRUCTURE_VOID);

        PortalRoomTemplateStore.save(name, template);

        // The saved size is the authored one now. A row filed by an earlier shrink would put blocks
        // back that the author has deliberately stepped away from and then saved without.
        PortalRoomResizeMemory.get(overworld).forget(name);

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
