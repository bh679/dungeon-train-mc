package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * Decides which carriages along the train belong to a portal, and in what part.
 *
 * <p><b>A portal is a whole carriage group</b> — entry corridor, one cart, exit corridor — rather
 * than two corridors picked independently and however far apart the spacing happened to put them:</p>
 *
 * <pre>
 *   slot:   0            1             2          3+
 *         ENTRY   →   middle   →     EXIT   →  ordinary…
 * </pre>
 *
 * <p><b>Why the cart between them is not an ordinary carriage.</b> It is unreachable, and always was.
 * Walking forward through the entry corridor, the swap fires before the far door; walking back toward
 * the exit corridor, its near half swaps you out before you get there. So whatever sits between an
 * entry and its exit is sealed for good. Under the old rule that was every carriage in the gap —
 * three of them at the default spacing — each one rolled a variant, took a parts overlay, contents,
 * loot and mobs, and was then walled off from every player forever. Now it is exactly one carriage,
 * and it comes from {@link PortalCarriageBuilder#middleVariant()} so it is deliberate dead space
 * someone authored rather than three accidents.</p>
 *
 * <p><b>Which groups get one is a lottery, not a cadence.</b> Portals used to land on every nth group
 * exactly, which read as machinery: once a player had seen two, they knew where the next one was.
 * A group now wins a portal when a hash of (world seed, group ordinal) comes up, so they arrive
 * one group in {@code every} on average and at no particular beat.</p>
 *
 * <p><b>Hashed rather than rolled</b>, so the answer is still stable: a carriage index yields the same
 * verdict on every reload and for every player, which matters because a carriage's blocks are
 * re-stamped whenever the rolling window brings it back round. Drawing from a {@code RandomSource}
 * would let a corridor turn into an ordinary carriage under a player standing in it.</p>
 *
 * <p>Mixing the world seed in is what keeps the lottery from being the same lottery everywhere: a
 * seedless hash would put portals at identical group ordinals in every world ever generated.</p>
 */
public final class PortalCarriageSelection {

    /** Carriages a portal occupies: entry, the cart between, exit. */
    public static final int PORTAL_GROUP_SPAN = 3;

    /** Slot of the entry corridor within its group. */
    public static final int SLOT_ENTRY = 0;
    /** Slot of the cart between the two corridors. */
    public static final int SLOT_MIDDLE = 1;
    /** Slot of the exit corridor within its group. */
    public static final int SLOT_EXIT = 2;

    /** One group in twenty holds a portal, on average. */
    public static final int DEFAULT_CARRIAGE_EVERY = 20;

    /** Value meaning "no group holds a portal". */
    public static final int CARRIAGE_EVERY_OFF = 0;

    private PortalCarriageSelection() {}

    /**
     * A carriage's slot within its group.
     *
     * <p>{@link Math#floorMod} because carriage indices go negative when the train extends backwards,
     * and {@code %} alone would mirror the slot order either side of the origin — putting the exit
     * where the entry belongs on half the track.</p>
     */
    public static int slotOf(int carriageIndex, int groupSize) {
        return Math.floorMod(carriageIndex, Math.max(1, groupSize));
    }

    /** The anchor index of the group a carriage belongs to — and the key its portal is stored under. */
    public static int groupAnchorOf(int carriageIndex, int groupSize) {
        return carriageIndex - slotOf(carriageIndex, groupSize);
    }

    /**
     * True if this group won a portal, one group in {@code every} on average.
     *
     * <p>The draw is a hash of the group's ordinal and the world seed rather than a modulo, so
     * portals arrive at no fixed beat while every reader — the placer, the relay, the tick that
     * builds the pair — keeps getting the same answer for the same group forever.</p>
     */
    public static boolean isPortalGroup(int carriageIndex, int groupSize, int every, long worldSeed) {
        if (every <= CARRIAGE_EVERY_OFF) return false;
        // A group too short to hold entry, cart and exit gets no portal at all rather than half of
        // one — an entry corridor whose exit landed in the next group would strand anyone using it.
        if (groupSize < PORTAL_GROUP_SPAN) return false;
        // Every group, without troubling the hash — and the case the group-arithmetic tests use.
        if (every == 1) return true;

        long groupIndex = Math.floorDiv((long) carriageIndex, Math.max(1, groupSize));
        return Math.floorMod(hash(worldSeed, groupIndex), (long) every) == 0L;
    }

    /**
     * Splitmix64 finalizer over (world seed, group ordinal) — same constants as
     * {@link games.brennan.dungeontrain.worldgen.StampRandom#at} and
     * {@code DungeonTrainWorldData.deriveGenerationSeed}, so the draw stays decorrelated from
     * vanilla's own seed-derived streams and from DT's other seeded decisions.
     *
     * <p>Always non-negative: {@code floorMod} would cope with a negative hash, but the group
     * ordinal already goes negative behind the origin and one sign question per lottery is
     * enough.</p>
     */
    private static long hash(long worldSeed, long groupIndex) {
        long h = worldSeed ^ (groupIndex * 0x9E3779B97F4A7C15L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return (h ^ (h >>> 31)) >>> 1;
    }

    /** True if this carriage is one of a portal's two corridors. */
    public static boolean isPortalCarriage(ServerLevel level, int carriageIndex) {
        return isPortalCarriage(carriageIndex, DungeonTrainConfig.getGroupSize(), every(level), seed(level));
    }

    /** True if this carriage is the cart between a portal's two corridors. */
    public static boolean isPortalMiddle(ServerLevel level, int carriageIndex) {
        return isPortalMiddle(carriageIndex, DungeonTrainConfig.getGroupSize(), every(level), seed(level));
    }

    /**
     * True if this carriage is any part of a portal — either corridor, or the cart between.
     *
     * <p>The predicate every system that must leave a portal alone asks: the placer skips the shell,
     * parts and contents passes for one ({@code CarriagePlacer.placeAt}), and the shared-carriage
     * relay neither serves nor pools one ({@code TrainAssembler.tryLeaseShared}) — a corridor has to
     * match its twin block-for-block, and the cart between two corridors is sealed space.</p>
     */
    public static boolean isPortalPart(ServerLevel level, int carriageIndex) {
        return isPortalPart(carriageIndex, DungeonTrainConfig.getGroupSize(), every(level), seed(level));
    }

    public static boolean isPortalCarriage(int carriageIndex, int groupSize, int every, long worldSeed) {
        if (!isPortalGroup(carriageIndex, groupSize, every, worldSeed)) return false;
        int slot = slotOf(carriageIndex, groupSize);
        return slot == SLOT_ENTRY || slot == SLOT_EXIT;
    }

    public static boolean isPortalMiddle(int carriageIndex, int groupSize, int every, long worldSeed) {
        if (!isPortalGroup(carriageIndex, groupSize, every, worldSeed)) return false;
        return slotOf(carriageIndex, groupSize) == SLOT_MIDDLE;
    }

    public static boolean isPortalPart(int carriageIndex, int groupSize, int every, long worldSeed) {
        return isPortalCarriage(carriageIndex, groupSize, every, worldSeed)
            || isPortalMiddle(carriageIndex, groupSize, every, worldSeed);
    }

    private static int every(ServerLevel level) {
        return PortalRegistry.get(level).carriageEvery();
    }

    /**
     * The world's persisted generation seed — the same one the rest of DT's generation draws from,
     * so the lottery differs between worlds and survives a reload rather than being re-drawn.
     */
    private static long seed(ServerLevel level) {
        return DungeonTrainWorldData.get(level).getGenerationSeed();
    }
}
