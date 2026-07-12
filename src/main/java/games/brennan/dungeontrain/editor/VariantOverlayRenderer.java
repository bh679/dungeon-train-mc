package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;

import games.brennan.dungeontrain.net.BlockVariantLockIdsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorStatusPacket;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.net.VariantHoverPacket;
import games.brennan.dungeontrain.template.Template;
import org.slf4j.Logger;
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
 *
 * <p>All the per-player dedup maps below are static, so they would otherwise
 * leak across worlds in the integrated server (single JVM, multiple
 * world-load cycles). The {@link ServerStoppedEvent} hook below clears them
 * on every world quit so a fresh server start treats every player as
 * "never sent anything yet" — the first-tick dedup compare returns "no
 * change vs empty" only when there's genuinely nothing to send.</p>
 */
public final class VariantOverlayRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

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

    /**
     * Per-player dedup key for the editor plot labels push — encodes the
     * category plus each label's {@code worldPos|name|weight} tuple in the
     * order they're built. {@code null} or absent means "last sent was empty
     * (or none yet)", so a fresh category enter always pushes on first tick.
     * Refreshes whenever any label name or weight changes (e.g. after
     * {@code /dt editor weight ...}).
     */
    private static final Map<UUID, String> LAST_PLOT_LABELS_KEY = new HashMap<>();

    /**
     * Per-player dedup key for the template-type menus push — encodes the
     * category plus each menu's anchor and the variant list inside it. Same
     * lifecycle as {@link #LAST_PLOT_LABELS_KEY}: refreshes on any name /
     * weight change so the floating menus stay current.
     */
    private static final Map<UUID, String> LAST_TYPE_MENUS_KEY = new HashMap<>();

    /**
     * Per-player dedup key for the Stages-panel block icon strips — just
     * {@code StageBlockIndex.generation()}, which moves on any part/sidecar/
     * assignment/stage mutation. Steady-state generates zero packets; the
     * strip payload itself is read from the index's cache, never recomputed
     * inside this per-tick path.
     */
    private static final Map<UUID, String> LAST_STAGE_STRIPS_KEY = new HashMap<>();

    /** Per-player dedup key for the part-visibility mirror — just {@code EditorPartVisibility.generation()}. */
    private static final Map<UUID, String> LAST_PART_VIS_KEY = new HashMap<>();

    private VariantOverlayRenderer() {}

    /**
     * Wipe every per-player dedup map and the overlay-disabled set when the
     * integrated server stops. Without this, a player who entered the editor
     * in world A and quit to title would carry the dedup state forward into
     * world B — the next server's first tick would skip sending an empty
     * snapshot (since "no labels" matches the last key it remembers from
     * world A under the same UUID) and any non-empty snapshot would diff
     * against world A's state rather than a fresh slate.
     */
        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        DISABLED.clear();
        LAST_HOVER_POS.clear();
        LAST_STATUS.clear();
        LAST_LOCK_SNAPSHOT_KEY.clear();
        LAST_OUTLINE_SNAPSHOT_KEY.clear();
        LAST_PLOT_LABELS_KEY.clear();
        LAST_TYPE_MENUS_KEY.clear();
        LAST_STAGE_STRIPS_KEY.clear();
        LAST_PART_VIS_KEY.clear();
    }

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
        clearPlotLabelsIfStale(player);
        clearTypeMenusIfStale(player);
        clearStageStripsIfStale(player);
        clearPartVisibilityIfStale(player);
    }

    /**
     * Minimum player Y for the editor overlay to do any work. Every editor plot
     * sits in the sky at {@code PLOT_Y = 250} (see {@link EditorLayout}); gameplay
     * and trains run far below. A player under this line can't be at a plot, so the
     * per-player {@code plotContaining} locate cascade is skipped entirely for them.
     * That cascade used to run every tick for every player even during normal play
     * with the editor closed (~9ms/tick on a long train — the profiler's "overlay"
     * cost). Set a few blocks below 250 for standing-on-the-plot-floor margin.
     */
    private static final int EDITOR_Y_MIN = 245;

    /**
     * Call once per server level tick. Cheap when no players are up at the editor
     * build area ({@code y >= EDITOR_Y_MIN}): every player below that is
     * short-circuited before any {@code plotContaining} scan runs.
     */
    public static void onLevelTick(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        // Refresh any open Stage Blocks panels when the stage-blocks index moved (part saves,
        // sidecar edits, chat-command replaces/duplicates) — generation-guarded, so steady-state
        // ticks are one long comparison.
        StagePanelController.resyncIfStale(level.getServer());

        for (ServerPlayer player : players) {
            // Editor plots live in the sky at PLOT_Y=250; trains run far below. Skip the whole
            // editor-overlay locate cascade for anyone not up at the build area — this is the
            // ~9ms/tick the profiler flagged, which ran unconditionally during normal play.
            // forget() clears any lingering editor HUD once on the way out, then no-ops (cheap
            // map checks), so a player descending from the build area doesn't keep stale overlay.
            if (player.getBlockY() < EDITOR_Y_MIN) {
                forget(player);
                continue;
            }
            updateEditorStatus(player, dims);
            pushLockIdSnapshot(player);
            pushPlotLabelsSnapshot(player, dims);
            pushTypeMenusSnapshot(player, dims);
            pushStageStripsSnapshot(player, level);
            pushPartVisibilitySnapshot(player);

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
            boolean[] partMirror = mirrorAxesAt(player, dims);
            String partModel = partLoc.kind().id() + ":" + partLoc.name();
            String partKey = "PARTS|" + partModel + "|" + partDevmode + "|" + partMenuEnabled
                + "|" + partMirror[0] + partMirror[1] + partMirror[2] + partMirror[3];
            if (partKey.equals(prev)) return;
            LAST_STATUS.put(uuid, partKey);
            // Parts have no weight pool — pass the part name as modelName for
            // consistency, but the menu won't render a weight row for parts.
            DungeonTrainNet.sendTo(player, new EditorStatusPacket(
                "Parts", partModel, partModel, partLoc.name(), partDevmode, EditorStatusPacket.NO_WEIGHT,
                0, EditorStatusPacket.MAX_LEVEL_ALL, EditorStatusPacket.ALL_PHASES_MASK,
                partMenuEnabled, partMirror[0], partMirror[1], partMirror[2], partMirror[3],
                Collections.emptySet(), ""));
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
        // Per-template spawn gate (min/max Diff-Level + phase set) for the inline
        // level steppers + phase popup in the editor menu.
        TemplateGate gate = l.model().gate();
        int minLevel = gate.minLevel();
        int maxLevel = gate.maxLevel();
        int phaseMask = TrainPhase.toMask(gate.phases());
        // Stage link for the standing model — the keyboard gate controls render the Stage chip
        // instead of the editable steppers when this is non-empty.
        String stageId = l.model().stageId();
        boolean[] mirror = mirrorAxesAt(player, dims);
        // Dedup key includes displayName (not just id) so walking from one
        // named variant to another in the same kind invalidates the cache —
        // model.id() is the kind tag and stays constant across a kind's
        // variants. Excluded set is sorted so its serialization is stable.
        // Mirror flags are in the key so a mirror toggle pushes a refresh next tick.
        String excludedKey = excludedContents.isEmpty()
            ? ""
            : String.join(",", new TreeSet<>(excludedContents));
        String key = l.category().name() + "|" + l.model().displayName() + "|" + devmode + "|" + weight
            + "|" + minLevel + "|" + maxLevel + "|" + phaseMask + "|" + stageId
            + "|" + partMenuEnabled + "|" + mirror[0] + mirror[1] + mirror[2] + mirror[3] + "|" + excludedKey;
        if (key.equals(prev)) return;
        LAST_STATUS.put(uuid, key);
        DungeonTrainNet.sendTo(player, new EditorStatusPacket(
            l.category().displayName(), l.model().displayName(), l.model().id(), modelName,
            devmode, weight, minLevel, maxLevel, phaseMask, partMenuEnabled,
            mirror[0], mirror[1], mirror[2], mirror[3], excludedContents, stageId));
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

    /**
     * Editor mirror flags {@code [x, y, z, variants]} for the plot the player is
     * standing in — read from that plot's sidecar via {@link BlockVariantPlot},
     * for any editor category. All false when the player isn't in a plot. Backs
     * the X-menu Mirror X / Y / Z / V toggle state.
     */
    private static boolean[] mirrorAxesAt(ServerPlayer player, CarriageDims dims) {
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) return new boolean[]{false, false, false, false};
        return new boolean[]{plot.mirrorX(), plot.mirrorY(), plot.mirrorZ(), plot.mirrorVariants()};
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
     * Push an {@link EditorPlotLabelsPacket} carrying every plot's name +
     * weight in the currently-stamped editor category. Driven by
     * {@link EditorStampedCategoryState} (set on category enter, cleared on
     * {@link EditorCategory#clearAllPlots}) so the labels persist for as long
     * as the structures themselves are present — the player can wander
     * outside the cages, fly above the row, or stand 50 blocks away and the
     * labels still float above each template.
     *
     * <p>Dedup against {@link #LAST_PLOT_LABELS_KEY} so steady-state
     * generates zero packets after the first tick.</p>
     */
    private static void pushPlotLabelsSnapshot(ServerPlayer player, CarriageDims dims) {
        UUID uuid = player.getUUID();
        EditorCategory category = EditorStampedCategoryState.current().orElse(null);
        if (category == null) {
            clearPlotLabelsIfStale(player);
            return;
        }
        java.util.List<EditorPlotLabels.Label> labels = EditorPlotLabels.forCategory(category, dims);

        // Resolve which (modelId, modelName) the player is currently standing
        // inside, if any. Two cases:
        //   - Parts plot inside the CARRIAGES view: synthesise a "kind:name" id
        //     that matches what EditorPlotLabels.addPartLabels emits.
        //   - Any other category model: use the EditorCategory.locate path,
        //     mapping its Template to the same (id, name) pair the per-
        //     category builders use.
        String currentId = "";
        String currentName = "";
        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(
            player.blockPosition(), dims);
        if (partLoc != null) {
            // Parts entries store {@code (modelId=kind.id(), modelName=name)}
            // — see EditorPlotLabels.addPartLabels — so match against that
            // pair rather than the colon-joined display label.
            currentId = partLoc.kind().id();
            currentName = partLoc.name();
        } else {
            java.util.Optional<EditorCategory.Located> located = EditorCategory.locate(player, dims);
            if (located.isPresent()) {
                Template model = located.get().model();
                currentId = model.id();
                currentName = modelNameFor(model);
            }
        }

        // Patch the matching label to inPlot=true so the renderer shows
        // interactive controls + green border on it.
        if (!currentId.isEmpty()) {
            for (int i = 0; i < labels.size(); i++) {
                EditorPlotLabels.Label l = labels.get(i);
                if (currentId.equals(l.modelId()) && currentName.equals(l.modelName())) {
                    labels.set(i, l.withInPlot(true));
                    break;
                }
            }
        }

        StringBuilder keyBuf = new StringBuilder(64);
        keyBuf.append(category.name()).append('|');
        for (EditorPlotLabels.Label l : labels) {
            BlockPos p = l.worldPos();
            keyBuf.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ())
                .append(':').append(l.name()).append('=').append(l.weight())
                .append(l.inPlot() ? "*" : "").append(';');
        }
        String snapshotKey = keyBuf.toString();
        String prev = LAST_PLOT_LABELS_KEY.get(uuid);
        if (snapshotKey.equals(prev)) return;

        LAST_PLOT_LABELS_KEY.put(uuid, snapshotKey);
        if (labels.isEmpty()) {
            LOGGER.info("[DungeonTrain] EditorPlotLabels: clear (category {}, player {})",
                category, player.getName().getString());
            DungeonTrainNet.sendTo(player, EditorPlotLabelsPacket.empty());
            return;
        }
        java.util.List<EditorPlotLabelsPacket.Entry> entries = new java.util.ArrayList<>(labels.size());
        for (EditorPlotLabels.Label l : labels) {
            entries.add(new EditorPlotLabelsPacket.Entry(
                l.worldPos(), l.name(), l.weight(),
                l.category(), l.modelId(), l.modelName(),
                l.inPlot(), l.isUser(), l.isImported()));
        }
        EditorPlotLabels.Label first = labels.get(0);
        LOGGER.info("[DungeonTrain] EditorPlotLabels: send {} entries (category {}, first '{}' weight={} @ {}) to {}",
            entries.size(), category, first.name(), first.weight(), first.worldPos(),
            player.getName().getString());
        DungeonTrainNet.sendTo(player, new EditorPlotLabelsPacket(entries));
    }

    /** Send the empty plot-labels packet if the player previously had a non-empty snapshot. */
    private static void clearPlotLabelsIfStale(ServerPlayer player) {
        if (LAST_PLOT_LABELS_KEY.remove(player.getUUID()) != null) {
            DungeonTrainNet.sendTo(player, EditorPlotLabelsPacket.empty());
        }
    }

    /**
     * Push an {@link EditorTypeMenusPacket} carrying every visible
     * template-type menu in the currently-stamped editor category. Driven by
     * {@link EditorStampedCategoryState} so the menus persist for as long as
     * the stamped plots themselves are present, just like
     * {@link #pushPlotLabelsSnapshot}.
     *
     * <p>Dedup against {@link #LAST_TYPE_MENUS_KEY} — steady-state generates
     * zero packets after the first tick.</p>
     */
    private static void pushTypeMenusSnapshot(ServerPlayer player, CarriageDims dims) {
        UUID uuid = player.getUUID();
        EditorCategory category = EditorStampedCategoryState.current().orElse(null);
        if (category == null) {
            clearTypeMenusIfStale(player);
            return;
        }
        java.util.List<EditorTypeMenusPacket.Menu> baseMenus = EditorTypeMenus.forCategory(category, dims);

        // Companion menus appended in render order (closest to per-plot panel
        // first). Multiple companions sequence along panel-local +X in the
        // client renderer — first added sits next to the per-plot panel,
        // subsequent ones shift past their predecessor.
        //
        // Sub-variants companion is appended FIRST (slots adjacent to per-plot
        // panel) so the player's "list of sub-variants for the variant I'm
        // editing" is the closest companion. The type companion follows.
        java.util.List<EditorTypeMenusPacket.Menu> menus = appendSubVariantsCompanion(
            baseMenus, player, dims, category);
        menus = appendCompanionMenu(menus, player, dims, category);
        menus = appendPackageMenu(menus, dims);
        menus = appendStagesMenu(menus, dims);

        StringBuilder keyBuf = new StringBuilder(64);
        keyBuf.append(category.name()).append('|');
        // Include the focused stage (effective: explicit selection, else the first stage) so selecting /
        // deselecting — or adding / deleting a stage that shifts the default — re-pushes the snapshot and
        // the highlight updates live (steady-state still dedups to zero packets).
        keyBuf.append("sel:").append(EditorStageSelection.effective()).append('|');
        for (EditorTypeMenusPacket.Menu m : menus) {
            BlockPos p = m.worldPos();
            keyBuf.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ())
                .append(':').append(m.typeName()).append('[');
            for (EditorTypeMenusPacket.Variant v : m.variants()) {
                // Include the spawn gate (min/max level + phase mask) in the dedup key so editing it
                // from the world-space panel re-pushes the snapshot and the cells update live —
                // otherwise only weight changes would refresh the panel.
                keyBuf.append(v.name()).append('=').append(v.weight())
                    .append('@').append(v.minLevel()).append('-').append(v.maxLevel())
                    .append('p').append(v.phaseMask())
                    // Include the Stage link(s) so linking / detaching / toggling re-pushes the
                    // snapshot (the chip replaces the cells) and the stage rows refresh as stages are
                    // added/edited. Joined so a multi-Stage member's edits change the key.
                    .append('s').append(String.join("|", v.stageIds())).append(',');
            }
            keyBuf.append("];");
        }
        String snapshotKey = keyBuf.toString();
        String prev = LAST_TYPE_MENUS_KEY.get(uuid);
        if (snapshotKey.equals(prev)) return;

        LAST_TYPE_MENUS_KEY.put(uuid, snapshotKey);
        if (menus.isEmpty()) {
            LOGGER.info("[DungeonTrain] EditorTypeMenus: clear (category {}, player {})",
                category, player.getName().getString());
            DungeonTrainNet.sendTo(player, EditorTypeMenusPacket.empty());
            return;
        }
        EditorTypeMenusPacket.Menu first = menus.get(0);
        LOGGER.info("[DungeonTrain] EditorTypeMenus: send {} menus (category {}, first '{}' with {} variants @ {}) to {}",
            menus.size(), category, first.typeName(), first.variants().size(), first.worldPos(),
            player.getName().getString());
        DungeonTrainNet.sendTo(player, new EditorTypeMenusPacket(menus, EditorStageSelection.effective()));
    }

    /** Send the empty type-menus packet if the player previously had a non-empty snapshot. */
    private static void clearTypeMenusIfStale(ServerPlayer player) {
        if (LAST_TYPE_MENUS_KEY.remove(player.getUUID()) != null) {
            DungeonTrainNet.sendTo(player, EditorTypeMenusPacket.empty());
        }
    }

    /**
     * Push the Stages-panel block icon strips ({@link games.brennan.dungeontrain.net.StageBlockStripsPacket})
     * when the stage-blocks index has changed since the player's last push. The dedup key is just
     * the index {@link StageBlockIndex#generation() generation} — the payload builds from the
     * index's cache, so steady-state ticks do no aggregation work and send nothing.
     */
    private static void pushStageStripsSnapshot(ServerPlayer player, ServerLevel level) {
        UUID uuid = player.getUUID();
        if (EditorStampedCategoryState.current().isEmpty()) {
            clearStageStripsIfStale(player);
            return;
        }
        String key = "g" + StageBlockIndex.generation();
        if (key.equals(LAST_STAGE_STRIPS_KEY.get(uuid))) return;
        LAST_STAGE_STRIPS_KEY.put(uuid, key);

        // Always aggregate against the overworld — the editor plots and their dims live there,
        // and this tick may be for another dimension the player is standing in.
        ServerLevel overworld = level.getServer().overworld();
        java.util.List<games.brennan.dungeontrain.net.StageBlockStripsPacket.Strip> strips =
            new java.util.ArrayList<>();
        for (Map.Entry<String, java.util.List<String>> e
                : StageBlockIndex.blockStripForAllStages(overworld).entrySet()) {
            java.util.List<String> ids = e.getValue();
            int cap = games.brennan.dungeontrain.net.StageBlockStripsPacket.STRIP_CAP;
            java.util.List<String> capped = ids.size() <= cap
                ? ids : java.util.List.copyOf(ids.subList(0, cap));
            strips.add(new games.brennan.dungeontrain.net.StageBlockStripsPacket.Strip(
                e.getKey(), capped, ids.size()));
        }
        DungeonTrainNet.sendTo(player,
            new games.brennan.dungeontrain.net.StageBlockStripsPacket(strips));
    }

    /** Send the empty strips packet if the player previously had a non-empty snapshot. */
    private static void clearStageStripsIfStale(ServerPlayer player) {
        if (LAST_STAGE_STRIPS_KEY.remove(player.getUUID()) != null) {
            DungeonTrainNet.sendTo(player,
                games.brennan.dungeontrain.net.StageBlockStripsPacket.empty());
        }
    }

    /**
     * Push the per-part visibility mirror ({@link games.brennan.dungeontrain.net.PartVisibilityPacket})
     * when {@link EditorPartVisibility#generation()} moved since the player's last push — the
     * part-list ☑/☐ glyphs read it. Generation-keyed, so steady-state ticks send nothing.
     */
    private static void pushPartVisibilitySnapshot(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (EditorStampedCategoryState.current().isEmpty()) {
            clearPartVisibilityIfStale(player);
            return;
        }
        String key = "g" + EditorPartVisibility.generation();
        if (key.equals(LAST_PART_VIS_KEY.get(uuid))) return;
        LAST_PART_VIS_KEY.put(uuid, key);

        java.util.List<games.brennan.dungeontrain.net.PartVisibilityPacket.Entry> entries =
            new java.util.ArrayList<>();
        for (StageBlockIndex.PartRef ref : EditorPartVisibility.hiddenSnapshot()) {
            entries.add(new games.brennan.dungeontrain.net.PartVisibilityPacket.Entry(
                (byte) ref.kind().ordinal(), ref.name()));
        }
        DungeonTrainNet.sendTo(player, new games.brennan.dungeontrain.net.PartVisibilityPacket(entries));
    }

    /** Send the empty visibility packet if the player previously had a non-empty snapshot. */
    private static void clearPartVisibilityIfStale(ServerPlayer player) {
        if (LAST_PART_VIS_KEY.remove(player.getUUID()) != null) {
            DungeonTrainNet.sendTo(player,
                games.brennan.dungeontrain.net.PartVisibilityPacket.empty());
        }
    }

    /**
     * If the player is standing in a plot, return a copy of {@code baseMenus}
     * with one extra menu appended — a duplicate of the matching menu, anchored
     * a few blocks above the per-plot panel. Otherwise returns {@code baseMenus}
     * unchanged.
     *
     * <p>Anchor lookup reuses {@link EditorPlotLabels#forCategory} so the
     * companion sits exactly above the existing per-plot label panel — same
     * X / Z, lifted on Y by {@link #COMPANION_LIFT}. Match key is
     * {@code (modelId, modelName)} which is what both the labels and the type
     * menu variants carry.</p>
     */
    private static java.util.List<EditorTypeMenusPacket.Menu> appendCompanionMenu(
        java.util.List<EditorTypeMenusPacket.Menu> baseMenus,
        ServerPlayer player, CarriageDims dims, EditorCategory category
    ) {
        String currentId = "";
        String currentName = "";
        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(
            player.blockPosition(), dims);
        if (partLoc != null) {
            currentId = partLoc.kind().id();
            currentName = partLoc.name();
        } else {
            java.util.Optional<EditorCategory.Located> located = EditorCategory.locate(player, dims);
            if (located.isPresent()) {
                Template model = located.get().model();
                currentId = model.id();
                currentName = modelNameFor(model);
            }
        }
        if (currentId.isEmpty()) return baseMenus;

        // Find the per-plot label's worldPos for the variant the player is
        // standing in — reuses the same anchor calculation so the companion
        // lines up exactly with the per-plot panel below it.
        BlockPos perPlotAnchor = null;
        for (EditorPlotLabels.Label l : EditorPlotLabels.forCategory(category, dims)) {
            if (currentId.equals(l.modelId()) && currentName.equals(l.modelName())) {
                perPlotAnchor = l.worldPos();
                break;
            }
        }
        if (perPlotAnchor == null) return baseMenus;

        // Find the type menu containing a variant with the matching ids.
        EditorTypeMenusPacket.Menu source = null;
        for (EditorTypeMenusPacket.Menu m : baseMenus) {
            for (EditorTypeMenusPacket.Variant v : m.variants()) {
                if (currentId.equals(v.modelId()) && currentName.equals(v.modelName())) {
                    source = m;
                    break;
                }
            }
            if (source != null) break;
        }
        if (source == null) return baseMenus;

        java.util.List<EditorTypeMenusPacket.Menu> out = new java.util.ArrayList<>(baseMenus.size() + 1);
        out.addAll(baseMenus);
        // Companion shares the per-plot panel's world anchor and is flagged
        // {@code isCompanion=true} — the renderer translates it sideways in
        // panel-local space (after the cylindrical billboard basis) so the
        // two panels share orientation and read as one extended UI.
        out.add(new EditorTypeMenusPacket.Menu(
            perPlotAnchor, source.typeName(), source.variants(), true));
        return out;
    }

    /** Marker typeName for the sub-variants companion menu. Input handler keys off this to route + New differently. */
    public static final String SUB_VARIANTS_TYPE_NAME = "Sub-Variants";

    /**
     * Append the floating package menu — the worldspace mirror of the X-menu's
     * "Package" drilldown. One menu per snapshot, anchored at
     * {@link EditorTypeMenus#packageMenuAnchor(CarriageDims)} so it sits beside
     * the carriages nav menu at the editor's main entry door.
     *
     * <p>Data (package name / isActive / enabled) is NOT included in the
     * packet — the client renderer reads from {@code PackageListClient} which
     * is fed by {@code PackageListSyncPacket} on a separate channel. The
     * snapshot-key dedupe in {@link #pushTypeMenusSnapshot} keys on
     * {@code (anchor, typeName, variants)}; with empty variants the package
     * menu's contribution to the key is stable, which is what we want — the
     * data channel handles re-renders on package state changes.</p>
     */
    private static java.util.List<EditorTypeMenusPacket.Menu> appendPackageMenu(
        java.util.List<EditorTypeMenusPacket.Menu> baseMenus, CarriageDims dims
    ) {
        BlockPos anchor = EditorTypeMenus.packageMenuAnchor(dims);
        if (anchor == null) return baseMenus;
        java.util.List<EditorTypeMenusPacket.Menu> out = new java.util.ArrayList<>(baseMenus.size() + 1);
        out.addAll(baseMenus);
        out.add(new EditorTypeMenusPacket.Menu(
            anchor, "Packages", java.util.List.of(),
            false, "", java.util.List.of(), java.util.List.of(),
            /*isPackageMenu*/ true));
        return out;
    }

    /**
     * Append the global Stages management panel (a {@code isStagesMenu} {@link EditorTypeMenusPacket.Menu})
     * beside the carriages nav menu / package menu, mirroring {@link #appendPackageMenu}. Unlike the
     * package menu its variant rows carry real data (one gated row per Stage + a "+ New Stage" row),
     * built by {@link EditorTypeMenus#buildStagesMenu}. Shown in every category so the Stages list is
     * always reachable next to the template-type list. Unchanged list when there is no anchor.
     */
    private static java.util.List<EditorTypeMenusPacket.Menu> appendStagesMenu(
        java.util.List<EditorTypeMenusPacket.Menu> baseMenus, CarriageDims dims
    ) {
        EditorTypeMenusPacket.Menu stages = EditorTypeMenus.buildStagesMenu(dims);
        if (stages == null) return baseMenus;
        java.util.List<EditorTypeMenusPacket.Menu> out = new java.util.ArrayList<>(baseMenus.size() + 1);
        out.addAll(baseMenus);
        out.add(stages);
        return out;
    }

    /**
     * If the player is standing inside a CONTENTS editor plot, return a copy
     * of {@code baseMenus} with a sub-variants companion appended — listing
     * the active variant's group context (parent as the default sub-variant +
     * explicit members). Otherwise returns {@code baseMenus} unchanged.
     *
     * <p>When the active contents id is itself a group member, the menu
     * reflects the parent group's full sibling list (per the user-confirmed
     * UX: editing {@code container_wooden} shows
     * {@code [container (default), container_wooden, container_metal]}).</p>
     *
     * <p>When the active contents id is a leaf with no group context, the
     * menu still appears with just one row (active = default) plus the
     * {@code + New} footer — so authors can spawn the first sub-variant
     * without leaving the plot.</p>
     */
    private static java.util.List<EditorTypeMenusPacket.Menu> appendSubVariantsCompanion(
        java.util.List<EditorTypeMenusPacket.Menu> baseMenus,
        ServerPlayer player, CarriageDims dims, EditorCategory category
    ) {
        if (category != EditorCategory.CONTENTS) return baseMenus;
        CarriageContents active = CarriageContentsEditor.plotContaining(player.blockPosition(), dims);
        if (active == null) return baseMenus;

        // Determine the effective "parent" for the sub-variants menu:
        // - If active is a group parent itself, use active.
        // - Else if active is a member of some group, use that parent.
        // - Else fall back to active (leaf — menu shows just self).
        String parentId;
        if (CarriageContentsGroupStore.exists(active.id())) {
            parentId = active.id();
        } else {
            parentId = CarriageContentsGroupStore.findParentOf(active.id()).orElse(active.id());
        }

        // Build the rows. Default (parent self) row always first. Provenance
        // tints (user/imported) flow through the same Variant fields the rest
        // of the type menu uses — read from the contents store's file path.
        // The default row's weight is the parent's editable selfWeight from
        // the group sidecar — but only when a group with at least one member
        // exists (selfWeight has no behavioural effect when the parent has no
        // members to compete with). For parents without a populated group,
        // emit NO_WEIGHT so the cell is hidden and not clickable.
        String cat = EditorCategory.CONTENTS.name();
        List<EditorTypeMenusPacket.Variant> rows = new java.util.ArrayList<>();
        Optional<games.brennan.dungeontrain.train.CarriageContentsGroup> groupOpt =
            CarriageContentsGroupStore.get(parentId);
        boolean hasMembers = groupOpt.isPresent() && !groupOpt.get().isEmpty();
        int defaultRowWeight = hasMembers
            ? groupOpt.get().selfWeight()
            : games.brennan.dungeontrain.net.EditorPlotLabelsPacket.NO_WEIGHT;
        EditorPlotLabels.Provenance parentProv = EditorPlotLabels.provenanceOf(
            games.brennan.dungeontrain.editor.CarriageContentsStore.fileForId(parentId));
        rows.add(new EditorTypeMenusPacket.Variant(
            parentId + " (default)",
            defaultRowWeight,
            cat, parentId, parentId,
            parentProv.isUser(), parentProv.isImported()));
        if (groupOpt.isPresent()) {
            for (var m : groupOpt.get().members()) {
                EditorPlotLabels.Provenance memberProv = EditorPlotLabels.provenanceOf(
                    games.brennan.dungeontrain.editor.CarriageContentsStore.fileForId(m.id()));
                // Members carry a per-member spawn gate + zero-or-more Stage links — surface the same
                // gate/Stage cells the top-level Contents rows use. When linked the renderer draws a
                // Stage chip in place of the min/max/phase cells, so the gate below is only visible
                // for Custom (unlinked) members; for a linked member the first Stage's gate is shown
                // behind the chip (harmless). The leading "(default)" self-row above stays weight-only.
                String primaryStage = m.stageIds().isEmpty() ? null : m.stageIds().get(0);
                games.brennan.dungeontrain.template.TemplateGate g =
                    StageStore.effectiveGate(m.gate(), primaryStage);
                rows.add(new EditorTypeMenusPacket.Variant(
                    m.id(), m.weight(),
                    g.minLevel(), g.maxLevel(),
                    games.brennan.dungeontrain.worldgen.TrainPhase.toMask(g.phases()),
                    cat, m.id(), m.id(),
                    memberProv.isUser(), memberProv.isImported(),
                    java.util.List.of(), m.stageIds()));
            }
        }

        // Anchor: same as the per-plot label panel for the active variant.
        BlockPos perPlotAnchor = null;
        for (EditorPlotLabels.Label l : EditorPlotLabels.forCategory(category, dims)) {
            if (active.id().equals(l.modelId())) {
                perPlotAnchor = l.worldPos();
                break;
            }
        }
        if (perPlotAnchor == null) return baseMenus;

        java.util.List<EditorTypeMenusPacket.Menu> out = new java.util.ArrayList<>(baseMenus.size() + 1);
        out.addAll(baseMenus);
        out.add(new EditorTypeMenusPacket.Menu(
            perPlotAnchor, SUB_VARIANTS_TYPE_NAME, rows, true));
        return out;
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
            if (s.isMob()) {
                // Mob entry — resolve the matching spawn egg item id for the
                // entity type so the HUD renders a recognisable spawn-egg
                // icon. Falls back to BARRIER (via the HUD's own missing-item
                // path) if the entity type has no spawn egg registered.
                ResourceLocation eggId = spawnEggIdFor(s.entityId());
                if (eggId != null) {
                    out.add(eggId);
                    continue;
                }
                // No spawn egg (e.g. minecraft:player) — show the entity id
                // as-is; the HUD's BARRIER fallback will render.
                out.add(s.entityId());
                continue;
            }
            out.add(BuiltInRegistries.BLOCK.getKey(s.state().getBlock()));
        }
        return out;
    }

    /**
     * Resolve the spawn-egg item registry id for an entity type, or
     * {@code null} when no spawn egg exists for that type. Vanilla pattern:
     * lookup the {@code <namespace>:<entity_path>_spawn_egg} item.
     */
    private static @org.jetbrains.annotations.Nullable ResourceLocation spawnEggIdFor(ResourceLocation entityId) {
        if (entityId == null) return null;
        ResourceLocation eggId = ResourceLocation.fromNamespaceAndPath(
            entityId.getNamespace(), entityId.getPath() + "_spawn_egg");
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(eggId);
        if (item == null || item == net.minecraft.world.item.Items.AIR) return null;
        return eggId;
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
