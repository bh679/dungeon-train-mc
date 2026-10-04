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

    /** Prefix of the per-band postcard advancements: {@code enchiridion/postcard_<band>}. */
    public static final String POSTCARD_PREFIX = "postcard_";

    private EnchiridionAdvancements() {}

    /** True when {@code path} (a {@code dungeontrain:} path) is on The Enchiridion tab. */
    public static boolean isEnchiridion(String path) {
        return path != null && (path.startsWith(PATH_PREFIX) || BOOK_PATHS.contains(path));
    }

    /**
     * The postcard advancement name for forward band id {@code bandId} ({@link BandAdvancements#ALL}),
     * without the {@link #PATH_PREFIX}: {@code reached_nether} → {@code postcard_nether}. Shares
     * {@link BandAdvancements#reverseId}'s naming, so the two irregular ids (the upside-down,
     * reassembly) read the same way here.
     */
    public static String postcardName(String bandId) {
        return POSTCARD_PREFIX + BandAdvancements.reverseId(bandId).substring(BandAdvancements.REVERSE_PREFIX.length());
    }
}
