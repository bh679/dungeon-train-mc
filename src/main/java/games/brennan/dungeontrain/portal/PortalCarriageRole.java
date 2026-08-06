package games.brennan.dungeontrain.portal;

/**
 * Whether a portal carriage is the way in or the way out of its pocket room.
 *
 * <p>Portal carriages alternate along the train, so consecutive ones form a pair sharing a single
 * structure: {@code twin(ENTRY) → room → twin(EXIT)}.</p>
 *
 * <p><b>Why two carriages rather than two corridors on one.</b> The swap rule is symmetric — before
 * the midpoint you belong on the train, past it you belong in the twin. A second twin carrying that
 * same rule teleports the player the moment they step into it, landing them <i>before</i> the
 * carriage's midpoint and still walking forwards, so two steps later they cross it again and are
 * back in the room: a revolving door. The exit corridor needs the opposite rule, whose train side is
 * its far half, and that requires a carriage the player leaves through its far door.</p>
 *
 * <p>The mirror lives in the rule, not the blocks. Reversing the corridor's geometry would flip the
 * view ahead of the player at the moment of the swap, since their walking direction has to map
 * consistently between frames — which is why {@link PortalCarriageLayout} is mirror-symmetric and
 * both roles stamp identical blocks.</p>
 */
public enum PortalCarriageRole {

    /** Walked in from the train, out into the room. Its near half is the train side. */
    ENTRY,

    /** Walked in from the room, out onto the train. Its far half is the train side. */
    EXIT;

    /**
     * The role of the portal carriage at {@code carriageIndex}, given portals every {@code every}
     * carriages. Alternates ENTRY, EXIT, ENTRY, … so consecutive portal carriages pair up.
     *
     * <p>{@link Math#floorDiv} rather than {@code /} because carriage indices go negative when the
     * train extends backwards, and integer division truncates toward zero — which would repeat a
     * role either side of the origin and break the pairing there.</p>
     */
    public static PortalCarriageRole roleFor(int carriageIndex, int every) {
        long ordinal = Math.floorDiv((long) carriageIndex, Math.max(1, every));
        return Math.floorMod(ordinal, 2L) == 0L ? ENTRY : EXIT;
    }

    /**
     * The index of the other carriage in this one's pair: the next portal carriage along for an
     * ENTRY, the previous one for an EXIT.
     */
    public static int partnerIndex(int carriageIndex, int every) {
        return roleFor(carriageIndex, every) == ENTRY
            ? carriageIndex + every
            : carriageIndex - every;
    }

    /** The ENTRY carriage of the pair {@code carriageIndex} belongs to — the key a pair is stored under. */
    public static int entryIndexOf(int carriageIndex, int every) {
        return roleFor(carriageIndex, every) == ENTRY ? carriageIndex : carriageIndex - every;
    }
}
