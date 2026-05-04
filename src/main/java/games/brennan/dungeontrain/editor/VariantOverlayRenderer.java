package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.BlockVariantLockIdsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorStatusPacket;
import games.brennan.dungeontrain.net.VariantHoverPacket;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsAllowList;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.Vec3i;

import java.util.function.Function;
import java.util.function.Predicate;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Server-driven visual overlay for the carriage editor. For every player
 * standing inside an editor plot whose overlay toggle is on (default on
 * {@link CarriageEditor#enter}), this renderer raycasts the player's eye
 * every tick; if the target is a variant-flagged block it pushes a hover
 * packet so the client HUD can render the candidate-block icons, and on
 * carriage plots it also drives the part-position menu controller and
 * lock-id / outline snapshot pushes.
 *
 * <p>Everything is per-player, so a dedicated server with multiple editors
 * only bills each player for their own plot.</p>
 */
public final class VariantOverlayRenderer {

    /** Raycast distance for the hover action bar. */
    private static final double HOVER_REACH = 8.0;

    /** Players who have turned the overlay OFF. Default is "on when in an editor plot". */
    private static final Set<UUID> DISABLED = new HashSet<>();

    /**
     * Per-player "last position we sent a hover packet for" — so we only
     * push a new packet when the player crosses a block boundary or stops
     * looking at a variant-flagged block. Null value means "last packet was
     * the empty-clear".
     */
    private static final Map<UUID, BlockPos> LAST_HOVER_POS = new HashMap<>();

    /**
     * Per-player "last (category, model) we told the client about", stored as
     * a single {@code category|model} string for cheap equality. Null means
     * "the last packet we sent was the empty-clear (or we haven't sent one)".
     */
    private static final Map<UUID, String> LAST_STATUS = new HashMap<>();

    /**
     * Per-player dedup key for the lock-id snapshot push — encodes
     * {@code plotKey + each (localPos, lockId)} sorted. {@code null} or
     * absent means "last sent was an empty snapshot (or none yet)", so a
     * fresh non-empty plot always pushes on first tick.
     */
    private static final Map<UUID, String> LAST_LOCK_SNAPSHOT_KEY = new HashMap<>();

    /**
     * Per-player dedup key for the wireframe-outline snapshot push — encodes
     * {@code plotKey + each localPos} sorted. {@code null} or absent means
     * "last sent was empty (or none yet)", so a fresh non-empty plot always
     * pushes on first tick. Independent of the lock-id snapshot key because
     * unlocked cells appear here but not there.
     */
    private static final Map<UUID, String> LAST_OUTLINE_SNAPSHOT_KEY = new HashMap<>();

    private VariantOverlayRenderer() {}

    /** Toggle the overlay for {@code player}. {@code on == true} resumes rendering. */
    public static void setEnabled(ServerPlayer player, boolean on) {
        if (on) DISABLED.remove(player.getUUID());
        else DISABLED.add(player.getUUID());
    }

    public static boolean isEnabled(ServerPlayer player) {
        return !DISABLED.contains(player.getUUID());
    }

    /** Drop a player's overlay preference (called on editor exit). */
    public static void forget(ServerPlayer player) {
        DISABLED.remove(player.getUUID());
        BlockPos last = LAST_HOVER_POS.remove(player.getUUID());
        // Clear the client HUD on exit so it doesn't linger in a non-editor context.
        if (last != null) {
            DungeonTrainNet.sendTo(player, VariantHoverPacket.empty());
        }
        String lastStatus = LAST_STATUS.remove(player.getUUID());
        if (lastStatus != null) {
            DungeonTrainNet.sendTo(player, EditorStatusPacket.empty());
        }
        PartPositionMenuController.forget(player);
        // Clear the client lock-id overlay too — player has left every plot.
        if (LAST_LOCK_SNAPSHOT_KEY.remove(player.getUUID()) != null) {
            DungeonTrainNet.sendTo(player, BlockVariantLockIdsPacket.empty());
        }
        clearOutlineIfStale(player);
    }

    /**
     * Call once per server level tick. Cheap when no players are in an
     * editor plot — the outer loop over {@code level.players()} short-circuits
     * via {@link CarriageEditor#plotContaining}.
     */
    public static void onLevelTick(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        for (ServerPlayer player : players) {
            updateEditorStatus(player, dims);
            pushLockIdSnapshot(player);

            if (!isEnabled(player)) {
                clearHoverIfStale(player);
                clearOutlineIfStale(player);
                continue;
            }
            pushOutlineSnapshot(player);

            BlockPos playerPos = player.blockPosition();

            // Carriage plot takes first priority — drives the parts menu
            // controller and the variant-blocks icon HUD.
            CarriageVariant plotVariant = CarriageEditor.plotContaining(playerPos, dims);
            if (plotVariant != null) {
                BlockPos plotOrigin = CarriageEditor.plotOrigin(plotVariant, dims);
                if (plotOrigin == null) continue;

                PartPositionMenuController.update(player, plotVariant, plotOrigin, dims);

                CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(plotVariant, dims);
                if (sidecar.isEmpty()) {
                    clearHoverIfStale(player);
                    continue;
                }
                updateHoverPacket(player, plotOrigin,
                    pos -> inBounds(pos, dims),
                    sidecar::statesAt);
                continue;
            }

            // Contents plot — icon HUD for the contents' own variant
            // sidecar, anchored to the interior origin (one block in from
            // each shell wall).
            CarriageContents contentsPlot = CarriageContentsEditor.plotContaining(playerPos, dims);
            if (contentsPlot != null) {
                BlockPos carriageOrigin = CarriageContentsEditor.plotOrigin(contentsPlot, dims);
                if (carriageOrigin == null) continue;
                BlockPos interiorOrigin = carriageOrigin.offset(1, 1, 1);
                Vec3i interiorSize = CarriageContentsPlacer.interiorSize(dims);
                CarriageContentsVariantBlocks contentsSidecar = CarriageContentsVariantBlocks.loadFor(
                    contentsPlot, interiorSize);
                if (contentsSidecar.isEmpty()) {
                    clearHoverIfStale(player);
                    continue;
                }
                updateHoverPacket(player, interiorOrigin,
                    pos -> inBounds(pos, interiorSize),
                    contentsSidecar::statesAt);
                continue;
            }

            // Part plot next — icon HUD for the part's own variant
            // sidecar.
            CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(playerPos, dims);
            if (partLoc != null) {
                BlockPos plotOrigin = CarriagePartEditor.plotOrigin(
                    new games.brennan.dungeontrain.template.CarriagePartTemplateId(partLoc.kind(), partLoc.name()), dims);
                if (plotOrigin == null) continue;
                Vec3i partSize = partLoc.kind().dims(dims);
                CarriagePartVariantBlocks partSidecar = CarriagePartVariantBlocks.loadFor(
                    partLoc.kind(), partLoc.name(), partSize);
                if (partSidecar.isEmpty()) {
                    clearHoverIfStale(player);
                    continue;
                }
                updateHoverPacket(player, plotOrigin,
                    pos -> inBounds(pos, partSize),
                    partSidecar::statesAt);
                continue;
            }

            // Track-side plot (track tile / pillar section / stairs adjunct
            // / tunnel kind) — icon HUD for the kind's own variants.json
            // sidecar. {@code TrackVariantBlocks.entries()} returns the same
            // {@link CarriageVariantBlocks.Entry} record the carriage-side
            // renderer uses, so the existing hover-packet helper applies
            // unchanged.
            TrackPlotLocator.PlotInfo trackLoc = TrackPlotLocator.locate(player, dims);
            if (trackLoc != null) {
                games.brennan.dungeontrain.track.variant.TrackVariantBlocks trackSidecar =
                    games.brennan.dungeontrain.track.variant.TrackVariantBlocks.loadFor(
                        trackLoc.kind(), trackLoc.name(), trackLoc.footprint());
                if (trackSidecar.isEmpty()) {
                    clearHoverIfStale(player);
                    continue;
                }
                Vec3i footprint = trackLoc.footprint();
                updateHoverPacket(player, trackLoc.origin(),
                    pos -> inBounds(pos, footprint),
                    trackSidecar::statesAt);
                continue;
            }

            // Outside every plot — clear any stale HUD state.
            clearHoverIfStale(player);
        }
    }

    /**
     * Resolve which (category, model) the player is currently standing in (if
     * any) and push an {@link EditorStatusPacket} only when any of (category,
     * model, dev-mode, weight) has changed from the last-seen value. Called
     * once per player per tick — cheap when the player is outside every plot
     * (single {@code locate} call, no packet).
     *
     * <p>Weight is included in the dedup key so {@code /dt editor weight
     * <variant> <n>} pushes an immediate HUD refresh on the next tick — no
     * need to leave and re-enter the plot to see the new value.</p>
     */
    private static void updateEditorStatus(ServerPlayer player, CarriageDims dims) {
        UUID uuid = player.getUUID();
        String prev = LAST_STATUS.get(uuid);

        // Part plot first — parts aren't in the Template sealed hierarchy
        // (would ripple into SaveCommand / ResetCommand dispatchers), so we
        // build a synthetic status packet with category="Parts" and
        // model="<kind>:<name>". The client menu (EditorMenuScreen) reads
        // this and renders a parts-specific Save / Remove row.
        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(
            player.blockPosition(), dims);
        if (partLoc != null) {
            boolean partDevmode = EditorDevMode.isEnabled();
            boolean partMenuEnabled = PartPositionMenuController.isMenuEnabled(player);
            String partModel = partLoc.kind().id() + ":" + partLoc.name();
            String partKey = "PARTS|" + partModel + "|" + partDevmode + "|" + partMenuEnabled;
            if (partKey.equals(prev)) return;
            LAST_STATUS.put(uuid, partKey);
            // Parts have no weight pool — pass the part name as modelName for
            // consistency, but the menu won't render a weight row for parts.
            DungeonTrainNet.sendTo(player, new EditorStatusPacket(
                "Parts", partModel, partModel, partLoc.name(), partDevmode, EditorStatusPacket.NO_WEIGHT,
                partMenuEnabled, Collections.emptySet()));
            return;
        }

        Optional<EditorCategory.Located> located = EditorCategory.locate(player, dims);
        if (located.isEmpty()) {
            if (prev != null) {
                LAST_STATUS.remove(uuid);
                DungeonTrainNet.sendTo(player, EditorStatusPacket.empty());
            }
            return;
        }
        EditorCategory.Located l = located.get();
        boolean devmode = EditorDevMode.isEnabled();
        int weight = weightFor(l.model());
        String modelName = modelNameFor(l.model());
        boolean partMenuEnabled = PartPositionMenuController.isMenuEnabled(player);
        Set<String> excludedContents = excludedContentsFor(l.model());
        // Dedup key includes displayName (not just id) so walking from one
        // named variant to another in the same kind invalidates the cache —
        // model.id() is the kind tag and stays constant across a kind's
        // variants. Excluded set is sorted so its serialization is stable.
        String excludedKey = excludedContents.isEmpty()
            ? ""
            : String.join(",", new TreeSet<>(excludedContents));
        String key = l.category().name() + "|" + l.model().displayName() + "|" + devmode + "|" + weight + "|" + partMenuEnabled + "|" + excludedKey;
        if (key.equals(prev)) return;
        LAST_STATUS.put(uuid, key);
        DungeonTrainNet.sendTo(player, new EditorStatusPacket(
            l.category().displayName(), l.model().displayName(), l.model().id(), modelName,
            devmode, weight, partMenuEnabled, excludedContents));
    }

    /**
     * Sidecar-driven excluded contents set for the active carriage variant
     * (loaded via {@link CarriageVariantContentsAllowStore}). Empty for any
     * non-carriage model — the field is meaningless outside carriage editor
     * plots and the client renders nothing for it.
     */
    private static Set<String> excludedContentsFor(Template model) {
        if (!(model instanceof Template.Carriage cm)) return Collections.emptySet();
        CarriageContentsAllowList allow = CarriageVariantContentsAllowStore.get(cm.variant())
            .orElse(CarriageContentsAllowList.EMPTY);
        return allow.excluded();
    }

    /**
     * Variant pick weight for the given model — Phase-3 collapse onto
     * {@link Template#weight()}. Each record routes to its own weight
     * pool (carriage / contents / track-side); parts return
     * {@link EditorStatusPacket#NO_WEIGHT} since their HUD path is
     * synthetic and never reaches this method.
     */
    private static int weightFor(Template model) {
        return model.weight();
    }

    /**
     * Bare variant-name segment for the given model — Phase-3 collapse
     * onto {@link Template#variantName()}. For carriages and contents this
     * equals the model id; for track-side models it's the trailing name
     * segment of the display path. Falls back to {@link Template#id()} for
     * any future model type that forgets to override.
     */
    private static String modelNameFor(Template model) {
        return model.variantName();
    }

    private static void clearHoverIfStale(ServerPlayer player) {
        if (LAST_HOVER_POS.remove(player.getUUID()) != null) {
            DungeonTrainNet.sendTo(player, VariantHoverPacket.empty());
        }
    }

    /**
     * Push a {@link BlockVariantLockIdsPacket} to {@code player} reflecting
     * every locked cell in their current editor plot. Dedups against the
     * last-sent snapshot via {@link #LAST_LOCK_SNAPSHOT_KEY} — steady-state
     * standing inside an unchanging plot generates zero packets after the
     * first tick.
     *
     * <p>Call sites: every server tick from {@link #onLevelTick} (via
     * {@code pushLockIdSnapshot(player)}); and immediately after any
     * mutation that may change the plot's lock-id set
     * ({@link BlockVariantMenuController#cycleLockId}, the REMOVE / CLEAR
     * branches in {@link BlockVariantMenuController#applyEdit}, and the
     * {@link games.brennan.dungeontrain.item.VariantClipboardItem#useOn paste path}).</p>
     *
     * <p>When the player isn't in any plot, sends the empty sentinel only
     * if the previous snapshot was non-empty.</p>
     */
    public static void pushLockIdSnapshot(ServerPlayer player) {
        UUID uuid = player.getUUID();
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        games.brennan.dungeontrain.train.CarriageDims dims =
            games.brennan.dungeontrain.world.DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            if (LAST_LOCK_SNAPSHOT_KEY.remove(uuid) != null) {
                DungeonTrainNet.sendTo(player, BlockVariantLockIdsPacket.empty());
            }
            return;
        }
        java.util.Map<net.minecraft.core.BlockPos, Integer> locks = plot.allLockIds();

        // Build a deterministic key for dedup: plotKey + each (x,y,z=lockId)
        // pair sorted by position. Cheap and stable across reloads.
        StringBuilder keyBuf = new StringBuilder(64);
        keyBuf.append(plot.key()).append('|');
        java.util.List<java.util.Map.Entry<net.minecraft.core.BlockPos, Integer>> sorted =
            new java.util.ArrayList<>(locks.entrySet());
        sorted.sort((a, b) -> {
            int dx = Integer.compare(a.getKey().getX(), b.getKey().getX());
            if (dx != 0) return dx;
            int dy = Integer.compare(a.getKey().getY(), b.getKey().getY());
            if (dy != 0) return dy;
            return Integer.compare(a.getKey().getZ(), b.getKey().getZ());
        });
        for (java.util.Map.Entry<net.minecraft.core.BlockPos, Integer> e : sorted) {
            keyBuf.append(e.getKey().getX()).append(',')
                  .append(e.getKey().getY()).append(',')
                  .append(e.getKey().getZ()).append('=')
                  .append(e.getValue()).append(';');
        }
        String snapshotKey = keyBuf.toString();
        String prev = LAST_LOCK_SNAPSHOT_KEY.get(uuid);
        if (snapshotKey.equals(prev)) return;

        LAST_LOCK_SNAPSHOT_KEY.put(uuid, snapshotKey);
        java.util.List<BlockVariantLockIdsPacket.Entry> entries =
            new java.util.ArrayList<>(sorted.size());
        for (java.util.Map.Entry<net.minecraft.core.BlockPos, Integer> e : sorted) {
            entries.add(new BlockVariantLockIdsPacket.Entry(e.getKey(), e.getValue()));
        }
        DungeonTrainNet.sendTo(player,
            new BlockVariantLockIdsPacket(plot.key(), plot.origin(), entries));
    }

    /**
     * Push a {@link games.brennan.dungeontrain.net.BlockVariantOutlinePacket}
     * to {@code player} reflecting every variant-flagged cell (locked or
     * unlocked) in their current editor plot. Drives the client wireframe
     * outline. Dedups against the last-sent snapshot via
     * {@link #LAST_OUTLINE_SNAPSHOT_KEY} so steady-state generates zero
     * packets after the first tick.
     *
     * <p>Only called when overlay is on (gated in {@link #onLevelTick}).
     * When the player isn't in any plot, sends the empty sentinel only if
     * the previous snapshot was non-empty.</p>
     */
    private static void pushOutlineSnapshot(ServerPlayer player) {
        UUID uuid = player.getUUID();
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        games.brennan.dungeontrain.train.CarriageDims dims =
            games.brennan.dungeontrain.world.DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            clearOutlineIfStale(player);
            return;
        }
        java.util.Set<BlockPos> positions = plot.allFlaggedPositions();
        java.util.List<BlockPos> sorted = new java.util.ArrayList<>(positions);
        sorted.sort((a, b) -> {
            int dx = Integer.compare(a.getX(), b.getX());
            if (dx != 0) return dx;
            int dy = Integer.compare(a.getY(), b.getY());
            if (dy != 0) return dy;
            return Integer.compare(a.getZ(), b.getZ());
        });

        StringBuilder keyBuf = new StringBuilder(64);
        keyBuf.append(plot.key()).append('|');
        for (BlockPos p : sorted) {
            keyBuf.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(';');
        }
        String snapshotKey = keyBuf.toString();
        String prev = LAST_OUTLINE_SNAPSHOT_KEY.get(uuid);
        if (snapshotKey.equals(prev)) return;

        LAST_OUTLINE_SNAPSHOT_KEY.put(uuid, snapshotKey);
        if (sorted.isEmpty()) {
            DungeonTrainNet.sendTo(player,
                games.brennan.dungeontrain.net.BlockVariantOutlinePacket.empty());
            return;
        }
        DungeonTrainNet.sendTo(player,
            new games.brennan.dungeontrain.net.BlockVariantOutlinePacket(
                plot.key(), plot.origin(), sorted));
    }

    /** Send the empty outline packet if the player previously had a non-empty snapshot. */
    private static void clearOutlineIfStale(ServerPlayer player) {
        if (LAST_OUTLINE_SNAPSHOT_KEY.remove(player.getUUID()) != null) {
            DungeonTrainNet.sendTo(player,
                games.brennan.dungeontrain.net.BlockVariantOutlinePacket.empty());
        }
    }

    /**
     * Raycast the player's eye, figure out which variant-flagged position
     * (if any) they're currently pointing at, and sync that set of candidate
     * block ids to the client so the HUD overlay can draw icons. Only sends
     * a packet when the target block changes — stationary crosshair = zero
     * network traffic.
     *
     * <p>Sidecar-agnostic: {@code inBoundsFn} constrains the local position
     * to the plot's footprint (carriage dims for the carriage editor, the
     * kind's own {@link Vec3i} extent for part plots) and {@code statesAtFn}
     * resolves a local position to its candidate list (or {@code null} if
     * unflagged).</p>
     */
    private static void updateHoverPacket(
        ServerPlayer player, BlockPos plotOrigin,
        Predicate<BlockPos> inBoundsFn,
        Function<BlockPos, List<VariantState>> statesAtFn
    ) {
        HitResult hit = player.pick(HOVER_REACH, 1.0f, false);
        BlockPos flaggedPos = null;
        List<VariantState> states = null;
        if (hit instanceof BlockHitResult bhr && bhr.getType() != HitResult.Type.MISS) {
            BlockPos local = bhr.getBlockPos().subtract(plotOrigin);
            if (inBoundsFn.test(local)) {
                List<VariantState> atPos = statesAtFn.apply(local);
                if (atPos != null) {
                    flaggedPos = bhr.getBlockPos().immutable();
                    states = atPos;
                }
            }
        }

        BlockPos prev = LAST_HOVER_POS.get(player.getUUID());
        if (flaggedPos == null) {
            if (prev != null) {
                LAST_HOVER_POS.remove(player.getUUID());
                DungeonTrainNet.sendTo(player, VariantHoverPacket.empty());
            }
            return;
        }
        if (flaggedPos.equals(prev)) return;

        LAST_HOVER_POS.put(player.getUUID(), flaggedPos);
        DungeonTrainNet.sendTo(player, new VariantHoverPacket(toBlockIds(states)));
    }

    /** Vec3i-based bounds overload for part plots — local pos inside the part's footprint. */
    private static boolean inBounds(BlockPos p, Vec3i size) {
        return p.getX() >= 0 && p.getX() < size.getX()
            && p.getY() >= 0 && p.getY() < size.getY()
            && p.getZ() >= 0 && p.getZ() < size.getZ();
    }

    private static List<ResourceLocation> toBlockIds(List<VariantState> states) {
        List<ResourceLocation> out = new ArrayList<>(states.size());
        for (VariantState s : states) {
            out.add(BuiltInRegistries.BLOCK.getKey(s.state().getBlock()));
        }
        return out;
    }

    /**
     * Push an updated hover packet to {@code player} immediately, bypassing
     * the tick-level "only when the target block changes" gate. Called from
     * {@link VariantBlockInteractions} right after a shift-right-click
     * appends a block to the variants list so the HUD reflects the new set
     * without waiting for the player to look away and back.
     */
    public static void pushImmediateHover(ServerPlayer player, BlockPos worldPos, List<VariantState> states) {
        LAST_HOVER_POS.put(player.getUUID(), worldPos.immutable());
        DungeonTrainNet.sendTo(player, new VariantHoverPacket(toBlockIds(states)));
    }

    private static boolean inBounds(BlockPos p, CarriageDims dims) {
        return p.getX() >= 0 && p.getX() < dims.length()
            && p.getY() >= 0 && p.getY() < dims.height()
            && p.getZ() >= 0 && p.getZ() < dims.width();
    }
}
