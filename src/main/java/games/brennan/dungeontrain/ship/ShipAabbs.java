package games.brennan.dungeontrain.ship;

import org.joml.primitives.AABBdc;

/**
 * Sanity tests on a ship's world AABB, for callers that are about to derive a world position from
 * one.
 *
 * <p>Sable answers {@code [0,0,0,0,0,0]} for a sub-level that has not ticked yet, and a wrapper
 * whose sub-level has been culled can answer with a box that is degenerate rather than merely
 * stale. Neither is a usable pose, and both read as a perfectly ordinary box to arithmetic — a
 * caller that subtracts from {@code minX()} without checking silently lands on coordinates near the
 * world origin.</p>
 *
 * <p><b>Not a substitute for {@link ManagedShip#isResident()}.</b> The two answer different
 * questions and a caller reading a live pose off a registry handle needs both: residency says the
 * handle still refers to a live sub-level, this says the box it returned is meaningful. A culled
 * ship commonly reports a <i>plausible</i> box — its last pose — which passes every test here and
 * only {@code isResident()} rejects.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class ShipAabbs {

    /**
     * Extent below which an axis counts as having no size. Matches the tolerance
     * {@code CarriageGroupGap} uses for the same Sable behaviour.
     */
    private static final double DEGENERATE_EPSILON = 1e-9;

    private ShipAabbs() {}

    /**
     * True when the box lacks meaningful extent on <b>at least one</b> axis — the shape Sable
     * returns for a sub-level that has not ticked, and for some disposed ones.
     *
     * <p>Tests extent rather than absolute coordinates, so it covers the literal all-zero box as
     * well as a zero-sized one that has drifted away from the origin.</p>
     *
     * <p>Rejecting on any single flat axis is deliberate and cannot produce a false positive here:
     * a carriage group is a solid box, several blocks on every axis, so a flat one is never a real
     * pose.</p>
     *
     * @param aabb the box to test; {@code null} counts as degenerate
     */
    public static boolean isDegenerate(AABBdc aabb) {
        if (aabb == null) return true;
        return isDegenerate(aabb.minX(), aabb.minY(), aabb.minZ(),
            aabb.maxX(), aabb.maxY(), aabb.maxZ());
    }

    /**
     * The same test on loose bounds.
     *
     * <p>Carries the whole rule, so it can be unit-tested without JOML on the test classpath — the
     * {@link AABBdc} overload above is a null check and six accessor calls.</p>
     */
    public static boolean isDegenerate(double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        return Math.abs(maxX - minX) < DEGENERATE_EPSILON
            || Math.abs(maxY - minY) < DEGENERATE_EPSILON
            || Math.abs(maxZ - minZ) < DEGENERATE_EPSILON;
    }
}
