package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

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
 *
 * <p>Two portals are never closer than {@link #MIN_GROUP_GAP} groups, so the lottery reads as
 * sporadic rather than clumpy — an unconstrained draw puts two back to back often enough to notice.</p>
 *
 * <p>The one exception is a dev build with everyone in creative, which keeps the old every-2nd
 * cadence so a portal is always a short ride away while testing — see {@link #rateFor}.</p>
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

    /** One group in fifteen holds a portal, on average. */
    public static final int DEFAULT_CARRIAGE_EVERY = 15;

    /** Value meaning "no group holds a portal". */
    public static final int CARRIAGE_EVERY_OFF = 0;

    /**
     * Groups two portals must be apart, at the least.
     *
     * <p>An unconstrained lottery clumps: a rate of one in fifteen still puts two portals back to
     * back now and then, and a player who walks out of one exit corridor into the next entry has
     * been handed the machinery rather than a surprise. The gap costs nothing to enforce and buys
     * the sporadic feel the lottery was for.</p>
     *
     * <p>Five is also a ceiling on density: no lottery rate can average denser than one group in
     * about twelve while honouring it — see {@link #drawThreshold}.</p>
     */
    public static final int MIN_GROUP_GAP = 5;

    /**
     * The cadence a dev build hands a creative player: every 2nd group, as the whole system worked
     * before the lottery. Finding a portal at the shipped 1-in-20 means riding a long way, which is
     * a poor loop for testing one; creative on a dev build is exactly the case where that matters
     * and nobody's play experience is at stake.
     */
    public static final int DEV_CREATIVE_EVERY = 2;

    private PortalCarriageSelection() {}

    /**
     * How often groups win a portal, and by which rule.
     *
     * @param every     one group in this many, or {@link #CARRIAGE_EVERY_OFF} for none
     * @param periodic  {@code true} for the fixed every-nth cadence (dev-creative), {@code false}
     *                  for the seeded lottery that ordinary play uses
     */
    public record Rate(int every, boolean periodic) {

        /** No group holds a portal. */
        public static final Rate OFF = new Rate(CARRIAGE_EVERY_OFF, false);

        /** One group in {@code every}, drawn from the world seed. */
        public static Rate lottery(int every) {
            return new Rate(every, false);
        }

        /** Every {@code every}th group exactly, seed ignored. */
        public static Rate periodic(int every) {
            return new Rate(every, true);
        }

        public boolean isOff() {
            return every <= CARRIAGE_EVERY_OFF;
        }
    }

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
     * True if this group won a portal, one group in {@code rate.every()} on average.
     *
     * <p>The draw is a hash of the group's ordinal and the world seed rather than a modulo, so
     * portals arrive at no fixed beat while every reader — the placer, the relay, the tick that
     * builds the pair — keeps getting the same answer for the same group forever. A
     * {@link Rate#periodic} rate takes the old every-nth cadence instead; see
     * {@link #DEV_CREATIVE_EVERY} for the one case that uses it.</p>
     */
    public static boolean isPortalGroup(int carriageIndex, int groupSize, Rate rate, long worldSeed) {
        if (rate.isOff()) return false;
        // A group too short to hold entry, cart and exit gets no portal at all rather than half of
        // one — an entry corridor whose exit landed in the next group would strand anyone using it.
        if (groupSize < PORTAL_GROUP_SPAN) return false;
        // Every group, without troubling the hash — and the case the group-arithmetic tests use.
        if (rate.every() == 1) return true;

        long groupIndex = Math.floorDiv((long) carriageIndex, Math.max(1, groupSize));

        // The dev-creative cadence is deliberately dense and already evenly spaced, so it skips the
        // gap rule outright — five apart is the opposite of what it is for.
        if (rate.periodic()) return Math.floorMod(groupIndex, (long) rate.every()) == 0L;

        // Denser than the gap can carry, so the draw is a certainty — and a certainty cannot go
        // through the suppression below, where every group would be knocked out by the one behind it
        // and the train would end up with no portals at all. Space them by the gap directly instead:
        // the densest arrangement the constraint permits, which is what such a rate is asking for.
        if (drawThreshold(rate.every()) >= DRAW_PRECISION) {
            return Math.floorMod(groupIndex, (long) MIN_GROUP_GAP) == 0L;
        }

        if (!drewHit(worldSeed, groupIndex, rate.every())) return false;

        // Suppressed by any hit in the four groups behind it, so two portals can never land within
        // MIN_GROUP_GAP: were they to, the earlier one's hit would sit inside the later one's window
        // and would have taken it out. Earlier wins.
        //
        // Deliberately looking at raw HITS rather than at whether those groups were themselves
        // chosen — that would recurse back down the train with no floor, and this has to answer
        // from the group's own ordinal alone, the same on every reload and for every reader.
        for (long back = 1; back < MIN_GROUP_GAP; back++) {
            if (drewHit(worldSeed, groupIndex - back, rate.every())) return false;
        }
        return true;
    }

    /**
     * True if {@code every} is denser than {@link #MIN_GROUP_GAP} allows, so the realised spacing
     * will be every {@code MIN_GROUP_GAP}th group rather than the rate that was asked for. The
     * command says so out loud — a setting that quietly does something else reads as a bug.
     */
    public static boolean isGapClamped(int every) {
        return every > 1 && drawThreshold(every) >= DRAW_PRECISION;
    }

    /** One group's raw draw, before the gap rule takes any of them back out. */
    private static boolean drewHit(long worldSeed, long groupIndex, int every) {
        return Math.floorMod(hash(worldSeed, groupIndex), DRAW_PRECISION) < drawThreshold(every);
    }

    /** Denominator the draw is taken against. A millionth of a group is finer than anyone can feel. */
    private static final long DRAW_PRECISION = 1_000_000L;

    /** Rates the command accepts (1–64), solved once at class init rather than per call. */
    private static final int MAX_TABULATED_EVERY = 64;

    private static final long[] DRAW_THRESHOLDS = tabulateThresholds();

    /**
     * How often a group must draw a hit for one in {@code every} to <b>survive</b> the gap rule.
     *
     * <p>Suppression thins the draw, so hitting at one in fifteen and dropping everything inside the
     * gap would realise about one in twenty and make the number in the command a lie. The raw rate
     * is therefore solved from the wanted one: a hit survives when the {@code MIN_GROUP_GAP - 1}
     * groups behind it all missed, so</p>
     *
     * <pre>   p · (1 − p)^(gap−1) = 1 / every</pre>
     *
     * <p>which the fixed point {@code p ← target / (1 − p)^(gap−1)} settles at in a few dozen steps.</p>
     *
     * <p><b>Rates the gap cannot reach.</b> That left-hand side peaks around 0.082 — one group in
     * roughly twelve — so anything denser is simply not achievable while keeping portals five apart.
     * Those clamp to a certain hit, which the gap then thins to exactly every fifth group: as dense
     * as the constraint permits, and an honest answer rather than a silent near-miss.</p>
     */
    private static long drawThreshold(int every) {
        if (every >= 0 && every <= MAX_TABULATED_EVERY) return DRAW_THRESHOLDS[every];
        return solveThreshold(every);
    }

    private static long[] tabulateThresholds() {
        long[] thresholds = new long[MAX_TABULATED_EVERY + 1];
        for (int every = 0; every <= MAX_TABULATED_EVERY; every++) {
            thresholds[every] = solveThreshold(every);
        }
        return thresholds;
    }

    private static long solveThreshold(int every) {
        if (every <= CARRIAGE_EVERY_OFF) return 0L;

        double target = 1.0 / every;
        double p = target;
        for (int step = 0; step < 64; step++) {
            double next = target / Math.pow(1.0 - p, MIN_GROUP_GAP - 1);
            // Diverged past certainty: this rate is denser than the gap allows, so draw every time
            // and let the gap alone space them.
            if (!(next < 1.0)) return DRAW_PRECISION;
            p = next;
        }
        return Math.round(p * DRAW_PRECISION);
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
        return isPortalCarriage(carriageIndex, DungeonTrainConfig.getGroupSize(), rateFor(level), seed(level));
    }

    /** True if this carriage is the cart between a portal's two corridors. */
    public static boolean isPortalMiddle(ServerLevel level, int carriageIndex) {
        return isPortalMiddle(carriageIndex, DungeonTrainConfig.getGroupSize(), rateFor(level), seed(level));
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
        return isPortalPart(carriageIndex, DungeonTrainConfig.getGroupSize(), rateFor(level), seed(level));
    }

    public static boolean isPortalCarriage(int carriageIndex, int groupSize, Rate rate, long worldSeed) {
        if (!isPortalGroup(carriageIndex, groupSize, rate, worldSeed)) return false;
        int slot = slotOf(carriageIndex, groupSize);
        return slot == SLOT_ENTRY || slot == SLOT_EXIT;
    }

    public static boolean isPortalMiddle(int carriageIndex, int groupSize, Rate rate, long worldSeed) {
        if (!isPortalGroup(carriageIndex, groupSize, rate, worldSeed)) return false;
        return slotOf(carriageIndex, groupSize) == SLOT_MIDDLE;
    }

    public static boolean isPortalPart(int carriageIndex, int groupSize, Rate rate, long worldSeed) {
        return isPortalCarriage(carriageIndex, groupSize, rate, worldSeed)
            || isPortalMiddle(carriageIndex, groupSize, rate, worldSeed);
    }

    /**
     * The rate this level is currently drawing at: the world's stored one, unless a dev build has
     * handed a creative player the dense testing cadence.
     *
     * <p><b>Game mode is not a stable input, and that is the price here.</b> Everything else about
     * the selection is fixed for the life of a world; this one input a player can change at will,
     * and when they do, groups the rolling window has not stamped yet answer differently — a
     * corridor re-stamped after the switch can come back an ordinary carriage. That is only
     * tolerable because it is fenced to dev builds ({@link DungeonTrain#isDevBuild()}, branch is not
     * {@code main}), where the point of the world is testing. It must never reach a release.</p>
     *
     * <p><b>Every player, not any.</b> One carriage has one verdict — the invariant the placer, the
     * relay and the pair tick all lean on — so the override cannot be per-player. A survival player
     * sharing a level with a creative one therefore holds the whole level at the normal rate, which
     * is also the plain reading of "survival is normal even on a dev build". An empty level keeps
     * the stored rate.</p>
     */
    public static Rate rateFor(ServerLevel level) {
        int stored = PortalRegistry.get(level).carriageEvery();
        if (stored <= CARRIAGE_EVERY_OFF) return Rate.OFF;
        return isDevCreative(level) ? Rate.periodic(DEV_CREATIVE_EVERY) : Rate.lottery(stored);
    }

    /** True on a dev build where at least one player is logged in and all of them are in creative. */
    public static boolean isDevCreative(ServerLevel level) {
        if (!DungeonTrain.isDevBuild()) return false;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return false;
        for (ServerPlayer player : players) {
            if (!player.gameMode.getGameModeForPlayer().isCreative()) return false;
        }
        return true;
    }

    /**
     * The world's persisted generation seed — the same one the rest of DT's generation draws from,
     * so the lottery differs between worlds and survives a reload rather than being re-drawn.
     */
    private static long seed(ServerLevel level) {
        return DungeonTrainWorldData.get(level).getGenerationSeed();
    }
}
