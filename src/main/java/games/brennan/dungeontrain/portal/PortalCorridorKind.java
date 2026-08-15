package games.brennan.dungeontrain.portal;

/**
 * Which of the two portal corridor shapes a pair was built to.
 *
 * <p>Both cross identically — same twin, same pocket room, same midpoint rule. They differ only in
 * how much of the sealed cart between the pair the two corridors eat, which is what
 * {@link PortalCorridorSize} turns into a length, an origin offset and a centre wall.</p>
 *
 * <p><b>Which kind a pair gets is a weighted draw over the two corridor variants</b> — see
 * {@link PortalCarriageSelection#corridorKindFor}. With both weighted 1 in
 * {@code templates/weights.json} that is a coin flip, and setting either to 0 turns that kind off.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public enum PortalCorridorKind {

    /**
     * The corridor that grows <i>inward</i> into the cart between the pair until the two nearly
     * meet — ≈1.44× a carriage, 13 blocks at the default dims, leaving a one-block centre wall.
     */
    LONG,

    /**
     * The corridor that stays inside its own carriage slot, so a portal carriage is the same box as
     * every other carriage and the cart between the pair survives whole.
     *
     * <p>This is the geometry {@link PortalCarriageLayout}'s own plan view is drawn for: doors at
     * {@code x0}/{@code x8}, baffles at {@code x2}/{@code x6}, crossing zone {@code x3..x5}.</p>
     */
    SHORT;

    /** The kind a corridor of unknown provenance is assumed to be — the one that shipped first. */
    public static final PortalCorridorKind DEFAULT = LONG;

    public boolean isShort() {
        return this == SHORT;
    }
}
