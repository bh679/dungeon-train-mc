package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PartAssignmentEditPacket;
import games.brennan.dungeontrain.net.PartAssignmentSyncPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriagePartPlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side driver for the part-position world-space menu.
 *
 * <ul>
 *   <li>Per-tick {@link #update}: raycast the player's eye against the
 *       carriage plot, classify the hit block as a {@link CarriagePartKind},
 *       and push a {@link PartAssignmentSyncPacket} when the hovered
 *       (variantId, kind) changes. The packet carries the part's
 *       worldspace anchor (centred in the kind region; for WALLS / DOORS
 *       picking whichever of the two placements is closer to the player)
 *       plus the panel's right/up axes (billboarded toward the player).</li>
 *   <li>{@link #applyEdit}: validate the player is OP and standing in
 *       the named variant's plot, apply the requested mutation (add /
 *       remove / clear / bump weight), persist via
 *       {@link CarriageVariantPartsStore}, and re-sync.</li>
 *   <li>{@link #setMenuEnabled}: track a per-player on/off flag. While
 *       off, {@link #update} suppresses sync packets and pushes a single
 *       empty packet to close the client menu.</li>
 * </ul>
 *
 * <p>Range/anchor maths uses {@link CarriagePartKind#placements} so wall
 * and door menus open on the wall/door the player is actually facing.
 * Anchors are at block-corner precision (offsets baked from kind
 * placements + half-extents).</p>
 */
public final class PartPositionMenuController {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Same eye-pick reach as the variant-block hover overlay. */
    private static final double HOVER_REACH = 8.0;

    /** Players who have toggled the menu OFF. Default behaviour is "auto-open". */
    private static final Set<UUID> DISABLED = new HashSet<>();

    /**
     * Last (variantId, kind) we sent a sync packet for, per player.
     * Keyed format {@code "variantId|kind"} or the empty string for
     * "we last sent the close-menu sentinel". Null means we have never
     * sent a sync packet for this player.
     */
    private static final Map<UUID, String> LAST_HOVER = new HashMap<>();

    private PartPositionMenuController() {}

    public static boolean isMenuEnabled(ServerPlayer player) {
        return !DISABLED.contains(player.getUUID());
    }

    public static void setMenuEnabled(ServerPlayer player, boolean enabled) {
        UUID uuid = player.getUUID();
        if (enabled) {
            DISABLED.remove(uuid);
        } else {
            DISABLED.add(uuid);
            // Push an immediate close so the client doesn't keep its panel up
            // until the next hover transition.
            String prev = LAST_HOVER.put(uuid, "");
            if (prev == null || !prev.isEmpty()) {
                DungeonTrainNet.sendTo(player, PartAssignmentSyncPacket.empty());
            }
        }
    }

    /** Per-server-stop reset. Hooked from {@link CarriagePartRegistry}'s lifecycle helpers. */
    public static synchronized void clearAll() {
        DISABLED.clear();
        LAST_HOVER.clear();
    }

    /** Per-player exit reset. Hooked from {@link VariantOverlayRenderer#forget}. */
    public static void forget(ServerPlayer player) {
        UUID uuid = player.getUUID();
        DISABLED.remove(uuid);
        if (LAST_HOVER.remove(uuid) != null) {
            DungeonTrainNet.sendTo(player, PartAssignmentSyncPacket.empty());
        }
    }

    /**
     * Per-tick hover detection. Called from
     * {@link VariantOverlayRenderer#onLevelTick} when the player stands in
     * the carriage editor plot for {@code variant}.
     */
    public static void update(ServerPlayer player, CarriageVariant variant,
                              BlockPos plotOrigin, CarriageDims dims) {
        UUID uuid = player.getUUID();

        if (!isMenuEnabled(player)) {
            // Suppress: ensure client knows menu is closed.
            String prev = LAST_HOVER.get(uuid);
            if (prev != null && !prev.isEmpty()) {
                LAST_HOVER.put(uuid, "");
                DungeonTrainNet.sendTo(player, PartAssignmentSyncPacket.empty());
            }
            return;
        }

        BlockPos hitLocal = resolveHoveredLocal(player, plotOrigin, dims);
        CarriagePartKind kind = hitLocal == null ? null
            : CarriagePartKind.kindAtLocalPos(hitLocal.getX(), hitLocal.getY(), hitLocal.getZ(), dims);

        if (kind == null) {
            String prev = LAST_HOVER.get(uuid);
            if (prev != null && !prev.isEmpty()) {
                LAST_HOVER.put(uuid, "");
                DungeonTrainNet.sendTo(player, PartAssignmentSyncPacket.empty());
            }
            return;
        }

        // Use the hit block's local pos to pick which placement (for two-
        // placement kinds — walls / doors). The panel should appear on the
        // wall the player is actually looking at, not the geometrically
        // closest one. Dedup on (variant, kind, face) so swinging the
        // crosshair within the same wall doesn't churn the anchor.
        Vec3 kindCentre = anchorCentreForHit(plotOrigin, dims, kind, hitLocal);
        Direction inwardFace = canonicalInwardFace(kind, kindCentre, plotOrigin, dims);

        String key = variant.id() + "|" + kind.name() + "|" + inwardFace.name();
        String prev = LAST_HOVER.get(uuid);
        if (key.equals(prev)) return;

        LAST_HOVER.put(uuid, key);
        DungeonTrainNet.sendTo(player,
            buildSyncPacket(variant, kindCentre, kind, inwardFace));
    }

    /**
     * Local block pos (relative to {@code plotOrigin}) the player is "looking
     * at" within the carriage shell, or {@code null} if their look ray never
     * crosses it.
     *
     * <p>Primary path: the live block-pick — when the crosshair lands on an
     * actual part block, that exact block is returned (unchanged precision for
     * the common case). Fallback: when the pick misses or hits a non-part block
     * (open doorway, {@code none} part, hollow interior, the roof gap), a
     * geometric {@link #shellFaceLocal} ray-vs-shell test recovers the part the
     * player is facing — so the menu tracks look direction rather than only
     * appearing on solid blocks.</p>
     */
    private static BlockPos resolveHoveredLocal(ServerPlayer player, BlockPos plotOrigin, CarriageDims dims) {
        HitResult hit = player.pick(HOVER_REACH, 1.0f, false);
        if (hit instanceof BlockHitResult b && b.getType() != HitResult.Type.MISS) {
            BlockPos local = b.getBlockPos().subtract(plotOrigin);
            if (CarriagePartKind.kindAtLocalPos(local.getX(), local.getY(), local.getZ(), dims) != null) {
                return local;
            }
        }
        return shellFaceLocal(player, plotOrigin, dims);
    }

    /**
     * Geometric fallback: intersect the player's eye ray against the carriage
     * shell box (plot-local {@code [0..L)×[0..H)×[0..W)}) and return a local
     * block pos on the crossed face, classified to the part the player is
     * looking toward. Returns {@code null} when the ray never crosses the box
     * (e.g. standing in the outer plot margin looking away).
     *
     * <p>The editing player is normally inside the box, so the ray exits through
     * exactly one face (the {@code tFar} crossing). When the eye is outside but
     * looking in, the entry face ({@code tNear}) is used instead. The crossed
     * axis is snapped to the shell coordinate; the other two axes are clamped
     * into the range that kind actually owns so
     * {@link CarriagePartKind#kindAtLocalPos} classifies the face unambiguously
     * (walls/floor/roof don't bleed into the door corner columns).</p>
     */
    private static BlockPos shellFaceLocal(ServerPlayer player, BlockPos plotOrigin, CarriageDims dims) {
        int L = dims.length(), H = dims.height(), W = dims.width();
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getViewVector(1.0f);
        double[] o = { eye.x, eye.y, eye.z };
        double[] d = { dir.x, dir.y, dir.z };
        double[] lo = { plotOrigin.getX(), plotOrigin.getY(), plotOrigin.getZ() };
        double[] hi = { plotOrigin.getX() + L, plotOrigin.getY() + H, plotOrigin.getZ() + W };

        double tNear = Double.NEGATIVE_INFINITY, tFar = Double.POSITIVE_INFINITY;
        int nearAxis = -1, farAxis = -1;
        boolean nearIsLo = false, farIsLo = false;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1.0e-8) {
                // Ray parallel to this slab: a miss if the eye is outside it.
                if (o[a] < lo[a] || o[a] > hi[a]) return null;
                continue;
            }
            double tEnter = ((d[a] > 0 ? lo[a] : hi[a]) - o[a]) / d[a];
            double tExit  = ((d[a] > 0 ? hi[a] : lo[a]) - o[a]) / d[a];
            if (tEnter > tNear) { tNear = tEnter; nearAxis = a; nearIsLo = d[a] > 0; }
            if (tExit  < tFar)  { tFar  = tExit;  farAxis  = a; farIsLo  = d[a] < 0; }
        }
        if (tNear > tFar) return null; // ray misses the box

        // Inside the box → tNear < 0, use the forward exit face. Outside but
        // looking in → tNear >= 0, use the entry face.
        boolean useExit = tNear < 0.0;
        double t = useExit ? tFar : tNear;
        int axis = useExit ? farAxis : nearAxis;
        boolean isLo = useExit ? farIsLo : nearIsLo;
        double maxT = L + H + W + HOVER_REACH; // generous: any in-box exit qualifies
        if (axis < 0 || t < 0.0 || t > maxT) return null;

        Vec3 hp = eye.add(dir.scale(t));
        int lx = (int) Math.floor(hp.x - plotOrigin.getX());
        int ly = (int) Math.floor(hp.y - plotOrigin.getY());
        int lz = (int) Math.floor(hp.z - plotOrigin.getZ());

        // Snap the crossed axis to the shell index and clamp the other two into
        // the range the resulting kind owns (see kindAtLocalPos precedence).
        switch (axis) {
            case 0 -> { // X face → DOORS (owns full width × height at x∈{0,L-1})
                lx = isLo ? 0 : L - 1;
                ly = clamp(ly, 0, H - 1);
                lz = clamp(lz, 0, W - 1);
            }
            case 1 -> { // Y face → FLOOR / ROOF (own the inner rectangle only)
                ly = isLo ? 0 : H - 1;
                lx = clamp(lx, 1, L - 2);
                lz = clamp(lz, 1, W - 2);
            }
            default -> { // Z face → WALLS (own the z-edges over the interior x-range)
                lz = isLo ? 0 : W - 1;
                lx = clamp(lx, 1, L - 2);
                ly = clamp(ly, 0, H - 1);
            }
        }
        return new BlockPos(lx, ly, lz);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * Build a sync packet anchored to the kind region's overall inward
     * face — independent of which specific block in the region the
     * crosshair last hit. The anchor stays fixed as the player swings
     * across different blocks of the same wall / floor / etc., which is
     * what authors expect: the panel is "the panel for this wall", not
     * "the panel for this brick".
     *
     * <p>Anchor = {@code kindCentre} pushed along the inward normal by
     * (half of the part's thickness in the inward axis + small ε to
     * avoid z-fighting with the block face). All four part kinds are
     * 1-block thick on their inward axis, so the half-thickness is
     * always 0.5.</p>
     */
    private static PartAssignmentSyncPacket buildSyncPacket(
        CarriageVariant variant, Vec3 kindCentre, CarriagePartKind kind, Direction inwardFace
    ) {
        Vec3 normal = new Vec3(inwardFace.getStepX(), inwardFace.getStepY(), inwardFace.getStepZ());
        // 0.5 = half the 1-block thickness; +0.02 keeps the panel just clear of the block face.
        Vec3 anchor = kindCentre.add(normal.scale(0.5 + 0.02));

        // Up axis: world up for vertical faces (walls/doors); world +Z for
        // horizontal faces (floor/ceiling) so text reads upright when the
        // player looks down.
        Vec3 up = (inwardFace.getAxis() == Direction.Axis.Y)
            ? new Vec3(0, 0, 1)
            : new Vec3(0, 1, 0);
        Vec3 right = up.cross(normal).normalize();

        CarriagePartAssignment assignment = CarriageVariantPartsStore.get(variant)
            .orElse(CarriagePartAssignment.EMPTY);
        List<CarriagePartAssignment.WeightedName> entries = assignment.entries(kind);
        List<String> registered = CarriagePartRegistry.names(kind);

        return new PartAssignmentSyncPacket(
            variant.id(), kind, entries, anchor, right, up, registered);
    }

    /**
     * Direction that points from the part region toward the carriage
     * interior. Used for re-syncs after an edit when the live raycast
     * face isn't available.
     */
    private static Direction canonicalInwardFace(
        CarriagePartKind kind, Vec3 kindCentre, BlockPos plotOrigin, CarriageDims dims
    ) {
        return switch (kind) {
            case FLOOR -> Direction.UP;
            case ROOF -> Direction.DOWN;
            case WALLS -> {
                // Wall at z=0 → inward is +Z; at z=W-1 → inward is -Z.
                double localZ = kindCentre.z - plotOrigin.getZ();
                yield localZ < dims.width() / 2.0 ? Direction.SOUTH : Direction.NORTH;
            }
            case DOORS -> {
                double localX = kindCentre.x - plotOrigin.getX();
                yield localX < dims.length() / 2.0 ? Direction.EAST : Direction.WEST;
            }
        };
    }

    /**
     * Centre of the placement that owns the hit block (or the lone
     * placement, for FLOOR / ROOF). For WALLS / DOORS the choice is made
     * from the hit block's local position — the wall the player's
     * crosshair is on, not the wall they're geometrically closest to.
     *
     * <p>Falls back to the first placement when {@code hitLocal} is null
     * (used by the post-edit re-sync path; the player's current crosshair
     * may have moved off the part).</p>
     */
    private static Vec3 anchorCentreForHit(
        BlockPos plotOrigin, CarriageDims dims, CarriagePartKind kind, BlockPos hitLocal
    ) {
        net.minecraft.core.Vec3i sz = kind.dims(dims);
        BlockPos chosenOrigin = chosenPlacementOrigin(kind, dims, hitLocal);
        return new Vec3(
            plotOrigin.getX() + chosenOrigin.getX() + sz.getX() / 2.0,
            plotOrigin.getY() + chosenOrigin.getY() + sz.getY() / 2.0,
            plotOrigin.getZ() + chosenOrigin.getZ() + sz.getZ() / 2.0
        );
    }

    /**
     * Resolve which {@link CarriagePartKind.Placement} the hit block
     * belongs to, returning that placement's origin offset. For two-
     * placement kinds the choice is purely on the hit block's local
     * position along the placement-axis; for one-placement kinds the
     * answer is the lone origin.
     */
    private static BlockPos chosenPlacementOrigin(
        CarriagePartKind kind, CarriageDims dims, BlockPos hitLocal
    ) {
        return switch (kind) {
            case FLOOR -> new BlockPos(1, 0, 1);
            case ROOF -> new BlockPos(1, dims.height() - 1, 1);
            case WALLS -> {
                int hz = hitLocal == null ? 0 : hitLocal.getZ();
                yield hz <= 0
                    ? new BlockPos(1, 0, 0)
                    : new BlockPos(1, 0, dims.width() - 1);
            }
            case DOORS -> {
                int hx = hitLocal == null ? 0 : hitLocal.getX();
                yield hx <= 0
                    ? new BlockPos(0, 0, 0)
                    : new BlockPos(dims.length() - 1, 0, 0);
            }
        };
    }

    /**
     * Resolve which {@link CarriagePartKind.Placement} index the player's
     * crosshair is currently on. Mirrors {@link #chosenPlacementOrigin}
     * but returns the placement's list index instead of its origin offset.
     * Used by the PREVIEW_ENTRY path to build a per-placement names list
     * that targets a single side without disturbing the other.
     */
    private static int chosenPlacementIndex(
        CarriagePartKind kind, CarriageDims dims, BlockPos hitLocal
    ) {
        return switch (kind) {
            case FLOOR, ROOF -> 0;
            case WALLS -> {
                int hz = hitLocal == null ? 0 : hitLocal.getZ();
                yield hz <= 0 ? 0 : 1;
            }
            case DOORS -> {
                int hx = hitLocal == null ? 0 : hitLocal.getX();
                yield hx <= 0 ? 0 : 1;
            }
        };
    }

    /**
     * Re-stamp {@code name}'s template at the placement under the player's
     * crosshair. The other placements (for two-placement kinds) are passed
     * the {@link CarriagePartKind#NONE} sentinel so
     * {@link CarriagePartPlacer#placeAtPerPlacement} skips them and
     * leaves the existing stamp in place.
     *
     * <p>{@link CarriagePartPlacer#placeAtPerPlacement} erases the target
     * placement's region to air before stamping, so sparse templates
     * (e.g. an "open" doorway with only a frame block) fully replace
     * the previously-previewed door instead of mixing with it through
     * the template's air positions.</p>
     *
     * <p>Seed 0 / carriageIndex 0 matches
     * {@link CarriageEditor#stampPlot}'s deterministic editor stamp, so
     * any {@code CarriagePartVariantBlocks} sidecar overlay lands on the
     * same picks the editor uses on plot entry.</p>
     *
     * <p>No assignment mutation — the on-disk parts assignment is
     * unchanged. Unknown names return silently; client can be slightly
     * stale after registry reloads.</p>
     */
    private static void previewEntry(net.minecraft.server.level.ServerLevel level,
                                     ServerPlayer player, CarriageVariant variant,
                                     CarriageDims dims, CarriagePartKind kind, String name) {
        if (!CarriagePartRegistry.isKnown(kind, name)) {
            LOGGER.warn("[DungeonTrain] PartMenu PREVIEW_ENTRY rejected: '{}' not a registered {} part",
                name, kind.id());
            return;
        }

        BlockPos plotOrigin = CarriageEditor.plotOrigin(variant, dims);
        if (plotOrigin == null) return;

        BlockPos hitLocal = resolveHoveredLocal(player, plotOrigin, dims);
        int targetIndex = chosenPlacementIndex(kind, dims, hitLocal);

        List<CarriagePartKind.Placement> placements = kind.placements(dims);
        java.util.ArrayList<String> names = new java.util.ArrayList<>(placements.size());
        for (int i = 0; i < placements.size(); i++) {
            names.add(i == targetIndex ? name : CarriagePartKind.NONE);
        }
        // Editor plot part swap (permanent world blocks, no Sable lift): relight through the light engine.
        CarriagePartPlacer.placeAtPerPlacement(level, plotOrigin, kind, names, dims, 0L, 0, /*relight*/ true);
    }

    /**
     * Apply a {@link PartAssignmentEditPacket} mutation. Authorisation:
     * the player must be OP (matches the existing slash-command policy)
     * and must be standing inside {@code variantId}'s editor plot — so
     * the menu can't be used to mutate variants the player isn't
     * currently looking at.
     */
    public static void applyEdit(ServerPlayer player, PartAssignmentEditPacket packet) {
        if (!player.hasPermissions(2)) {
            LOGGER.warn("[DungeonTrain] PartMenu edit rejected: player {} not OP", player.getName().getString());
            return;
        }
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) return;
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        CarriageDims dims = games.brennan.dungeontrain.world.DungeonTrainWorldData.get(overworld).dims();

        CarriageVariant standingIn = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (standingIn == null || !standingIn.id().equals(packet.variantId())) {
            LOGGER.warn("[DungeonTrain] PartMenu edit rejected: player {} not in plot for '{}'",
                player.getName().getString(), packet.variantId());
            return;
        }

        // PREVIEW_ENTRY: re-stamp the named variant's template at the
        // placement under the player's crosshair. Pure visual swap — no
        // assignment mutation, no save, no re-sync.
        if (packet.op() == PartAssignmentEditPacket.Op.PREVIEW_ENTRY) {
            previewEntry(overworld, player, standingIn, dims, packet.kind(), packet.name());
            return;
        }

        CarriagePartAssignment current = CarriageVariantPartsStore.get(standingIn)
            .orElse(CarriagePartAssignment.EMPTY);
        CarriagePartAssignment updated = switch (packet.op()) {
            case ADD -> {
                if (!CarriagePartRegistry.isKnown(packet.kind(), packet.name())) {
                    LOGGER.warn("[DungeonTrain] PartMenu ADD rejected: '{}' not a registered {} part",
                        packet.name(), packet.kind().id());
                    yield current;
                }
                String normalized = packet.name() == null
                    ? CarriagePartKind.NONE
                    : packet.name().toLowerCase(java.util.Locale.ROOT);
                boolean alreadyPresent = false;
                for (CarriagePartAssignment.WeightedName e : current.entries(packet.kind())) {
                    if (e.name().equals(normalized)) { alreadyPresent = true; break; }
                }
                if (alreadyPresent) {
                    yield current;
                }
                CarriagePartAssignment withPart = current.withAppended(packet.kind(), packet.name(), 1);
                // Default a newly-added part to the focused Stage — the explicit selection, or the first
                // stage when none is selected — so authoring straight into the previewed stage links the
                // entry without a separate "set stage" step. Null (no stages exist) = Custom.
                String focusedStage = EditorStageSelection.effective();
                yield focusedStage == null
                    ? withPart
                    : withPart.withStage(packet.kind(), packet.name(), focusedStage);
            }
            case REMOVE -> current.withRemoved(packet.kind(), packet.name());
            case CLEAR -> current.with(packet.kind(),
                List.of(CarriagePartAssignment.WeightedName.of(CarriagePartKind.NONE)));
            case BUMP_WEIGHT -> current.withWeight(packet.kind(), packet.name(), packet.delta());
            case CYCLE_SIDE_MODE -> current.cycleSideMode(packet.kind(), packet.name());
            case CYCLE_END_MODE -> current.cycleEndMode(packet.kind(), packet.name());
            case BUMP_MIN_LEVEL -> current.withMinLevel(packet.kind(), packet.name(), packet.delta());
            case BUMP_MAX_LEVEL -> current.withMaxLevel(packet.kind(), packet.name(), packet.delta());
            case TOGGLE_PHASE, TOGGLE_OTHER_PHASES -> {
                // delta carries the TrainPhase ordinal (see PartAssignmentEditPacket). TOGGLE_PHASE
                // flips that one dimension; TOGGLE_OTHER_PHASES (shift-click) flips all but it.
                games.brennan.dungeontrain.worldgen.TrainPhase[] phases =
                    games.brennan.dungeontrain.worldgen.TrainPhase.values();
                int idx = packet.delta();
                if (idx < 0 || idx >= phases.length) {
                    LOGGER.warn("[DungeonTrain] PartMenu {} rejected: bad phase ordinal {}", packet.op(), idx);
                    yield current;
                }
                games.brennan.dungeontrain.worldgen.TrainPhase phase = phases[idx];
                yield packet.op() == PartAssignmentEditPacket.Op.TOGGLE_OTHER_PHASES
                    ? current.toggleOtherPhases(packet.kind(), packet.name(), phase)
                    : current.togglePhase(packet.kind(), packet.name(), phase);
            }
            // Link the entry to a Stage (empty stageId = detach to Custom). Unknown stage ids are
            // tolerated — the entry's effective gate then falls back to its inline snapshot.
            case SET_STAGE -> {
                String link = packet.stageId();
                if (link != null && !link.isBlank()
                    && !games.brennan.dungeontrain.editor.StageStore.exists(link)) {
                    LOGGER.warn("[DungeonTrain] PartMenu SET_STAGE: stage '{}' does not exist — linking anyway.", link);
                }
                yield current.withStage(packet.kind(), packet.name(), link);
            }
            // Unreachable — PREVIEW_ENTRY is handled by an early return above.
            // Kept here so the switch stays exhaustive over the Op enum.
            case PREVIEW_ENTRY -> current;
        };

        BlockPos plotOrigin = CarriageEditor.plotOrigin(standingIn, dims);
        // Re-resolve on the server which placement the player is looking at —
        // the edit was just dispatched from the client so the crosshair is
        // almost certainly still on the same part. Uses the same block-pick +
        // geometric-shell fallback as hover, so the side stays correct even
        // when facing a part's air (open door / none). Null only if looking
        // clear of the shell, in which case chosenPlacementOrigin falls back
        // to the first placement.
        BlockPos hitLocal = resolveHoveredLocal(player, plotOrigin, dims);
        Vec3 kindCentre = anchorCentreForHit(plotOrigin, dims, packet.kind(), hitLocal);
        Direction inwardFace = canonicalInwardFace(packet.kind(), kindCentre, plotOrigin, dims);

        if (updated == current) {
            // No-op; still resync so the client UI is authoritative.
            DungeonTrainNet.sendTo(player,
                buildSyncPacket(standingIn, kindCentre, packet.kind(), inwardFace));
            return;
        }

        try {
            CarriageVariantPartsStore.save(standingIn, updated);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] PartMenu save failed for '{}': {}", standingIn.id(), e.toString());
            // Sync the unchanged-on-disk state so the client doesn't drift.
            DungeonTrainNet.sendTo(player,
                buildSyncPacket(standingIn, kindCentre, packet.kind(), inwardFace));
            return;
        }

        // When a stage preview is active (an explicit selection or the first-stage default), re-stamp
        // this carriage so the per-stage preview reflects the edit right away — the unfiltered preview
        // only re-stamps on plot re-entry, but the stage preview must show/hide the just-changed part
        // now (e.g. a part just defaulted into this stage).
        if (EditorStageSelection.effective() != null) {
            CarriageEditor.stampPlot(overworld, standingIn, dims);
        }

        // Force a fresh sync so the client menu reflects the mutation
        // even though the (variantId, kind) key didn't change.
        UUID uuid = player.getUUID();
        LAST_HOVER.remove(uuid);
        DungeonTrainNet.sendTo(player,
            buildSyncPacket(standingIn, kindCentre, packet.kind(), inwardFace));
        LAST_HOVER.put(uuid, standingIn.id() + "|" + packet.kind().name() + "|" + inwardFace.name());
    }
}
