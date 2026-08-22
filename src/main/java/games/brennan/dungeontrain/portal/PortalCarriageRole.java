package games.brennan.dungeontrain.portal;

/**
 * Whether a portal corridor is the way in or the way out of its pocket room.
 *
 * <p>A portal is one carriage group — {@code ENTRY | middle cart | EXIT} — so a corridor's role is
 * simply which slot of its group it sits in, and the pair is found without arithmetic: the other
 * corridor is the other end of the same group. See {@link PortalCarriageSelection}.</p>
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
     * The role of the portal corridor at {@code carriageIndex}.
     *
     * <p>Only meaningful for a carriage {@link PortalCarriageSelection#isPortalCarriage} accepts;
     * anything else answers {@link #ENTRY} and means nothing by it.</p>
     */
    public static PortalCarriageRole roleFor(int carriageIndex, int groupSize) {
        return PortalCarriageSelection.slotOf(carriageIndex, groupSize) == PortalCarriageSelection.SLOT_EXIT
            ? EXIT
            : ENTRY;
    }

    /** The index of the other corridor in this one's pair — the other end of the same group. */
    public static int partnerIndex(int carriageIndex, int groupSize) {
        int anchor = PortalCarriageSelection.groupAnchorOf(carriageIndex, groupSize);
        return roleFor(carriageIndex, groupSize) == ENTRY
            ? anchor + PortalCarriageSelection.SLOT_EXIT
            : anchor + PortalCarriageSelection.SLOT_ENTRY;
    }

    /**
     * The key a pair's structure is stored under: its group's anchor.
     *
     * <p>Which is also the entry corridor's own index, since the entry sits in slot 0 — so both
     * corridors of a pair, and the cart between them, resolve to one key without either having to
     * know the other's index.</p>
     */
    public static int entryIndexOf(int carriageIndex, int groupSize) {
        return PortalCarriageSelection.groupAnchorOf(carriageIndex, groupSize);
    }

    /**
     * The real carriage index of the corridor filling {@code role} in the group anchored at
     * {@code pairKey} — the key {@code PortalCarriageBuilder} rolls a corridor's shell and contents
     * against.
     *
     * <p>{@code pairKey} comes from {@link #entryIndexOf}, so it is already a group anchor and this
     * is just the slot added back on. Every site that stamps a corridor knows its role — the on-train
     * carriage ({@code CarriagePlacer} computes it from {@link #roleFor}), the underground twin and
     * every exit copy ({@code PortalCarriageBuilder.stampCorridorHalf} takes it as a parameter) — so
     * all of them derive this identically with no state passing between them. That purity is what the
     * crossing needs: a player is only ever mapped between a carriage and the twin of the <b>same</b>
     * role, so entry and exit are free to furnish differently while each role's copies still agree
     * block-for-block.</p>
     */
    public static int corridorIndexOf(int pairKey, PortalCarriageRole role) {
        return pairKey + (role == EXIT
            ? PortalCarriageSelection.SLOT_EXIT
            : PortalCarriageSelection.SLOT_ENTRY);
    }
}
