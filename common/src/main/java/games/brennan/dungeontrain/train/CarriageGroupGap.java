package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.CarriageGroupGapPacket;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure helper that produces per-train debug-label entries describing the
 * X-axis gap between each carriage group and the next-higher-pIdx group in
 * the same train. Lives next to {@link CarriageFootprint} (its sibling
 * train-AABB helper) so the JOML {@link AABBdc} type only appears in
 * non-event-subscribed classes — NeoForge's mod-construction class
 * verification eagerly resolves method-signature types in
 * {@code @EventBusSubscriber} classes, and {@code AABBdc} is supplied via
 * {@code additionalRuntimeClasspath} (not the test-runtime classpath).
 *
 * <p>Returned entries are {@link CarriageGroupGapPacket.Entry} records of
 * {@code (anchorPIdx, highestPIdx, distance)} — anchor + highest define the
 * absolute carriage-pIdx range covered by THIS group, so the client HUD can
 * match the player's current carriage pIdx against {@code [anchor, highest]}
 * to find the relevant gap. Distance is
 * {@code nextGroup.minX − thisGroup.maxX} in blocks along world X.</p>
 */
public final class CarriageGroupGap {

    /** Sable returns a [0,0,0,0,0,0] AABB for sub-levels that haven't ticked yet — skip those. */
    private static final double ZERO_AABB_EPSILON = 1e-9;

    private CarriageGroupGap() {}

    /**
     * Compute one entry per (this, next) group pair across every loaded
     * train in {@code level}. The leading group of each train (highest
     * {@code pIdx}) gets no entry — it has no "next group" by definition.
     * Trains with fewer than two groups produce nothing.
     */
    public static List<CarriageGroupGapPacket.Entry> compute(ServerLevel level) {
        Map<UUID, List<Trains.Carriage>> trainsById = Trains.byTrainId(level);
        List<CarriageGroupGapPacket.Entry> out = new ArrayList<>();

        for (List<Trains.Carriage> train : trainsById.values()) {
            if (train.size() < 2) continue;
            List<Trains.Carriage> sorted = new ArrayList<>(train);
            sorted.sort(Comparator.comparingInt(c -> c.provider().getPIdx()));

            for (int i = 0; i < sorted.size() - 1; i++) {
                Trains.Carriage thisGroup = sorted.get(i);
                Trains.Carriage nextGroup = sorted.get(i + 1);
                AABBdc thisAabb = thisGroup.ship().worldAABB();
                AABBdc nextAabb = nextGroup.ship().worldAABB();
                if (isZeroAabb(thisAabb) || isZeroAabb(nextAabb)) continue;

                // Pick the anchor (line origin) in WORLD coords first — the
                // +X face of THIS group, FLOOR of carriage (bottom of AABB,
                // = the y of the floor block row), Z centerline — then
                // convert to THIS group's shipyard space so the client
                // renderer can apply the live render-pose at draw time.
                // The renderer stacks geometry up from this floor anchor:
                // cubes occupy [localY, localY+1] (the floor row in the
                // gap), the precise line sits at localY+1 (standing
                // surface), label at localY+1.7 (chest height).
                double worldStartX = thisAabb.maxX();
                double worldY = thisAabb.minY();
                double worldZ = (thisAabb.minZ() + thisAabb.maxZ()) * 0.5;

                Vector3d local = new Vector3d(worldStartX, worldY, worldZ);
                thisGroup.ship().worldToShip(local);

                float distance = (float) (nextAabb.minX() - thisAabb.maxX());
                int anchor = thisGroup.provider().getPIdx();
                int highest = thisGroup.provider().getGroupHighestPIdx();
                out.add(new CarriageGroupGapPacket.Entry(
                    anchor, highest,
                    thisGroup.ship().subLevelId(),
                    local.x, local.y, local.z,
                    distance));
            }
        }
        return out;
    }

    private static boolean isZeroAabb(AABBdc aabb) {
        return Math.abs(aabb.maxX() - aabb.minX()) < ZERO_AABB_EPSILON
            && Math.abs(aabb.maxY() - aabb.minY()) < ZERO_AABB_EPSILON
            && Math.abs(aabb.maxZ() - aabb.minZ()) < ZERO_AABB_EPSILON;
    }
}
