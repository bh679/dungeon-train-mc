package games.brennan.dungeontrain.train.id;

import java.util.Arrays;
import java.util.OptionalInt;

/**
 * What one carriage group was built with, beyond its seed and index: the inputs a rebuild can't
 * derive again once the train has moved on. Written once per group at placement
 * ({@link CarriageIdLedger}), read back to give each of the group's carriages its full id.
 *
 * @param groupSize      carriages in the group
 * @param level          difficulty level the group was gated at
 * @param phase          {@code TrainPhase} ordinal the group was gated at
 * @param bandMask       {@code BandFlavor} bits the group sat in
 * @param version        the mod's minor version when the group was built
 * @param portal         the portal lottery fired for this group
 * @param transitionMask slots a transition flatbed claimed ({@code 1 << slot})
 * @param forced         an admin forcing setting was on when it was built
 * @param sharedBySlot   relay build stamped into each slot, {@link #NOT_SHARED} where none was
 */
public record GroupRecord(int groupSize, int level, int phase, int bandMask, int version,
                          boolean portal, int transitionMask, boolean forced, int[] sharedBySlot) {

    /** {@link #sharedBySlot} value for a slot generated here rather than leased from the relay. */
    public static final int NOT_SHARED = -1;

    private static final int HEADER = 7;

    public GroupRecord {
        if (groupSize < 1) throw new IllegalArgumentException("groupSize must be at least 1: " + groupSize);
        if (sharedBySlot == null || sharedBySlot.length != groupSize) {
            throw new IllegalArgumentException("sharedBySlot needs one entry per slot");
        }
        sharedBySlot = sharedBySlot.clone();
    }

    @Override
    public int[] sharedBySlot() {
        return sharedBySlot.clone();
    }

    /**
     * The id context for the carriage in {@code slot} of this group. The level is left out when it is
     * {@code baselineLevel}, the level the group's carriage number gives, so most ids don't carry it.
     */
    public CarriageId.Full contextFor(int slot, int baselineLevel) {
        int shared = slot >= 0 && slot < groupSize ? sharedBySlot[slot] : NOT_SHARED;
        int lvl = Math.max(0, level);
        return new CarriageId.Full(lvl == baselineLevel ? OptionalInt.empty() : OptionalInt.of(lvl),
            phase, bandMask, Math.floorMod(version, CarriageId.VERSION_MODULUS),
            portal, transitionMask, forced, shared == NOT_SHARED ? OptionalInt.empty() : OptionalInt.of(shared));
    }

    /** Packed for saving: a header of seven ints, then one int per slot. */
    int[] toInts() {
        int[] out = new int[HEADER + groupSize];
        out[0] = groupSize;
        out[1] = level;
        out[2] = phase;
        out[3] = bandMask;
        out[4] = version;
        out[5] = (portal ? 1 : 0) | (forced ? 2 : 0);
        out[6] = transitionMask;
        System.arraycopy(sharedBySlot, 0, out, HEADER, groupSize);
        return out;
    }

    /** The inverse of {@link #toInts}, or {@code null} for a malformed entry. */
    static GroupRecord fromInts(int[] in) {
        if (in == null || in.length < HEADER + 1) return null;
        int groupSize = in[0];
        if (groupSize < 1 || in.length != HEADER + groupSize) return null;
        try {
            return new GroupRecord(groupSize, in[1], in[2], in[3], in[4],
                (in[5] & 1) != 0, in[6], (in[5] & 2) != 0, Arrays.copyOfRange(in, HEADER, in.length));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GroupRecord r && groupSize == r.groupSize && level == r.level && phase == r.phase
            && bandMask == r.bandMask && version == r.version && portal == r.portal
            && transitionMask == r.transitionMask && forced == r.forced && Arrays.equals(sharedBySlot, r.sharedBySlot);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Objects.hash(groupSize, level, phase, bandMask, version, portal, transitionMask, forced)
            + Arrays.hashCode(sharedBySlot);
    }

    @Override
    public String toString() {
        return "GroupRecord[size=" + groupSize + " level=" + level + " phase=" + phase + " bands=" + bandMask
            + " version=" + version + " portal=" + portal + " transition=" + transitionMask + " forced=" + forced
            + " shared=" + Arrays.toString(sharedBySlot) + "]";
    }
}
