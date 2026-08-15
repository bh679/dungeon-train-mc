package games.brennan.dungeontrain.portal;

/**
 * Layout of the hallway corridor <b>inside a single carriage</b>, in carriage-local coordinates
 * ({@code 0,0,0} = the carriage origin, X along the train, Y up, Z across).
 *
 * <p><b>Not {@link DimensionalCarriageLayout}.</b> Two different things in this package are called
 * a carriage. This one is the <i>train-side</i> carriage that hosts the portal doorway — a real
 * carriage on the train, sized to {@code CarriageDims} like every other. The dimensional carriage
 * is the pocket space the doorway leads into, which is not on the train and has its own free-size
 * geometry. When you want the space a player walks through after stepping into the portal, you
 * want the other class.</p>
 *
 * <p>Derived from the world's {@link games.brennan.dungeontrain.train.CarriageDims} rather than
 * fixed numbers, because {@code CarriagePlacer.sizeMatches} requires every template to match those
 * dims exactly — a portal carriage is the same 9×7×7 box as every other carriage by default, and
 * has to adapt if a world uses different dims.</p>
 *
 * <pre>
 *   x:  0     1      2..6      7     8
 *      door baffle  CROSSING baffle door
 *                   midpoint at length/2
 * </pre>
 *
 * <p><b>Light is solved by saturation, not distance.</b> The free-standing portal keeps its midpoint
 * more than 15 blocks from any doorway so no outside light can reach it
 * ({@link PortalGeometry#MIN_LENGTH}). Nine blocks cannot do that, and a closed door is no help:
 * {@code BlockBehaviour.getLightBlock} returns 1 for anything not solid-render, so doors block a
 * single light level rather than sealing. Instead the crossing zone is lit to <b>15</b> by its own
 * lanterns in both copies. Minecraft block light is the <i>maximum</i> over sources, not a sum, so
 * once the local value is pinned at max, light leaking in from the train cannot change it — the two
 * copies read identically however bright the neighbouring carriage is.</p>
 *
 * <p><b>Baffles handle what light saturation cannot: sight.</b> A player before the midpoint must
 * never see through to the far end, where the two copies genuinely differ (dead space on one side,
 * the pocket area on the other). The baffle columns break the straight line without needing the
 * length the free-standing version relies on.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 *
 * @param length carriage length along X (dims length)
 * @param height carriage height (dims height)
 * @param width  carriage width along Z (dims width)
 */
public record PortalCarriageLayout(int length, int height, int width) {

    /** Smallest carriage this layout can be built in: two doors, two baffles and a crossing gap. */
    public static final int MIN_LENGTH = 7;
    /** Floor, two blocks of headroom for a door, and a ceiling. */
    public static final int MIN_HEIGHT = 4;
    /** Walls either side of a one-block walkway, plus a baffle to step around. */
    public static final int MIN_WIDTH = 5;

    /** Containment slack in blocks, matching {@link PortalGeometry}. */
    private static final double PAD = 0.5;

    public PortalCarriageLayout {
        if (length < MIN_LENGTH) {
            throw new IllegalArgumentException("carriage length " + length + " < " + MIN_LENGTH);
        }
        if (height < MIN_HEIGHT) {
            throw new IllegalArgumentException("carriage height " + height + " < " + MIN_HEIGHT);
        }
        if (width < MIN_WIDTH) {
            throw new IllegalArgumentException("carriage width " + width + " < " + MIN_WIDTH);
        }
    }

    /** Local X of the midpoint plane — the corridor's exact centre, deliberately off a block boundary. */
    public double midX() {
        return length / 2.0;
    }

    /** Local X of the entrance door (the real one in the carriage, a dummy in the twin). */
    public int nearDoorX() {
        return 0;
    }

    /** Local X of the exit door (a dummy in the carriage, the real one in the twin). */
    public int farDoorX() {
        return length - 1;
    }

    /**
     * Local X of the baffle inside each door — <b>two</b> blocks in, not one.
     *
     * <p>One block in makes the corridor unwalkable. The door plane is solid except its doorway
     * column, and a baffle immediately behind it blocks that same column, so the only route from the
     * doorway to the open side is a diagonal step between two solid corners. Minecraft refuses that:
     * the two blocks touch at an edge, leaving a zero-width gap that a 0.6-wide player cannot pass.
     * Leaving a clear column between door and baffle makes every step orthogonal — through the door,
     * sideways, then on.</p>
     *
     * <p>The sight-line is unaffected: a straight run down the doorway's own column still meets a
     * baffle at each end.</p>
     */
    public int nearBaffleX() {
        return 2;
    }

    public int farBaffleX() {
        return length - 3;
    }

    /**
     * Local Z of the walkway centre line, which the doorways sit on.
     *
     * <p><b>Authoring constraint for the {@code portal} template:</b> keep its doorways on this line.
     * The pocket room's two openings are cut to meet a corridor here, so doors authored off-centre
     * would open onto the room's wall.</p>
     */
    public int doorZ() {
        return width / 2;
    }

    /**
     * Local Z the baffle blocks off, up to and including this value, at <b>both</b>
     * {@link #nearBaffleX()} and {@link #farBaffleX()}. Sits on the walkway centre so the player
     * steps around it, which is what breaks the line of sight through to the far end.
     *
     * <p>Both baffles block the same side, which makes the corridor mirror-symmetric. That is a look
     * preference, <b>not</b> a requirement: a pair's carriage and its twin are stamped from the same
     * source whatever their {@link PortalCarriageRole}, and nothing is ever mirrored between them, so
     * a staggered S-bend would cross just as seamlessly. (An earlier version of this comment claimed
     * symmetry was load-bearing for the exit role. It is not.)</p>
     *
     * <p>Symmetry costs nothing in sight-blocking either way: a straight line down the walkway centre
     * is interrupted at <i>both</i> baffles, so neither doorway is ever visible from the crossing
     * zone.</p>
     */
    public int baffleZ() {
        return doorZ();
    }

