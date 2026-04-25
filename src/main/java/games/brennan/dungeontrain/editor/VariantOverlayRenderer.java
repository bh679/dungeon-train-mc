package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorStatusPacket;
import games.brennan.dungeontrain.net.VariantHoverPacket;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsTemplate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;

import java.util.function.Function;
import java.util.function.Predicate;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-driven visual overlay for the carriage editor. For every player
 * standing inside an editor plot whose overlay toggle is on (default on
 * {@link CarriageEditor#enter}), this renderer:
 *
 * <ul>
 *   <li>Emits {@link ParticleTypes#END_ROD} particles at the 12 edge
 *       midpoints of every block flagged with random variants — a cheap
 *       highlight that reads as a glowing outline from the player's PoV.</li>
 *   <li>Raycasts the player's eye every tick; if the target is a flagged
 *       block, shows an action-bar string listing the number of variants
 *       and the current block's registry name.</li>
 * </ul>
 *
 * <p>No client-side code required — particles and action-bar messages
 * travel on the vanilla networking path. Everything is per-player, so a
 * dedicated server with multiple editors only bills each player for their
 * own plot.</p>
 */
public final class VariantOverlayRenderer {

    /** Tick cadence for particle emission — every 6 ticks ≈ 3.3 Hz. */
    private static final int PARTICLE_PERIOD_TICKS = 6;

    /** Range beyond which we skip particle emission even for plots a player is "in". */
    private static final double PARTICLE_RANGE = 24.0;
    private static final double PARTICLE_RANGE_SQ = PARTICLE_RANGE * PARTICLE_RANGE;

    /** Raycast distance for the hover action bar. */
    private static final double HOVER_REACH = 8.0;

    /** Number of particles per edge of a flagged block. 2 = one near each corner for visibility. */
    private static final int PARTICLES_PER_EDGE = 2;

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

    /** Per-player last-shown part-hover label and the tick we sent it on — keeps the action bar refreshed while the crosshair holds on a part. */
    private static final Map<UUID, String> LAST_PART_HOVER = new HashMap<>();
    private static final Map<UUID, Long> LAST_PART_HOVER_TICK = new HashMap<>();
    /** Re-emit the action-bar message at this cadence so Minecraft's ~60-tick fade never wins while hovering. */
    private static final int PART_HOVER_RESEND_TICKS = 10;

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
        LAST_PART_HOVER.remove(player.getUUID());
        LAST_PART_HOVER_TICK.remove(player.getUUID());
    }

    /**
     * Call once per server level tick. Cheap when no players are in an
     * editor plot — the outer loop over {@code level.players()} short-circuits
     * via {@link CarriageEditor#plotContaining}.
     */
    public static void onLevelTick(ServerLevel level) {
        long tick = level.getGameTime();
        boolean emitParticles = tick % PARTICLE_PERIOD_TICKS == 0;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        for (ServerPlayer player : players) {
            updateEditorStatus(player, dims);

            if (!isEnabled(player)) {
                clearHoverIfStale(player);
                clearPartHoverIfStale(player);
                continue;
            }

            BlockPos playerPos = player.blockPosition();

            // Carriage plot takes first priority — runs both the parts-list
            // action bar (which describes the carriage's part assignment at
            // the hovered kind) and the variant-blocks icon HUD.
            CarriageVariant plotVariant = CarriageEditor.plotContaining(playerPos, dims);
            if (plotVariant != null) {
                BlockPos plotOrigin = CarriageEditor.plotOrigin(plotVariant);
                if (plotOrigin == null) continue;

                updatePartHover(tick, player, plotVariant, plotOrigin, dims);

                CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(plotVariant, dims);
                if (sidecar.isEmpty()) {
                    clearHoverIfStale(player);
                    continue;
                }
                if (emitParticles) {
                    emitOutlineParticles(level, player, plotOrigin, sidecar.entries());
                }
                updateHoverPacket(player, plotOrigin,
                    pos -> inBounds(pos, dims),
                    sidecar::statesAt);
                continue;
            }

            // Contents plot — particles + icon HUD for the contents'
            // own variant sidecar, anchored to the interior origin (one
            // block in from each shell wall).
            CarriageContents contentsPlot = CarriageContentsEditor.plotContaining(playerPos, dims);
            if (contentsPlot != null) {
                clearPartHoverIfStale(player);
                BlockPos carriageOrigin = CarriageContentsEditor.plotOrigin(contentsPlot);
                if (carriageOrigin == null) continue;
                BlockPos interiorOrigin = carriageOrigin.offset(1, 1, 1);
                Vec3i interiorSize = CarriageContentsTemplate.interiorSize(dims);
                CarriageContentsVariantBlocks contentsSidecar = CarriageContentsVariantBlocks.loadFor(
                    contentsPlot, interiorSize);
                if (contentsSidecar.isEmpty()) {
                    clearHoverIfStale(player);
                    continue;
                }
                if (emitParticles) {
                    emitOutlineParticles(level, player, interiorOrigin, contentsSidecar.entries());
                }
                updateHoverPacket(player, interiorOrigin,
                    pos -> inBounds(pos, interiorSize),
                    contentsSidecar::statesAt);
                continue;
            }

            // Part plot next — particles + icon HUD for the part's own
            // variant sidecar. Parts-list action bar doesn't apply here (the
            // player is editing one part, not inspecting an assignment).
            CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(playerPos, dims);
            if (partLoc != null) {
                clearPartHoverIfStale(player);
                BlockPos plotOrigin = CarriagePartEditor.plotOrigin(partLoc.kind(), partLoc.name(), dims);
                if (plotOrigin == null) continue;
                Vec3i partSize = partLoc.kind().dims(dims);
                CarriagePartVariantBlocks partSidecar = CarriagePartVariantBlocks.loadFor(
                    partLoc.kind(), partLoc.name(), partSize);
                if (partSidecar.isEmpty()) {
                    clearHoverIfStale(player);
                    continue;
                }
                if (emitParticles) {
                    emitOutlineParticles(level, player, plotOrigin, partSidecar.entries());
                }
                updateHoverPacket(player, plotOrigin,
                    pos -> inBounds(pos, partSize),
                    partSidecar::statesAt);
                continue;
            }

            // Track-side plot (track tile / pillar section / stairs adjunct
            // / tunnel kind) — particles + icon HUD for the kind's own
            // variants.json sidecar. {@code TrackVariantBlocks.entries()}
            // returns the same {@link CarriageVariantBlocks.Entry} record
            // the carriage-side renderer uses, so the existing particle +
            // hover-packet helpers apply unchanged.
            TrackPlotLocator.PlotInfo trackLoc = TrackPlotLocator.locate(player, dims);
            if (trackLoc != null) {
                clearPartHoverIfStale(player);
                games.brennan.dungeontrain.track.variant.TrackVariantBlocks trackSidecar =
                    games.brennan.dungeontrain.track.variant.TrackVariantBlocks.loadFor(
                        trackLoc.kind(), trackLoc.name(), trackLoc.footprint());
                if (trackSidecar.isEmpty()) {
                    clearHoverIfStale(player);
                    continue;
                }
                if (emitParticles) {
                    emitOutlineParticles(level, player, trackLoc.origin(), trackSidecar.entries());
                }
                Vec3i footprint = trackLoc.footprint();
                updateHoverPacket(player, trackLoc.origin(),
                    pos -> inBounds(pos, footprint),
                    trackSidecar::statesAt);
                continue;
            }

            // Outside every plot — clear any stale HUD state.
            clearHoverIfStale(player);
            clearPartHoverIfStale(player);
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

        // Part plot first — parts aren't in the EditorModel sealed hierarchy
        // (would ripple into SaveCommand / ResetCommand dispatchers), so we
        // build a synthetic status packet with category="Parts" and
        // model="<kind>:<name>". The client menu (EditorMenuScreen) reads
        // this and renders a parts-specific Save / Remove row.
        CarriagePartEditor.PlotLocation partLoc = CarriagePartEditor.plotContaining(
            player.blockPosition(), dims);
        if (partLoc != null) {
            boolean partDevmode = EditorDevMode.isEnabled();
            String partModel = partLoc.kind().id() + ":" + partLoc.name();
            String partKey = "PARTS|" + partModel + "|" + partDevmode;
            if (partKey.equals(prev)) return;
            LAST_STATUS.put(uuid, partKey);
            DungeonTrainNet.sendTo(player, new EditorStatusPacket(
                "Parts", partModel, partDevmode, EditorStatusPacket.NO_WEIGHT));
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
        // Dedup key includes displayName (not just id) so walking from one
        // named variant to another in the same kind invalidates the cache —
        // model.id() is the kind tag and stays constant across a kind's
        // variants.
        String key = l.category().name() + "|" + l.model().displayName() + "|" + devmode + "|" + weight;
        if (key.equals(prev)) return;
        LAST_STATUS.put(uuid, key);
        DungeonTrainNet.sendTo(player, new EditorStatusPacket(
            l.category().displayName(), l.model().displayName(), devmode, weight));
    }

    /**
     * Variant pick weight for the given model. Carriage variants pull from
     * {@link CarriageWeights}; track-side models (track tile, pillar
     * sections, tunnel kinds) pull from {@link TrackVariantWeights} for the
     * synthetic "default" name. Returns {@link EditorStatusPacket#NO_WEIGHT}
     * for models that don't yet have weight semantics
     * ({@link EditorModel.ContentsModel}); the HUD uses the sentinel to
     * decide whether to render the weight line.
     */
    private static int weightFor(EditorModel model) {
        if (model instanceof EditorModel.CarriageModel cm) {
            return CarriageWeights.current().weightFor(cm.variant().id());
        }
        if (model instanceof EditorModel.TrackModel tm) {
            return TrackVariantWeights.weightFor(TrackKind.TILE, tm.name());
        }
        if (model instanceof EditorModel.PillarModel pm) {
            return TrackVariantWeights.weightFor(
                TrackPlotLocator.pillarKind(pm.section()), pm.name());
        }
        if (model instanceof EditorModel.TunnelModel tm) {
            return TrackVariantWeights.weightFor(
                TrackPlotLocator.tunnelKind(tm.variant()), tm.name());
        }
        return EditorStatusPacket.NO_WEIGHT;
    }

    private static void clearHoverIfStale(ServerPlayer player) {
        if (LAST_HOVER_POS.remove(player.getUUID()) != null) {
            DungeonTrainNet.sendTo(player, VariantHoverPacket.empty());
        }
    }

    /**
     * Emit end-rod outline particles around every entry in {@code entries},
     * offset from {@code plotOrigin} into world space. Range-culled against
     * the player so idle editors don't flood unnecessary packets.
     */
    private static void emitOutlineParticles(
        ServerLevel level, ServerPlayer player, BlockPos plotOrigin, List<CarriageVariantBlocks.Entry> entries
    ) {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        for (CarriageVariantBlocks.Entry e : entries) {
            BlockPos world = plotOrigin.offset(e.localPos());
            double cx = world.getX() + 0.5;
            double cy = world.getY() + 0.5;
            double cz = world.getZ() + 0.5;
            double dx = cx - px;
            double dy = cy - py;
            double dz = cz - pz;
            if (dx * dx + dy * dy + dz * dz > PARTICLE_RANGE_SQ) continue;
            sendEdgeParticles(level, player, world);
        }
    }

    /**
     * 12 edges per cube, {@link #PARTICLES_PER_EDGE} particles per edge.
     * Each {@code sendParticles} call is a single packet to the player,
     * so batch count matters — 24 per block is plenty for a visible outline
     * at 3 Hz without overwhelming the pipe.
     */
    private static void sendEdgeParticles(ServerLevel level, ServerPlayer player, BlockPos world) {
        double x0 = world.getX();
        double y0 = world.getY();
        double z0 = world.getZ();
        double x1 = x0 + 1.0;
        double y1 = y0 + 1.0;
        double z1 = z0 + 1.0;

        for (int step = 0; step < PARTICLES_PER_EDGE; step++) {
            double t = (step + 0.5) / PARTICLES_PER_EDGE;

            // 4 edges along X
            particle(level, player, lerp(x0, x1, t), y0, z0);
            particle(level, player, lerp(x0, x1, t), y0, z1);
            particle(level, player, lerp(x0, x1, t), y1, z0);
            particle(level, player, lerp(x0, x1, t), y1, z1);

            // 4 edges along Y
            particle(level, player, x0, lerp(y0, y1, t), z0);
            particle(level, player, x0, lerp(y0, y1, t), z1);
            particle(level, player, x1, lerp(y0, y1, t), z0);
            particle(level, player, x1, lerp(y0, y1, t), z1);

            // 4 edges along Z
            particle(level, player, x0, y0, lerp(z0, z1, t));
            particle(level, player, x0, y1, lerp(z0, z1, t));
            particle(level, player, x1, y0, lerp(z0, z1, t));
            particle(level, player, x1, y1, lerp(z0, z1, t));
        }
    }

    private static void particle(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        level.sendParticles(player, ParticleTypes.END_ROD, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
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

    /**
     * Raycast the player's eye against the carriage plot; if the crosshair
     * lands on a block owned by one of the part kinds (floor / walls / roof /
     * doors), push an action-bar message listing every candidate name the
     * variant's {@code parts.json} holds for that kind. Refreshes every
     * {@link #PART_HOVER_RESEND_TICKS} so the message persists while the
     * crosshair stays on the part.
     *
     * <p>No {@code parts.json} for the variant → no message (the kind still
     * renders via the monolithic NBT, there's nothing to list).</p>
     */
    private static void updatePartHover(long tick, ServerPlayer player, CarriageVariant variant,
                                         BlockPos plotOrigin, CarriageDims dims) {
        UUID uuid = player.getUUID();
        Optional<CarriagePartAssignment> opt = CarriageVariantPartsStore.get(variant);
        if (opt.isEmpty()) {
            clearPartHoverIfStale(player);
            return;
        }

        HitResult hit = player.pick(HOVER_REACH, 1.0f, false);
        String newKey = null;
        Component message = null;
        if (hit instanceof BlockHitResult bhr && bhr.getType() != HitResult.Type.MISS) {
            BlockPos local = bhr.getBlockPos().subtract(plotOrigin);
            if (inBounds(local, dims)) {
                CarriagePartKind kind = CarriagePartKind.kindAtLocalPos(
                    local.getX(), local.getY(), local.getZ(), dims);
                if (kind != null) {
                    List<String> names = opt.get().names(kind);
                    String joined = String.join(", ", names);
                    newKey = variant.id() + "|" + kind.id() + "|" + joined;
                    message = Component.literal(kind.id() + ": " + joined)
                        .withStyle(ChatFormatting.AQUA);
                }
            }
        }

        if (newKey == null) {
            clearPartHoverIfStale(player);
            return;
        }

        String prev = LAST_PART_HOVER.get(uuid);
        Long lastTick = LAST_PART_HOVER_TICK.get(uuid);
        boolean due = lastTick == null || tick - lastTick >= PART_HOVER_RESEND_TICKS;
        if (newKey.equals(prev) && !due) return;

        LAST_PART_HOVER.put(uuid, newKey);
        LAST_PART_HOVER_TICK.put(uuid, tick);
        player.displayClientMessage(message, true);
    }

    private static void clearPartHoverIfStale(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (LAST_PART_HOVER.remove(uuid) != null) {
            LAST_PART_HOVER_TICK.remove(uuid);
            // No explicit clear — the action-bar message fades naturally in
            // ~3s once we stop re-sending, and a follow-up variant-hover push
            // (or any other action-bar writer) will replace it if needed.
        }
    }
}
