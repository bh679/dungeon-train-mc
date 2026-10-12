package games.brennan.dungeontrain.advancement;

import java.util.Set;

/**
 * <b>The Enchiridion</b> — the advancement tab for books. Photos have their own tab, The Darkroom
 * ({@code enchiridion/darkroom}), whose advancements keep this class's {@link #PATH_PREFIX}.
 *
 * <p>Its root is {@code dungeon_train/the_enchiridion} (earned by reading any book). The book advancements kept their original {@code dungeon_train/} ids when they moved here —
 * so banked cross-world progress, relay overrides and hardcoded ids still line up — and only their
 * parent chain changed. Everything added for the camera lives under {@link #PATH_PREFIX}.</p>
 *
 * <p>Both tabs are collections of their own, like The Secrete Menu: none of it counts towards the
 * Everything Burrito ({@link CompletionistAdvancement}), so the start-again wipe
 * ({@link StartAgainAdvancement}) leaves all of it earned.</p>
 */
public final class EnchiridionAdvancements {

    /** Path prefix of the camera advancements: {@code dungeontrain:enchiridion/…}. */
    public static final String PATH_PREFIX = "enchiridion/";

    /** The Darkroom: the photo tab's root, earned by taking, looking at or paying Tribute to a photo. */
    public static final String DARKROOM_ROOT = PATH_PREFIX + "darkroom";

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
            "dungeon_train/welcome_back", // switched off for now (welcome_back.json.disabled); kept so a rename re-enables it
            "dungeon_train/the_far_start");

    /** {@code gameplay_action} ids fired by the camera hooks. */
    public static final String TOOK_PHOTO = "took_photo";
    public static final String TRIBUTED_PHOTO = "tributed_photo";
    /** Paid Tribute to a photo you took yourself — your own print, or one of yours found on the train. */
    public static final String TRIBUTED_OWN_PHOTO = "tributed_own_photo";
    /** Paid Tribute to a photo someone else took. */
    public static final String TRIBUTED_OTHERS_PHOTO = "tributed_others_photo";
    public static final String PHOTOGRAPHED_BY_PLAYERMOB = "photographed_by_playermob";
    /** Opened a found photo (or paid it Tribute) — "Found Footage", parent of the found-photo advancements. */
    public static final String VIEWED_FOUND_PHOTO = "viewed_found_photo";
    /** A creature that was in a player's photo died within a second of the shot — "Final Moments". */
    public static final String PHOTO_FINAL_MOMENTS = "photo_final_moments";
    /** Someone else's album, found on the train, is in your inventory — "Memory Lane". */
    public static final String FOUND_ALBUM = "found_album";
    /** Your album was saved holding at least one photo — "Keepsake". */
    public static final String ALBUM_PHOTO = "album_photo";
    /** Your album was saved with a photo on every page — "No Room Left". */
    public static final String ALBUM_FULL = "album_full";

    /** Prefix of the per-dimension photo advancements: {@code enchiridion/photo_<dimension>}. */
    public static final String PHOTO_PREFIX = "photo_";

    /** The Nether's photo advancement — the first link of the dimension chain. */
    public static final String HELLISH_HOLIDAY = "hellish_holiday";

    /** The End islands' photo advancement. */
    public static final String END_CREDITS = "end_credits";

    /** The biome tiers share one album of photos, one per biome, kept under the first tier. */
    public static final String BIOME_ALBUM = PATH_PREFIX + "scenic_route";

    private static final Set<String> BIOME_TIERS = Set.of(
            PATH_PREFIX + "scenic_route", PATH_PREFIX + "travel_brochure", PATH_PREFIX + "coffee_table_book");

    /**
     * How many biomes each photo tier asks for — the album shows that many slots. Kept here because a
     * client never receives criterion conditions; a test pins these to the JSON. Coffee Table Book
     * (absent) asks for every biome the game has.
     */
    public static final java.util.Map<String, Integer> BIOME_TIER_TARGETS = java.util.Map.of(
            PATH_PREFIX + "scenic_route", 30, PATH_PREFIX + "travel_brochure", 60);

    /** Collections that keep a photo for every entry, not just the one that completed them. */
    private static final Set<String> ENTITY_ALBUMS = Set.of(
            PATH_PREFIX + "nature_documentary", PATH_PREFIX + "most_wanted");

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

    /**
     * The album {@code path}'s photos are kept in — a photo per entry, logged as the collection fills —
     * or {@code null} for an advancement with a single photo. The biome tiers share {@link #BIOME_ALBUM}.
     */
    public static String albumOf(String path) {
        if (path == null) return null;
        if (BIOME_TIERS.contains(path)) return BIOME_ALBUM;
        return ENTITY_ALBUMS.contains(path) ? path : null;
    }

    /** True when {@code album}'s entries are biome ids; otherwise they are entity type ids. */
    public static boolean isBiomeAlbum(String album) {
        return BIOME_ALBUM.equals(album);
    }
}