    /** Local Z the player detours through to pass a baffle. */
    public int detourZ() {
        return doorZ() + 1;
    }

    /** Local Y of the floor blocks; the walkable surface is one above. */
    public int floorY() {
        return 0;
    }

    /** Local Y of the ceiling blocks. */
    public int ceilingY() {
        return height - 1;
    }

    /** Interior headroom in blocks, between floor and ceiling. */
    public int interiorHeight() {
        return height - 2;
    }

    /** First and last interior Z (the walls sit just outside these). */
    public int interiorMinZ() {
        return 1;
    }

    public int interiorMaxZ() {
        return width - 2;
    }

    /**
     * True if a local X lies in the crossing zone — the stretch between the two baffles, which must
     * be lit to 15 so no light difference can survive there.
     */
    public boolean isCrossingZone(int localX) {
        return localX > nearBaffleX() && localX < farBaffleX();
    }

    /**
     * True if a local cell is part of the corridor's <b>exterior shell</b> — the floor, the ceiling,
     * either side wall, or a solid part of an end plane.
     *
     * <p>This is the surface that separates the corridor from whatever is outside it, and a hole in
     * it is what {@link PortalSever} treats as fatal to the illusion: on the train side the hole
     * shows open sky beside a moving carriage, and in the twin it shows the pocket room's plug. The
     * two copies then visibly differ.</p>
     *
     * <p>Three things inside the box are deliberately <b>not</b> shell, because breaking them opens
     * nothing to the outside:</p>
     * <ul>
     *   <li>the <b>crossing-zone lanterns</b>, which sit in the floor line but read as a fitting;</li>
     *   <li>the <b>baffles</b>, which are free-standing pillars in the interior;</li>
     *   <li>the <b>doorway column</b> of each end plane, so a door — including the dead dummy one —
     *       can still be broken the way any door can.</li>
     * </ul>
     *
     * <p>{@code PortalCarriageBuilder.stateAt} is written against this, so the shape has one
     * definition rather than two free to drift apart.</p>
     */
    public boolean isShellCell(int dx, int dy, int dz) {
        // The lantern is tested first because it sits ON the floor line, which the next test claims.
        if (dy == floorY() && isCrossingZone(dx) && dz >= interiorMinZ() && dz <= interiorMaxZ()) {
            return false;
        }
        if (dy == floorY() || dy == ceilingY()) return true;
        if (dz < interiorMinZ() || dz > interiorMaxZ()) return true;
        if (dx == nearDoorX() || dx == farDoorX()) return !isDoorwayCell(dy, dz);
        return false;
    }

    /** The two-block column an end plane leaves open for its door. */
    public boolean isDoorwayCell(int dy, int dz) {
        return dz == doorZ() && dy <= floorY() + 2;
    }

    /**
     * True if a local X lies on the <b>twin</b> side of the midpoint for a corridor in this role —
     * the half a player standing there would have been teleported out of.
     *
     * <p>The same side rule {@link PortalFrames#requiredMove} applies, and mirrored for the same
     * reason: an {@link PortalCarriageRole#ENTRY} corridor puts the train on its near half, an
     * {@link PortalCarriageRole#EXIT} corridor on its far half.</p>
     *
     * <p><b>No {@link PortalFrames#SWAP_HYSTERESIS} here.</b> That band exists to absorb the
     * positional jitter of a player riding a Sable carriage. A block cell does not jitter, so the
     * plain midpoint is the honest line — and widening it would leave a band of shell either side of
     * the midpoint that could be mined with no consequence.</p>
     */
    public boolean isTwinSideLocalX(double localX, PortalCarriageRole role) {
        return role == PortalCarriageRole.ENTRY ? localX > midX() : localX < midX();
    }

    /**
     * The corridor interior as a box in local coordinates, pads included.
     *
     * <p>Exists so the volume can be handed to a spatial query — the puppet pass asks the level for
     * every entity in a corridor — without restating the bounds {@link #insideCorridor} tests. Two
     * copies of that arithmetic would be free to drift apart, and an entity search that disagreed
     * with the containment rule would put a puppet in a corridor the swap says you are not in.</p>
     */
    public record Bounds(double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) {}

    /** {@link Bounds} of this corridor. */
    public Bounds localBounds() {
        return new Bounds(
            -PAD, floorY() - PAD, interiorMinZ() - PAD,
            length + PAD, ceilingY() + PAD, interiorMaxZ() + 1 + PAD);
    }

    /** True if the local position is inside the corridor interior, pads included. */
    public boolean insideCorridor(double localX, double localY, double localZ) {
        Bounds b = localBounds();
        return localX >= b.minX() && localX <= b.maxX()
            && localY >= b.minY() && localY <= b.maxY()
            && localZ >= b.minZ() && localZ <= b.maxZ();
    }

    /**
     * Which copy a local X belongs in — {@link PortalGeometry#COPY_NEAR} before the midpoint,
     * {@link PortalGeometry#COPY_FAR} after it. The tie resolves to NEAR via the strict {@code >},
     * exactly as the free-standing portal does, so the two implementations cannot disagree about a
     * player standing on the line.
     */
    public int copyForLocalX(double localX) {
        return localX > midX() ? PortalGeometry.COPY_FAR : PortalGeometry.COPY_NEAR;
    }
}
