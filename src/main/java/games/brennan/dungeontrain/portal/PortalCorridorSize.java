package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;

/**
 * How long a portal corridor is, and where it sits relative to its carriage slot.
 *
 * <p><b>There are two shapes</b> ({@link PortalCorridorKind}), and every method here is a pure
 * function of the world's carriage dims <i>and</i> the kind. Which one a pair drew is decided once,
 * by {@link PortalCarriageSelection#corridorKindFor}, and carried on its {@link PortalStructure}.</p>
 *
 * <p><b>{@link PortalCorridorKind#LONG} — a corridor longer than the carriage it is placed in.</b>
 * A portal owns a whole group of three — {@code ENTRY | cart | EXIT}
 * ({@link PortalCarriageSelection}) — and the cart between the two corridors is unreachable by
 * construction, so a corridor confined to its own slot leaves a full carriage of sealed dead space
 * in the middle of the group. Instead each corridor grows <i>inward</i> into that cart until the two
 * nearly meet:</p>
 *
 * <pre>
 *   x: 0 ─────────────── 12 │ 13 │ 14 ─────────────── 26      (L = 9)
 *      ENTRY corridor (13)   wall   EXIT corridor (13)
 *      ├──── cart slot: x 9..17 ────┤
 * </pre>
 *
 * <p><b>{@link PortalCorridorKind#SHORT} — a corridor that is exactly its slot.</b> The overrun is
 * zero, so a portal carriage is the same box as every other carriage, the cart between the pair
 * survives whole, and the "centre wall" degenerates to the entire cart:</p>
 *
 * <pre>
 *   x: 0 ────── 8 │ 9 ────── 17 │ 18 ────── 26                (L = 9)
 *      ENTRY (9)   cart, sealed    EXIT (9)
 * </pre>
 *
 * <p>Everything downstream falls out of {@link #overrun} — the length, the origin offset, the width
 * of what is left of the cart — so {@code SHORT} needed no second set of rules, only a zero.</p>
 *
 * <p><b>{@code LONG}'s rule is derived, not configured.</b> It is fixed the same way
 * {@code PLUG_DEPTH} is: one number the whole system agrees on. It is written against
 * {@link CarriageDims} rather than the default 9 because a world may use any carriage length in
 * {@code [CarriageDims.MIN_LENGTH, MAX_LENGTH]}, and the corridor has to stay a sensible shape at
 * either end of that range.</p>
 *
 * <p><b>Why {@code (L - 1) / 2} and not a multiplier.</b> A float ratio has to be rounded, and the
 * rounding is what decides whether the two corridors leave a wall between them, meet exactly, or
 * overlap — an overlap being two corridors writing different blocks into the same cells. Halving the
 * cart instead makes the wall fall out of the arithmetic: {@link #centreWallWidth} is
 * {@code L - 2 * overrun}, which is 1 for odd {@code L} and 2 for even, and never zero. At the
 * default {@code L = 9} that gives a 13-block corridor, ≈1.44× a carriage.</p>
 *
 * <p><b>No kind-less overloads.</b> An implicit default is exactly how the two copies of one
 * crossing drift apart — a carriage stamped at one length and its twin at another is a hole in the
 * illusion, not a compile error. Every caller states which corridor it means.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalCorridorSize {

    private PortalCorridorSize() {}

    /**
     * How far each corridor reaches past its own slot, into the cart between the pair.
     *
     * <p>{@link PortalCorridorKind#SHORT} reaches nowhere. For {@link PortalCorridorKind#LONG} it is
     * half the cart, rounded down, so the two corridors growing toward each other always leave at
     * least one block of it standing between them.</p>
     *
     * <p><b>Capped at {@link CarriageDims#MAX_LENGTH}.</b> {@link #corridorDims} builds a real
     * {@code CarriageDims}, whose constructor throws outside {@code [MIN_LENGTH, MAX_LENGTH]} — so
     * without this a world configured with a long carriage (anything over 21) would crash on its
     * first portal rather than build a shorter one. Past that point the corridor simply stops
     * growing and the pair keeps the one-carriage-per-corridor shape it always had.</p>
     */
    public static int overrun(CarriageDims dims, PortalCorridorKind kind) {
        if (kind == PortalCorridorKind.SHORT) return 0;
        return Math.max(0, Math.min((dims.length() - 1) / 2,
            CarriageDims.MAX_LENGTH - dims.length()));
    }

    /** A corridor's length along X — its own slot plus its {@link #overrun} into the cart. */
    public static int corridorLength(CarriageDims dims, PortalCorridorKind kind) {
        return dims.length() + overrun(dims, kind);
    }

    /**
     * What survives of the cart between the two corridors, and so how wide the sealed wall at the
     * centre of the group is. Always at least 1, by {@link #overrun}'s rounding; under
     * {@link PortalCorridorKind#SHORT} it is the whole cart.
     */
    public static int centreWallWidth(CarriageDims dims, PortalCorridorKind kind) {
        return dims.length() - 2 * overrun(dims, kind);
    }

    /**
     * The dims a corridor's blocks occupy — the world's carriage dims with the length replaced.
     *
     * <p>This is what the corridor's template is stored and validated against
     * ({@code CarriagePlacer.sizeMatches}), and it is why the two kinds cannot share one: a
     * {@code LONG} corridor's {@code portal} template is a different size from every other carriage
     * template, while a {@code SHORT} corridor's {@code portal_short} is exactly a carriage.</p>
     */
    public static CarriageDims corridorDims(CarriageDims dims, PortalCorridorKind kind) {
        return new CarriageDims(corridorLength(dims, kind), dims.width(), dims.height());
    }

    /**
     * Where a corridor's minimum corner goes, given the minimum corner of the carriage slot it was
     * placed for.
     *
     * <p>{@link PortalCarriageRole#ENTRY} keeps its slot's origin and runs forward into the cart.
     * {@link PortalCarriageRole#EXIT} is pulled <i>back</i> by the overrun so it still ends flush
     * with the trailing edge of its slot — its far door is the way back onto the train and cannot
     * move. Under {@link PortalCorridorKind#SHORT} neither moves: the corridor is the slot.</p>
     */
    public static int originOffsetX(PortalCarriageRole role, CarriageDims dims,
                                    PortalCorridorKind kind) {
        return role == PortalCarriageRole.EXIT ? -overrun(dims, kind) : 0;
    }
}
