package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.narrative.LeaderboardBookFactory;
import games.brennan.dungeontrain.narrative.LeaderboardCategory;
import games.brennan.dungeontrain.narrative.LeaderboardPool;
import games.brennan.dungeontrain.narrative.PortalStatRoomTribute;
import games.brennan.dungeontrain.narrative.RunStatBookFactory;
import games.brennan.dungeontrain.narrative.RunStatBookTag;
import games.brennan.dungeontrain.narrative.RunStatSubject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stocks a Stat Room: one book for every number the mod can report.
 *
 * <h2>What "every" means</h2>
 * <p>One run-stat note per {@link RunStatSubject} and one leaderboard book per
 * {@link LeaderboardCategory} — {@link #FULL_SET} books between them, 49 as the mod stands. The
 * notes need nothing fetched and go up immediately; the boards each need a fetch, so the room fills
 * in over the first minutes rather than all at once, and a server with no relay holds the notes and
 * nothing else.</p>
 *
 * <h2>Filled again and again, never twice</h2>
 * <p>{@link #stock} is called on every portal tick for as long as the room is incomplete, and each
 * call places only what {@code already} does not name. That is what makes the incremental fill safe:
 * a board that lands on the fortieth tick goes onto a free shelf, and the thirty-nine books already
 * standing there are neither moved nor duplicated.</p>
 *
 * <h2>Known residual: the reader</h2>
 * <p>Both kinds of book are about a person, and shelves are world state. The boards' closing "where
 * you stand" line and the notes' first fill are resolved against the rider the room was stocked for —
 * so on a shared server, a second player reads the first player's standing. This is the same residual
 * {@code PortalRoomLibrarian.shelvable} documents for the author room, and closing it properly means
 * rendering pages per-viewer rather than placing them.</p>
 */
public final class PortalRoomStatShelves {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Run-stat notes in a full set: one per subject. */
    public static final int STAT_BOOK_COUNT = RunStatSubject.values().length;

    /** Leaderboard books in a full set: one per board. */
    public static final int LEADERBOARD_BOOK_COUNT = LeaderboardCategory.values().length;

    /** Books a complete Stat Room holds. */
    public static final int FULL_SET = STAT_BOOK_COUNT + LEADERBOARD_BOOK_COUNT;

    /** Key prefix for a leaderboard board, so the two id spaces cannot collide. */
    private static final String KEY_BOARD = "lb:";

    /** Key prefix for a run-stat note. */
    private static final String KEY_STAT = "stat:";

    /** Seeds the per-book roll off the pair key, so a re-stamped room bakes the same books. */
    private static final long SALT_BOOK = 0x57A75B00C5EEDL;

    /** Separates this room's shelf order from the library room's, which shuffles on the same key. */
    private static final long SALT_ORDER = 0x57A7534F4C464CL;

    private PortalRoomStatShelves() {}

    /**
     * What one pass over a stat room achieved.
     *
     * @param placed  the keys now standing in the room — {@code already} plus anything just shelved
     * @param stalled true when the pass had books to shelve and nowhere to put a single one. Distinct
     *                from "nothing to do": a room merely waiting on the relay is not stalled and must
     *                keep asking, whereas a room whose every shelf slot is already spoken for will
     *                never do better and should stop being ticked.
     */
    public record Progress(Set<String> placed, boolean stalled) {}

    /**
     * Put whatever this room is still missing onto its free shelves.
     *
     * <p>Returns {@code already} unchanged, unstalled, when nothing new has arrived — which on a room
     * still waiting for boards is most ticks.</p>
     *
     * @param already the keys this room is known to hold, from previous calls
     * @param reader  the rider the room was stocked for — whose standing the boards close on
     */
    public static Progress stock(ServerLevel level, BlockPos origin, Vec3i size,
                                 Set<String> already, int pairKey, UUID reader) {
        if (level == null || origin == null || size == null) return new Progress(already, false);

        // Cheap, pure, and first: this runs on EVERY server tick for as long as the room is
        // incomplete, and the overwhelmingly common answer is "nothing new has arrived". Deciding
        // that with a few dozen set lookups, before scanning a room-sized box of block positions or
        // building a single book, is what keeps a room waiting on the relay from costing anything.
        if (missingStats(already).isEmpty()
            && missingBoards(already, LeaderboardPool.populated()).isEmpty()) {
            return new Progress(already, false);
        }

        Map<String, ItemStack> missing = catalogue(already, pairKey, reader);
        if (missing.isEmpty()) return new Progress(already, false);

        List<BlockPos> shelves = PortalRoomLibrary.shelvesIn(level, origin, size);
        if (shelves.isEmpty()) return new Progress(already, true);

        // The note goes up with the first books, not after the last: a room that will spend minutes
        // filling should explain itself the moment a player can read anything in it.
        PortalRoomLibrary.dressLecterns(level, origin, size,
            () -> PortalStatRoomTribute.buildStack(pairKey));

        List<String> order = dealOrder(new ArrayList<>(missing.keySet()), pairKey);
        List<ItemStack> stacks = new ArrayList<>(order.size());
        for (String key : order) stacks.add(missing.get(key));

        int placed = PortalRoomLibrary.placeInto(level, shelves, stacks);
        // Shelves exist and not one took a book: every slot was already spoken for by the template.
        // More books will not help, so say stalled rather than come back every tick to find out again.
        if (placed <= 0) return new Progress(already, true);

        Set<String> now = new HashSet<>(already);
        now.addAll(order.subList(0, placed));
        LOGGER.info("[DungeonTrain] Stat room {} placed {} book(s) — {}/{} shelved",
            pairKey, placed, now.size(), FULL_SET);
        return new Progress(now, false);
    }

    /**
     * The books this room is missing, keyed so the caller can remember them.
     *
     * <p>Ordered ({@link LinkedHashMap}) purely so the result is reproducible before the shuffle —
     * two runs of the same tick must produce the same list for {@link #dealOrder} to mean anything.</p>
     */
    static Map<String, ItemStack> catalogue(Set<String> already, int pairKey, UUID reader) {
        Map<String, ItemStack> out = new LinkedHashMap<>();

        for (RunStatSubject subject : missingStats(already)) {
            out.put(KEY_STAT + subject.id(), statBook(subject, pairKey));
        }

        // Only the boards that have actually arrived. An unfetched board builds to empty, and asking
        // again next tick is exactly how this room fills itself in.
        for (LeaderboardCategory category : missingBoards(already, LeaderboardPool.populated())) {
            LeaderboardBookFactory.build(category, reader)
                .ifPresent(stack -> out.put(KEY_BOARD + category.id(), stack));
        }
        return out;
    }

    /**
     * The run-stat subjects this room does not hold yet.
     *
     * <p>Split out from {@link #catalogue} so the bookkeeping — which is where a duplicate would come
     * from — can be tested without building a single {@code ItemStack}, and therefore without a server.</p>
     */
    static List<RunStatSubject> missingStats(Set<String> already) {
        List<RunStatSubject> out = new ArrayList<>();
        for (RunStatSubject subject : RunStatSubject.values()) {
            if (!already.contains(KEY_STAT + subject.id())) out.add(subject);
        }
        return out;
    }

    /**
     * The boards this room does not hold yet AND could actually shelve now.
     *
     * <p>Two filters, and they are different questions: {@code already} is what is standing on the
     * shelves, {@code populated} is what the relay has served. A board that is neither is simply not
     * yet — the room asks again next tick.</p>
     */
    static List<LeaderboardCategory> missingBoards(Set<String> already,
                                                   List<LeaderboardCategory> populated) {
        List<LeaderboardCategory> out = new ArrayList<>();
        for (LeaderboardCategory category : populated) {
            if (!already.contains(KEY_BOARD + category.id())) out.add(category);
        }
        return out;
    }

    /** This room's key for one board — how a shelved board is remembered across ticks. */
    static String boardKey(LeaderboardCategory category) {
        return KEY_BOARD + category.id();
    }

    /** This room's key for one run stat. */
    static String statKey(RunStatSubject subject) {
        return KEY_STAT + subject.id();
    }

    /**
     * One shelf-ready run-stat note, pinned to {@code subject}.
     *
     * <p>The loot path lets the seed choose a subject from what the finder's run has done enough of;
     * a Stat Room cannot, because the whole point is that every subject is here. So the subject is
     * written onto the stack up front with an empty rendered value, which is the state
     * {@code RunStatBookFactory.refresh} reads as "pinned, never filled" — it then bakes the reader's
     * own number at the first hand exactly as it does for a looted note.</p>
     *
     * <p><b>This deliberately bypasses {@code RunStatSubject.eligible},</b> so a Stat Room can hold a
     * note reading zero. That is correct here: a room advertising every possible book has to hold the
     * ones the reader has not earned yet, or it is not the set it claims to be.</p>
     */
    private static ItemStack statBook(RunStatSubject subject, int pairKey) {
        ItemStack stack = RunStatBookFactory.create(bookSeed(subject.id(), pairKey));
        RunStatBookTag.recordBaked(stack, subject, "");
        return stack;
    }

    /** A stable per-book seed, so the same room re-stamps to the same wording. */
    private static long bookSeed(String key, int pairKey) {
        return mix(pairKey * 0x9E3779B97F4A7C15L + key.hashCode(), SALT_BOOK);
    }

    /**
     * The keys in the order this room shelves them.
     *
     * <p>Shuffled per pair for the same reason the library room shuffles: enum order would put every
     * run-stat note on the bottom shelf and every board above it, in the same arrangement in every
     * stat room in the world. Seeded, so a re-stamp deals the same way.</p>
     *
     * <p><b>Only the missing books are shuffled</b>, which means a board arriving late lands wherever
     * there is room rather than in its "proper" place. Nothing depends on the final arrangement, and
     * the alternative — reshuffling the whole set each tick — would have to move books a player may
     * already have taken.</p>
     */
    static List<String> dealOrder(List<String> keys, int pairKey) {
        List<String> order = new ArrayList<>(keys);
        long state = pairKey * 0x9E3779B97F4A7C15L + SALT_ORDER;
        for (int i = order.size() - 1; i > 0; i--) {
            state = mix(state, SALT_ORDER);
            int j = (int) Math.floorMod(state, i + 1L);
            String tmp = order.get(i);
            order.set(i, order.get(j));
            order.set(j, tmp);
        }
        return order;
    }

    /** Splittable-mix, so neighbouring pair keys and adjacent ids do not correlate. */
    private static long mix(long seed, long salt) {
        long state = seed ^ salt;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }

    /** True once this room holds everything it is ever going to be asked to hold. */
    public static boolean isComplete(Set<String> placed) {
        return placed != null && placed.size() >= FULL_SET;
    }

    /** Warm every board this room wants, so it does not wait on the one-per-tick rotation. */
    public static void requestBoards() {
        LeaderboardPool.noteWanted();
        LeaderboardPool.warmAll();
    }
}
