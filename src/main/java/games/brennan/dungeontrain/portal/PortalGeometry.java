package games.brennan.dungeontrain.portal;

/**
 * Pure geometry for one <b>hallway portal</b> — a corridor with a door at each end and a midpoint
 * line, stamped <b>twice</b> at two heights in the same chunk columns:
 *
 * <pre>
 *   copy FAR   (floorY + deltaY)   Door1(dummy) ───────── X ───────── Door2 → pocket area
 *   copy NEAR  (floorY)            Door1 → outside ────── X ───────── Door2(dummy)
 * </pre>
 *
 * <p>Which copy a player belongs in is decided <b>purely by which side of the midpoint they are
 * on</b>: before it ⇒ {@link #COPY_NEAR}, after it ⇒ {@link #COPY_FAR}. Because the two copies are
 * block-identical, moving a player between them is invisible — that is the whole trick, and it
 * replaces the portal rendering an Immersive-Portals-style implementation would need.</p>
 *
 * <p><b>Invariant, not edge-detection.</b> {@link #requiredShift} states the rule as a condition on
 * the current position rather than as a crossing event, so it is idempotent: applying it twice is a
 * no-op. That immunises the system against everything edge-detection gets wrong — no ping-pong
 * after the swap, no missed crossing at any speed (elytra, speed potions), and it self-heals after
 * login, {@code /tp}, or knockback.</p>
 *
 * <p><b>Why {@link #MIN_LENGTH} is 32.</b> Vanilla light travels at most 15 blocks, so a corridor
 * of 32+ puts the midpoint out of reach of any light from either doorway. Without that, daylight
 * leaking past an open near door would light the NEAR copy's midpoint but not the FAR copy's
 * (whose matching door is a sealed dummy) — and the brightness would visibly pop on crossing. The
 * length rule removes that class of tell by construction rather than by tuning.</p>
 *
 * <p><b>Why the vertical offset.</b> The second copy sits in the <b>same chunk columns</b>, so its
 * chunks are already loaded server-side, already sent to the client, and already meshed:
 * {@code ViewArea.setViewDistance} sizes the render grid {@code sectionGridSizeY =
 * level.getSectionsCount()}, i.e. the full build height, at every render distance. A horizontal
 * offset would have to fight render distance and could hand the player an unloaded destination; a
 * vertical one cannot. This is what makes "the other side is preloaded" true for free.</p>
 *
 * <p>No Minecraft types here, so it unit-tests without a NeoForge bootstrap — same convention as
 * {@link games.brennan.dungeontrain.worldgen.NetherTransition}.</p>
 *
 * @param originX interior start of the corridor along +X (the near door's column)
 * @param floorY  Y of the NEAR copy's floor blocks; walkable surface is {@code floorY + 1}
 * @param originZ interior start of the corridor along +Z (minimum interior Z)
 * @param length  interior length along X, in blocks, at least {@link #MIN_LENGTH}
 * @param width   interior width along Z, in blocks
 * @param height  interior headroom in blocks; ceiling blocks sit at {@code floorY + height + 1}
 * @param deltaY  Y offset from the NEAR copy to the FAR copy
 */
