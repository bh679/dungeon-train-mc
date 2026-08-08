package games.brennan.dungeontrain.portal;

/**
 * Whether a portal carriage is the way in or the way out of its pocket room.
 *
 * <p>A portal occupies one whole carriage group: the group's <b>first</b> carriage is the ENTRY and
 * its <b>last</b> is the EXIT, so the two form a pair sharing a single structure,
 * {@code twin(ENTRY) → room → twin(EXIT)}. The carriages between them are ordinary train, skipped by
 * walking through the room.</p>
 *
 * <p><b>Why the pair may not span two groups.</b> A group is one Sable sub-level. The pair's
 * structure is stamped once, in the ENTRY carriage's chunk columns, and the EXIT resolves its own
 * frame against that same structure — so an exit in a neighbouring group would be mapping into a
 * room anchored to a carriage that drifts independently of it, and the two halves of the illusion
 * would disagree by however much the groups had moved apart. Keying both roles off the group index
 * makes that unrepresentable rather than merely avoided.</p>
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
     * Carriages a pair spans: first slot to last slot of its group.
     *
     * <p>Also the offset from an ENTRY to its EXIT, which is what makes the pair's two carriages
     * addressable from each other without consulting the group at all.</p>
     */
    public static int span(int groupSize) {
        return Math.max(1, groupSize) - 1;
    }

    /**
     * The role of the portal carriage at {@code carriageIndex} — ENTRY on its group's first carriage,
     * EXIT on its last.
     *
     * <p>Only meaningful for indices {@link PortalCarriageSelection} has already accepted; a carriage
     * in the middle of a group is neither, and reports ENTRY here by falling through. Callers reach
     * this only after the selection check, which is the same order the roles were always resolved in.</p>
     *
     * <p>{@link Math#floorMod} rather than {@code %} because carriage indices go negative when the
     * train extends backwards, and {@code %} keeps the sign of its left operand — which would put
     * slot 0 in the wrong place either side of the origin.</p>
     */
    public static PortalCarriageRole roleFor(int carriageIndex, int groupSize) {
        return Math.floorMod(carriageIndex, Math.max(1, groupSize)) == 0 ? ENTRY : EXIT;
    }

    /**
     * The index of the other carriage in this one's pair: the group's last carriage for an ENTRY,
     * its first for an EXIT.
     */
    public static int partnerIndex(int carriageIndex, int groupSize) {
        return roleFor(carriageIndex, groupSize) == ENTRY
            ? carriageIndex + span(groupSize)
            : carriageIndex - span(groupSize);
    }

    /**
     * The ENTRY carriage of the pair {@code carriageIndex} belongs to — the key a pair is stored
     * under, and the anchor index of its group, matching
     * {@code GateContext.groupAnchorPIdx} and the keys of {@code Trains.knownGroups}.
     *
     * <p>{@link Math#floorDiv} for the same reason as above: integer division truncates toward zero,
     * which would make two adjacent groups either side of the origin share an anchor.</p>
     */
    public static int entryIndexOf(int carriageIndex, int groupSize) {
        int size = Math.max(1, groupSize);
        return Math.floorDiv(carriageIndex, size) * size;
    }
}
