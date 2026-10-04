package games.brennan.dungeontrain.advancement;

import java.util.Set;

/**
 * <b>The Enchiridion</b> — the advancement tab for books and photos.
 *
 * <p>Its root is {@code dungeon_train/the_enchiridion} (earned by reading any book or taking any
 * photo). The book advancements kept their original {@code dungeon_train/} ids when they moved here —
 * so banked cross-world progress, relay overrides and hardcoded ids still line up — and only their
 * parent chain changed. Everything added for the camera lives under {@link #PATH_PREFIX}.</p>
 *
 * <p>The tab is a collection of its own, like The Secrete Menu: none of it counts towards the
 * Everything Burrito ({@link CompletionistAdvancement}), so the start-again wipe
 * ({@link StartAgainAdvancement}) leaves all of it earned.</p>
 */
public final class EnchiridionAdvancements {

    /** Path prefix of the camera advancements: {@code dungeontrain:enchiridion/…}. */
    public static final String PATH_PREFIX = "enchiridion/";

    /** The tab root (also the original book-tree root, so its id is unchanged). */
    public static final String ROOT = "dungeon_train/the_enchiridion";

    /** Every {@code dungeon_train/} advancement on this tab — the root and the book tree under it. */
    public static final Set<String> BOOK_PATHS = Set.of(
            ROOT,
            "dungeon_train/taking_notes",
            "dungeon_train/read_shared_book",
            "dungeon_train/signed_shared_book",
            "dungeon_train/submitted_death_note",
            "dungeon_train/deathnote_self_target",
            "dungeon_train/submitted_love_note",
            "dungeon_train/lovenote_self_target",
            "dungeon_train/read_all_rules",
            "dungeon_train/i_must_know",
            "dungeon_train/faulthursts_favourite",
            "dungeon_train/burned_stat_book",
            "dungeon_train/burned_leaderboard_book",
            "dungeon_train/collecting_stories",
            "dungeon_train/read_all_stories",
            "dungeon_train/nothing_but_books",
            "dungeon_train/burned_unread",
            "dungeon_train/the_same_but_different",
            "dungeon_train/welcome_back");

    /** {@code gameplay_action} ids fired by the camera hooks. */
    public static final String TOOK_PHOTO = "took_photo";
    public static final String TRIBUTED_PHOTO = "tributed_photo";
    public static final String PHOTOGRAPHED_BY_PLAYERMOB = "photographed_by_playermob";
    /** Opened a found photo (or paid it Tribute) — "Found Footage", parent of the found-photo advancements. */
    public static final String VIEWED_FOUND_PHOTO = "viewed_found_photo";

    /** Prefix of the per-dimension photo advancements: {@code enchiridion/photo_<dimension>}. */
    public static final String PHOTO_PREFIX = "photo_";

    /** The Nether's photo advancement — the first link of the dimension chain. */
    public static final String HELLISH_HOLIDAY = "hellish_holiday";

    /** The End islands' photo advancement. */
    public static final String END_CREDITS = "end_credits";

    private EnchiridionAdvancements() {}

    /** True when {@code path} (a {@code dungeontrain:} path) is on The Enchiridion tab. */
    public static boolean isEnchiridion(String path) {
        return path != null && (path.startsWith(PATH_PREFIX) || BOOK_PATHS.contains(path));
    }

    /**
     * The photo advancement name for dimension {@code bandId} ({@link BandAdvancements#ALL}), without
     * the {@link #PATH_PREFIX}. The Nether and the End islands have their own names ({@link #HELLISH_HOLIDAY},
     * {@link #END_CREDITS}); the rest are {@code photo_<dimension>}, sharing
     * {@link BandAdvancements#reverseId}'s naming for the two irregular ids (the upside-down, reassembly).
     */
    public static String photoName(String bandId) {
        if (BandAdvancements.NETHER.equals(bandId)) return HELLISH_HOLIDAY;
        if (BandAdvancements.END_ISLANDS.equals(bandId)) return END_CREDITS;
        return PHOTO_PREFIX + BandAdvancements.reverseId(bandId).substring(BandAdvancements.REVERSE_PREFIX.length());
    }
}