public record PortalGeometry(int originX, int floorY, int originZ,
                             int length, int width, int height, int deltaY) {

    /** The copy below — its near door is the real entrance from the world. */
    public static final int COPY_NEAR = 0;
    /** The copy above — its far door is the real exit into the pocket area. */
    public static final int COPY_FAR = 1;
    /** Returned by {@link #copyAt} for a Y that is in neither copy. */
    public static final int COPY_NONE = -1;

    /**
     * Minimum corridor length. 32 keeps the midpoint more than 15 blocks — vanilla's maximum light
     * propagation — from either doorway, so no light from outside can reach the crossing point in
     * one copy but not the other. See the class javadoc.
     */
    public static final int MIN_LENGTH = 32;

    /** Minimum interior width and headroom. Headroom 3 leaves a 2-tall door plus a ceiling course. */
    public static final int MIN_WIDTH = 3;
    public static final int MIN_HEIGHT = 3;

    /**
     * Minimum clear space between the two copies' shells, in blocks. Keeps their Y windows disjoint
     * with room to spare, so {@link #copyAt} can never be ambiguous however generous the pads get.
     */
    public static final int MIN_COPY_GAP = 8;

    /**
     * Containment slack, in blocks. Generous enough that a player clipping a corner or stepping
     * through a doorway still reads as inside; far smaller than {@link #MIN_COPY_GAP}, so it can
     * never make the two copies' Y windows overlap.
     */
    private static final double PAD = 0.5;

    public PortalGeometry {
        if (length < MIN_LENGTH) {
            throw new IllegalArgumentException("corridor length " + length + " < MIN_LENGTH " + MIN_LENGTH);
        }
        if (width < MIN_WIDTH) {
            throw new IllegalArgumentException("corridor width " + width + " < MIN_WIDTH " + MIN_WIDTH);
        }
        if (height < MIN_HEIGHT) {
            throw new IllegalArgumentException("corridor height " + height + " < MIN_HEIGHT " + MIN_HEIGHT);
        }
        if (deltaY < height + 2 + MIN_COPY_GAP) {
            throw new IllegalArgumentException(
                "deltaY " + deltaY + " leaves less than MIN_COPY_GAP (" + MIN_COPY_GAP
                    + ") between the copies for height " + height);
        }
    }

    /**
     * The midpoint plane, in world X. Deliberately a half-block value (the corridor's exact centre)
     * so it never coincides with a block boundary a player can stand still on.
     */
    public double midX() {
        return originX + length / 2.0;
    }

    /** Floor Y of the given copy ({@link #COPY_NEAR} or {@link #COPY_FAR}). */
    public int floorYOf(int copy) {
        return copy == COPY_FAR ? floorY + deltaY : floorY;
    }

    /** World X of the near door's column (the entrance in the NEAR copy, a dummy in the FAR one). */
    public int nearDoorX() {
        return originX;
    }

    /** World X of the far door's column (the exit in the FAR copy, a dummy in the NEAR one). */
    public int farDoorX() {
        return originX + length - 1;
    }

    /** World Z of the doorway column — the centre of the corridor's width. */
    public int doorZ() {
        return originZ + width / 2;
    }

    /** Ceiling block Y of the FAR copy — the highest block the pair occupies. */
    public int highestBlockY() {
        return floorY + deltaY + height + 1;
    }

    /** True if the X/Z pair lies within the corridor footprint (either copy), pads included. */
    public boolean withinCorridorXZ(double x, double z) {
        return x >= originX - PAD && x <= originX + length + PAD
            && z >= originZ - PAD && z <= originZ + width + PAD;
    }

    /**
     * Which copy's interior the given Y sits in — {@link #COPY_NEAR}, {@link #COPY_FAR}, or
     * {@link #COPY_NONE}. The two windows are disjoint by {@link #MIN_COPY_GAP}.
     */
    public int copyAt(double y) {
        if (withinInteriorY(y, floorYOf(COPY_NEAR))) return COPY_NEAR;
        if (withinInteriorY(y, floorYOf(COPY_FAR))) return COPY_FAR;
        return COPY_NONE;
    }

    private boolean withinInteriorY(double y, int fy) {
        return y >= fy - PAD && y <= fy + height + 1 + PAD;
    }

    /** True if the position is inside either copy's corridor interior. */
    public boolean insideCorridor(double x, double y, double z) {
        return withinCorridorXZ(x, z) && copyAt(y) != COPY_NONE;
    }

    /**
     * The Y displacement needed to put a position in the copy its side of the midpoint demands:
     * {@code 0} when it is already correct (or outside the corridor entirely), {@code +deltaY} to
     * lift a past-the-midpoint position from NEAR to FAR, {@code -deltaY} for the reverse.
     *
     * <p>Positions outside the corridor return {@code 0} on purpose: the pocket area beyond the far
     * door and the open world beyond the near door are both outside the footprint, so a player who
     * has walked out of either end is left alone in whichever copy they exited into.</p>
     *
     * <p>The midpoint tie ({@code x == midX()}) resolves to NEAR via the strict {@code >}, so
     * exactly one of the two branches can ever fire.</p>
     */
    public int requiredShift(double x, double y, double z) {
        if (!withinCorridorXZ(x, z)) return 0;
        int copy = copyAt(y);
        if (copy == COPY_NONE) return 0;

        boolean pastMidpoint = x > midX();
        if (copy == COPY_NEAR && pastMidpoint) return deltaY;
        if (copy == COPY_FAR && !pastMidpoint) return -deltaY;
        return 0;
    }
}
