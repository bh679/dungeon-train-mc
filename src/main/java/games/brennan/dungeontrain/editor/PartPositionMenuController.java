package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PartAssignmentEditPacket;
import games.brennan.dungeontrain.net.PartAssignmentSyncPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
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

        HitResult hit = player.pick(HOVER_REACH, 1.0f, false);
        CarriagePartKind kind = null;
        BlockPos hitLocal = null;
        if (hit instanceof BlockHitResult b && b.getType() != HitResult.Type.MISS) {
            hitLocal = b.getBlockPos().subtract(plotOrigin);
            kind = CarriagePartKind.kindAtLocalPos(
                hitLocal.getX(), hitLocal.getY(), hitLocal.getZ(), dims);
        }

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
                yield current.withAppended(packet.kind(), packet.name(), 1);
            }
            case REMOVE -> current.withRemoved(packet.kind(), packet.name());
            case CLEAR -> current.with(packet.kind(),
                List.of(CarriagePartAssignment.WeightedName.of(CarriagePartKind.NONE)));
            case BUMP_WEIGHT -> current.withWeight(packet.kind(), packet.name(), packet.delta());
            case CYCLE_SIDE_MODE -> current.cycleSideMode(packet.kind(), packet.name());
        };

        BlockPos plotOrigin = CarriageEditor.plotOrigin(standingIn, dims);
        // Re-raycast on the server to determine which placement the player
        // is currently looking at — the edit was just dispatched from the
        // client so the crosshair is almost certainly still on the same
        // part. If the player has since looked away, hitLocal is null and
        // chosenPlacementOrigin falls back to the first placement.
        BlockPos hitLocal = null;
        HitResult hit = player.pick(HOVER_REACH, 1.0f, false);
        if (hit instanceof BlockHitResult b && b.getType() != HitResult.Type.MISS) {
            hitLocal = b.getBlockPos().subtract(plotOrigin);
        }
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

        // Force a fresh sync so the client menu reflects the mutation
        // even though the (variantId, kind) key didn't change.
        UUID uuid = player.getUUID();
        LAST_HOVER.remove(uuid);
        DungeonTrainNet.sendTo(player,
            buildSyncPacket(standingIn, kindCentre, packet.kind(), inwardFace));
        LAST_HOVER.put(uuid, standingIn.id() + "|" + packet.kind().name() + "|" + inwardFace.name());
    }
}
