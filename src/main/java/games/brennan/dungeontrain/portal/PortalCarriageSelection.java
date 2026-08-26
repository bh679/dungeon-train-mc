package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * sporadic rather than clumpy — an unconstrained draw puts two back to back often enough to notice.
 * The gap is the <b>survival lottery's</b> alone: creative's fixed cadence never consults it.</p>
 *
 * <p><b>The lottery does not start at the origin.</b> Nothing before Diff-Level
 * {@link #MIN_PORTAL_LEVEL} holds a portal, and the draw begins counting at that boundary rather
 * than running from the origin and discarding what it drew — see {@link #firstEligibleGroup()}.</p>
 *
 * <p>The one exception is a level where everyone is in creative, which takes an <b>exact cadence</b>
 * rather than a rate to draw against — every nth group, no seed involved, no gap rule and no
 * Diff-Level gate. Its default is {@link #CREATIVE_EVERY}, separate from survival's, so creative can
 * be dense without moving what a survival run meets. See {@link #rateFor}, which also spells out what riding on a mutable input
 * costs.</p>
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

    /** One group in fifteen holds a portal, on average — the survival lottery's rate. */
    public static final int DEFAULT_CARRIAGE_EVERY = 15;

    /** Value meaning "no group holds a portal". */
    public static final int CARRIAGE_EVERY_OFF = 0;

    /**
     * The Diff-Level a stretch of track must be at before any of it holds a portal.
     *
     * <p>Portals are not an opening-minutes surprise. A player who meets one in the first few
     * carriages meets it before they have any read on what the train is — while the onboarding ramp
     * is still holding hostiles back — and the portal stops being the thing the run grows into and
     * becomes the second thing that happened. Two levels in, they know the train, and a corridor that
     * is plainly not part of it lands as one.</p>
     *
     * <p>Zero-based, the same scale as {@link games.brennan.dungeontrain.template.TemplateGate}'s
     * {@code minLevel} and the stage presets. At the default twenty carriages a level and a one-level
     * progression delay, level two begins sixty carriages out — see {@link #firstEligibleGroup()},
     * which is where this is turned into a group ordinal.</p>
     *
     * <p><b>This is the position frame, and the boarding HUD is not.</b> The HUD's Diff-Level comes
     * from {@code BoardingProgressData.travelledCarriageIndex} — carriages counted from wherever the
     * player <em>boarded</em> — while this counts from the train's origin. A player who chased the
     * train and got on nine carriages in therefore runs nine carriages behind the track, and can
     * reach the first portal while their HUD still reads level one. That gap is not a bug to close
     * here: the position frame is the only one a portal verdict may read. The player frame is
     * per-world mutable and depends on who is online, and a verdict that moved with it would turn a
     * corridor into an ordinary carriage under whoever was standing in it — see the class note above.
     * Raising this constant to paper over the boarding lag was considered and declined; the gate is
     * about where the <em>track</em> is, and every other template gate reads the same frame.</p>
     */
    public static final int MIN_PORTAL_LEVEL = 2;

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
     * The cadence a dev build stands in for the world's own rate with, while nobody has set one:
     * every 2nd group, as the whole system worked before the lottery. Riding to a portal at the
     * shipped rate is a poor loop for testing one, and a dev build is exactly where that matters and
     * nobody's play experience is at stake.
     *
     * <p>A stand-in, not an override — {@code /dungeontrain portal carriage <n>} beats it, so the
     * command is testable in the dev client. See {@link #creativeEvery}.</p>
     */
    public static final int DEV_CREATIVE_EVERY = 2;

    /**
     * The cadence creative gets where the world's rate has not been set by hand: every 5th group.
     *
     * <p>Creative is the build-and-look-around mode and a portal is the thing worth looking at, so it
     * arrives on a fixed beat and often. Deliberately <b>separate from
     * {@link #DEFAULT_CARRIAGE_EVERY}</b>: making creative dense must not move what a survival run
     * meets, and one stored number cannot default to two things at once.</p>
     *
     * <p>It coincides with {@link #MIN_GROUP_GAP} by arithmetic rather than by meaning — that constant
     * is the lottery's anti-clumping floor, which a periodic rate never consults.</p>
     */
    public static final int CREATIVE_EVERY = 5;

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
     * {@link Rate#periodic} rate takes a fixed every-nth cadence instead; see
     * {@link #DEV_CREATIVE_EVERY} for the one case that uses it.</p>
     */
    public static boolean isPortalGroup(int carriageIndex, int groupSize, Rate rate, long worldSeed) {
        return isPortalGroup(carriageIndex, groupSize, rate, worldSeed, 0);
    }

    /**
     * As {@link #isPortalGroup(int, int, Rate, long)}, but with the opening stretch of the track shut
     * out: no group nearer the origin than {@code firstEligibleGroup} holds a portal, and the draw
     * <b>starts counting there</b> rather than running from the origin and having its early results
     * thrown away.
     *
     * <p><b>Why the draw is re-indexed rather than merely filtered.</b> A filtered draw burns its
     * first {@code firstEligibleGroup} results against dead track, so the first portal lands one in
     * {@code every} groups after the gate <em>by luck</em> — at the shipped rate that is a long ride
     * past the gate as often as not, and the player who has finally earned portals gets nothing to
     * show for it. Re-indexing makes the gate the draw's own origin, so the wait past it is the
     * ordinary between-portals wait.</p>
     *
     * <p>The gate is measured in group ordinals from the origin and applies to both directions of
     * travel, since {@link games.brennan.dungeontrain.difficulty.DifficultyProgression#positionTier}
     * likewise reads the distance behind the origin as a magnitude.</p>
     *
     * @param firstEligibleGroup groups nearer the origin than this hold no portal; {@code 0} for no
     *                           gate, which reproduces the ungated draw exactly
     */
    public static boolean isPortalGroup(int carriageIndex, int groupSize, Rate rate, long worldSeed,
                                        int firstEligibleGroup) {
        if (rate.isOff()) return false;
        // A group too short to hold entry, cart and exit gets no portal at all rather than half of
        // one — an entry corridor whose exit landed in the next group would strand anyone using it.
        if (groupSize < PORTAL_GROUP_SPAN) return false;

        long groupIndex = Math.floorDiv((long) carriageIndex, Math.max(1, groupSize));

        // The dev-creative cadence is deliberately dense and already evenly spaced, so it skips the
        // gap rule outright — five apart is the opposite of what it is for. It skips the difficulty
        // gate with it: the point of that cadence is a portal a short ride from spawn while testing,
        // and a tester who had to travel to Diff-Level MIN_PORTAL_LEVEL first would not have one.
        if (rate.periodic()) return Math.floorMod(groupIndex, (long) rate.every()) == 0L;

        long gate = Math.max(0, firstEligibleGroup);
        if (Math.abs(groupIndex) < gate) return false;
        // The gate is the draw's origin. Shifting toward zero on each side rather than subtracting a
        // signed offset keeps the track behind the origin drawing outward like the track ahead.
        long drawIndex = groupIndex >= 0 ? groupIndex - gate : groupIndex + gate;

        // Every group, without troubling the hash — and the case the group-arithmetic tests use.
        if (rate.every() == 1) return true;

        // Denser than the gap can carry, so the draw is a certainty — and a certainty cannot go
        // through the suppression below, where every group would be knocked out by the one behind it
        // and the train would end up with no portals at all. Space them by the gap directly instead:
        // the densest arrangement the constraint permits, which is what such a rate is asking for.
        if (drawThreshold(rate.every()) >= DRAW_PRECISION) {
            return Math.floorMod(drawIndex, (long) MIN_GROUP_GAP) == 0L;
        }

        if (!drewHit(worldSeed, drawIndex, rate.every())) return false;

        // Suppressed by any hit in the four groups behind it, so two portals can never land within
        // MIN_GROUP_GAP: were they to, the earlier one's hit would sit inside the later one's window
        // and would have taken it out. Earlier wins.
        //
        // Deliberately looking at raw HITS rather than at whether those groups were themselves
        // chosen — that would recurse back down the train with no floor, and this has to answer
        // from the group's own ordinal alone, the same on every reload and for every reader.
        for (long back = 1; back < MIN_GROUP_GAP; back++) {
            // Nothing to be too close to on the far side of the gate: the groups there hold no
            // portal, so letting their raw hits suppress the first eligible ones would push the
            // first portal back past the gate for no reason. Ungated (gate 0) the draw is one
            // unbroken line through the origin and keeps looking straight through it, as before.
            if (gate > 0 && drawIndex - back < 0) break;
            if (drewHit(worldSeed, drawIndex - back, rate.every())) return false;
        }
        return true;
    }

    /**
     * The first group ordinal a portal may land on: the one whose anchor carriage sits at
     * {@code minLevel} on the position-derived Diff-Level scale, which is
     * {@code (minLevel + delay) * carriagesPerTier} carriages out, rounded up to a whole group.
     *
     * <p>Pure (params in) so it is unit-testable without a NeoForge config bootstrap, in the same
     * shape as {@link games.brennan.dungeontrain.difficulty.DifficultyProgression#effectiveTier}.
     * A {@code minLevel} of zero or less gates nothing.</p>
     */
    static int firstEligibleGroup(int minLevel, int groupSize, int carriagesPerTier, int delay) {
        if (minLevel <= 0) return 0;
        long boundaryCarriage = (long) (minLevel + Math.max(0, delay)) * Math.max(1, carriagesPerTier);
        long span = Math.max(1, groupSize);
        return (int) Math.min(Integer.MAX_VALUE, (boundaryCarriage + span - 1) / span);
    }

    /**
     * {@link #firstEligibleGroup(int, int, int, int)} at {@link #MIN_PORTAL_LEVEL} and the configured
     * progression shape.
     *
     * <p><b>Read from the config alone, never from {@code DifficultyProgression.positionTier}.</b>
     * That method folds in the {@code /dungeontrain difficulty} admin offset, which a player can move
     * mid-session — and a portal verdict that moves is exactly what the class note above rules out:
     * a corridor re-stamped after the offset changed would come back an ordinary carriage under
     * whoever was standing in it. The offset re-themes what generates ahead; it does not decide
     * whether a portal is there.</p>
     */
    public static int firstEligibleGroup() {
        return firstEligibleGroup(MIN_PORTAL_LEVEL,
                DungeonTrainConfig.getGroupSize(),
                DungeonTrainConfig.getCarriagesPerTier(),
                DungeonTrainConfig.getProgressionLevelDelay());
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
        int groupSize = DungeonTrainConfig.getGroupSize();
        if (isForcedGroup(carriageIndex, groupSize)) {
            int slot = slotOf(carriageIndex, groupSize);
            return slot == SLOT_ENTRY || slot == SLOT_EXIT;
        }
        return isPortalCarriage(carriageIndex, groupSize, rateFor(level), generationSeed(level),
                firstEligibleGroup());
    }

    /** True if this carriage is the cart between a portal's two corridors. */
    public static boolean isPortalMiddle(ServerLevel level, int carriageIndex) {
        int groupSize = DungeonTrainConfig.getGroupSize();
        if (isForcedGroup(carriageIndex, groupSize)) {
            return slotOf(carriageIndex, groupSize) == SLOT_MIDDLE;
        }
        return isPortalMiddle(carriageIndex, groupSize, rateFor(level), generationSeed(level),
                firstEligibleGroup());
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
        if (isForcedGroup(carriageIndex, DungeonTrainConfig.getGroupSize())) {
            return isPortalCarriage(level, carriageIndex) || isPortalMiddle(level, carriageIndex);
        }
        return isPortalPart(carriageIndex, DungeonTrainConfig.getGroupSize(), rateFor(level), generationSeed(level),
                firstEligibleGroup());
    }

    /**
     * True if the group this carriage belongs to holds a portal — <b>including</b> any ordinary slots
     * beyond the entry/middle/exit three.
     *
     * <p>The predicate for things that must stay out of a portal group entirely rather than merely off
     * its corridors — PlayerMob spawning, which is the one placement path that puts a live, <em>mobile</em>
     * entity on the train. At the default group size of three this and {@link #isPortalPart} agree, since
     * the portal fills the group; they part company only when {@code groupSize} exceeds
     * {@link #PORTAL_GROUP_SPAN}, and there the group-level answer is the one that holds: a mob in slot 3
     * marches along the train and walks itself into the corridor regardless.</p>
     */
    public static boolean isPortalGroup(ServerLevel level, int carriageIndex) {
        int groupSize = DungeonTrainConfig.getGroupSize();
        if (isForcedGroup(carriageIndex, groupSize)) return true;
        return isPortalGroup(carriageIndex, groupSize, rateFor(level), generationSeed(level),
                firstEligibleGroup());
    }

    /**
     * True if a debug tool has forced this carriage's group to hold a portal — see
     * {@link PortalForcedGroups}.
     *
     * <p>Asked <b>before</b> the rate and the Diff-Level gate, so a forced group is a portal in a
     * world with portals switched off and on the opening stretch of track the gate reserves. The
     * one rule it cannot override is the group-span one: a group too short to hold entry, cart and
     * exit gets no portal at all rather than half of one, whoever asked.</p>
     */
    private static boolean isForcedGroup(int carriageIndex, int groupSize) {
        if (PortalForcedGroups.isEmpty()) return false;
        if (groupSize < PORTAL_GROUP_SPAN) return false;
        return PortalForcedGroups.isForced(Math.floorDiv((long) carriageIndex, Math.max(1, groupSize)));
    }

    public static boolean isPortalCarriage(int carriageIndex, int groupSize, Rate rate, long worldSeed) {
        return isPortalCarriage(carriageIndex, groupSize, rate, worldSeed, 0);
    }

    public static boolean isPortalCarriage(int carriageIndex, int groupSize, Rate rate, long worldSeed,
                                           int firstEligibleGroup) {
        if (!isPortalGroup(carriageIndex, groupSize, rate, worldSeed, firstEligibleGroup)) return false;
        int slot = slotOf(carriageIndex, groupSize);
        return slot == SLOT_ENTRY || slot == SLOT_EXIT;
    }

    public static boolean isPortalMiddle(int carriageIndex, int groupSize, Rate rate, long worldSeed) {
        return isPortalMiddle(carriageIndex, groupSize, rate, worldSeed, 0);
    }

    public static boolean isPortalMiddle(int carriageIndex, int groupSize, Rate rate, long worldSeed,
                                         int firstEligibleGroup) {
        if (!isPortalGroup(carriageIndex, groupSize, rate, worldSeed, firstEligibleGroup)) return false;
        return slotOf(carriageIndex, groupSize) == SLOT_MIDDLE;
    }

    public static boolean isPortalPart(int carriageIndex, int groupSize, Rate rate, long worldSeed) {
        return isPortalPart(carriageIndex, groupSize, rate, worldSeed, 0);
    }

    public static boolean isPortalPart(int carriageIndex, int groupSize, Rate rate, long worldSeed,
                                       int firstEligibleGroup) {
        return isPortalCarriage(carriageIndex, groupSize, rate, worldSeed, firstEligibleGroup)
            || isPortalMiddle(carriageIndex, groupSize, rate, worldSeed, firstEligibleGroup);
    }

    /**
     * The rate this level is currently drawing at: a fixed cadence while everyone on it is in
     * creative, otherwise the world's stored lottery rate.
     *
     * <p><b>Game mode is not a stable input, and that is the price of this feature.</b> Everything
     * else about the selection is fixed for the life of a world; this one input a player can change
     * at will, and when they do, groups the rolling window has not stamped yet answer differently —
     * so a group that would have held a corridor can come back ordinary, and the other way about.
     * Carriages already stamped keep what they have, so a world switched mid-run ends up with a
     * mixed train. That is understood and accepted: creative is a mode you enter to look at the
     * train rather than to run it, and the alternative — a creative cadence that only takes effect
     * in a fresh world — is not what the mode is for.</p>
     *
     * <p><b>This method answers about what to stamp NEXT, and about nothing that is already
     * standing.</b> That distinction is load-bearing and used not to be drawn. {@code
     * PortalCarriageEvents} re-derived the verdict every tick and built a corridor swap plane from
     * it, so after a flip it claimed carriages that had been stamped as ordinary ones — and a swap
     * plane covers a whole carriage interior, so walking down a normal carriage teleported the
     * player into a pocket room. The verdict is now written down when the blocks are laid
     * ({@link PortalRegistry#noteStamped}) and read back from there
     * ({@link PortalStampRecord}); a mid-run switch moves what generates ahead, and re-decides
     * nothing behind.</p>
     *
     * <p><b>Every player, not any.</b> One carriage has one verdict — the invariant the placer, the
     * relay and the pair tick all lean on — so the cadence cannot be per-player. A survival player
     * sharing a level with a creative one therefore holds the whole level at the lottery rate. On a
     * shared server that means one survival player silently changes what the creative players get;
     * there is no per-player answer available, so this is the honest one. An empty level keeps the
     * stored rate.</p>
     *
     * <p><b>One number, read two ways.</b> Creative and survival share the world's stored rate: in
     * survival it is a rate the seeded lottery draws against, in creative it is an exact period —
     * every nth group, no seed, no Diff-Level gate. Same setting, so
     * {@code /dungeontrain portal carriage 5} means "about every 5th" to one and "every 5th" to the
     * other, and {@code portal carriage off} means no portals to both.</p>
     */
    public static Rate rateFor(ServerLevel level) {
        PortalRegistry registry = PortalRegistry.get(level);
        int stored = registry.carriageEvery();
        if (stored <= CARRIAGE_EVERY_OFF) return Rate.OFF;
        if (!isAllCreative(level)) return Rate.lottery(stored);
        return Rate.periodic(creativeEveryFor(registry));
    }

    /**
     * The exact cadence creative runs at: the world's rate, unless this is a dev build whose rate
     * nobody has set, in which case the dense testing cadence stands in for it.
     *
     * <p><b>An explicit setting wins, including on a dev build.</b> An unconditional dev override
     * makes {@code /dungeontrain portal carriage 7} appear to do nothing in the dev client — the
     * command reports the new rate, the world stores it, and the train keeps stamping every
     * {@link #DEV_CREATIVE_EVERY} groups. The substitution is a convenience for a world nobody has
     * an opinion about, so it steps aside the moment someone does.</p>
     */
    private static int creativeEveryFor(PortalRegistry registry) {
        return creativeEvery(registry.carriageEvery(), registry.isCarriageEverySet(),
            DungeonTrain.isDevBuild());
    }

    /** {@link #creativeEveryFor} with its inputs supplied — the testable form. */
    static int creativeEvery(int stored, boolean setByHand, boolean devBuild) {
        if (setByHand) return stored;
        return devBuild ? DEV_CREATIVE_EVERY : CREATIVE_EVERY;
    }

    /**
     * True where at least one player is logged in on this level and every one of them is in creative
     * — see {@link #rateFor} for why it is every player rather than any.
     */
    public static boolean isAllCreative(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return false;
        for (ServerPlayer player : players) {
            if (!player.gameMode.getGameModeForPlayer().isCreative()) return false;
        }
        return true;
    }

    /** True on a dev build that is also {@link #isAllCreative} — the dense testing default's case. */
    public static boolean isDevCreative(ServerLevel level) {
        return DungeonTrain.isDevBuild() && isAllCreative(level);
    }

    /**
     * Which of the two corridor shapes this pair draws — see {@link PortalCorridorKind}.
     *
     * <p><b>Drawn from the corridor variants' own weights</b>, with the pair's key as the index, so
     * {@code templates/weights.json} is the one place the mix is stated: both at 1 is the shipped
     * coin flip, {@code portal_short} at 0 turns short corridors off, and a 3:1 there is a 3:1 on the
     * train. Reusing {@code CarriagePlacer}'s own weighted pick rather than a private coin means the
     * numbers in that file mean the same thing here as everywhere else.</p>
     *
     * <p><b>Why the weights and not a hash.</b> A weight of 0 was what used to keep the portal
     * templates out of the ordinary carriage pool, and that job has moved to
     * {@link PortalCarriageBuilder#isPortalVariant} — which frees the weights to say the one thing
     * about a portal template a number can usefully say.</p>
     *
     * <p><b>Memoised, not merely deterministic — and it has to be.</b> The weights are the one input
     * to the whole selection that a player can move mid-session ({@code /dungeontrain editor
     * weight}), and a corridor exists twice: as a carriage on the train and as a static twin
     * underground, stamped by independent calls that pass no state between them. A re-weighting
     * between those two calls would stamp a 9-block carriage against a 13-block twin and tear the
     * crossing open. Caching the draw per {@code (worldSeed, pairKey)} means a pair keeps its shape
     * for the life of the server, exactly as {@link PortalCorridorContents} caches a pair's
     * furnishing for the same reason. New numbers take effect for pairs first drawn afterwards, and
     * for every pair on the next start.</p>
     */
    public static synchronized PortalCorridorKind corridorKindFor(ServerLevel level, int pairKey) {
        long worldSeed = generationSeed(level);
        if (kindSeed == null || kindSeed != worldSeed) {
            KIND_PICKS.clear();
            kindSeed = worldSeed;
        }
        return KIND_PICKS.computeIfAbsent(pairKey,
            key -> corridorKindFor(key, worldSeed, CarriageWeights.current()));
    }

    /** Which world {@link #KIND_PICKS} belongs to — a second world must not inherit the first's draws. */
    private static Long kindSeed;

    private static final Map<Integer, PortalCorridorKind> KIND_PICKS = new HashMap<>();

    /** Drop every cached corridor-kind draw — called when the server stops. */
    public static synchronized void clearCorridorKinds() {
        KIND_PICKS.clear();
        kindSeed = null;
    }

    /** {@link #corridorKindFor(ServerLevel, int)} with its inputs supplied — the testable form. */
    public static PortalCorridorKind corridorKindFor(int pairKey, long worldSeed,
                                                     CarriageWeights weights) {
        CarriageVariant picked = CarriagePlacer.weightedSeededPick(
            worldSeed, pairKey, PortalCarriageBuilder.corridorVariants(), weights);
        // An all-zero pool falls back to an unweighted pick rather than to nothing, so "both off"
        // still has to resolve to a shape. LONG is the one that shipped first.
        return picked.equals(PortalCarriageBuilder.portalVariant(PortalCorridorKind.SHORT))
            ? PortalCorridorKind.SHORT
            : PortalCorridorKind.LONG;
    }

    /**
     * The world's persisted generation seed — the same one the rest of DT's generation draws from,
     * so the lottery differs between worlds and survives a reload rather than being re-drawn.
     *
     * <p>Public because it is not only the lottery's seed: it is <b>the train's seed</b>, the same
     * value every ordinary carriage rolls its contents against ({@code CarriageGenerationConfig.seed},
     * built from this in {@code DungeonTrainWorldData.getGenerationConfig}). A portal corridor is a
     * carriage, so {@code PortalCarriageBuilder} rolls its shell and its contents here too rather
     * than against the raw {@code level.getSeed()} — which is a different, undecorrelated frame.</p>
     */
    public static long generationSeed(ServerLevel level) {
        return DungeonTrainWorldData.get(level).getGenerationSeed();
    }
}
