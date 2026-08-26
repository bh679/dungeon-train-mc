package games.brennan.dungeontrain.narrative;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The note left on a stat room's lectern, saying what the room is.
 *
 * <p>Sibling to {@link PortalLibraryTribute}, and for the same reason: a room whose shelves are all
 * one kind of book reads as a coincidence until something says otherwise. The difference is what
 * needs explaining. A library room has to name an author the shelves cannot name for themselves; a
 * stat room has to explain that the numbers on these shelves are about the reader — that this is the
 * whole tally, every board and every count the train keeps, in one place.</p>
 *
 * <p>Credited to The Tallyman, the same hand that signs the leaderboard books, rather than to The
 * Curator: nobody assembled this room out of somebody else's work. It is one thing's complete
 * output.</p>
 *
 * <p>Plain English rather than a lang key, matching {@link PortalLibraryTribute} — this is book
 * prose, which the mod keeps with its other book prose rather than in the GUI strings.</p>
 */
public final class PortalStatRoomTribute {

    /** Title on the lectern's book. */
    private static final String TITLE = "A Note on This Room";

    /** Who the note is credited to — the same hand that signs the boards on these shelves. */
    private static final String CURATOR = "The Tallyman";

    /**
     * The ten ways of saying it. Each is the whole page; unlike the library note there is no name to
     * sign underneath, because the subject of this room is whoever is reading it.
     */
    private static final List<String> VARIANTS = List.of(
        "Every book here is a count of something. Some count the whole train; some count only you.",
        "This room holds the tally entire. Take one down and it will tell you where you stand.",
        "Nothing on these shelves is a story. They are all the same story, told in figures.",
        "The boards are everyone. The notes are you. Both were counted the same way.",
        "A complete set of numbers, kept for no stated reason. You are in most of them.",
        "Somebody wanted every measure in one room. Here they are, and here you are in them.",
        "These are the ledgers. What they add up to is a question the shelves do not take on.",
        "One book per thing worth counting. The counting has not stopped while you have been reading.",
        "The whole reckoning, shelved. Some of it is about strangers and some of it is about you.",
        "Every figure the train keeps is on these shelves. It keeps more of them than you would think."
    );

    private PortalStatRoomTribute() {}

    /** How many ways the note can be written — ten. */
    public static int variantCount() {
        return VARIANTS.size();
    }

    /**
     * The note in the variant {@code seed} selects.
     *
     * <p>Seeded rather than random so a room that is re-stamped puts the same note back, the same
     * property the shelves themselves have.</p>
     */
    public static ItemStack buildStack(long seed) {
        return BookFactory.buildPlainBook(TITLE, CURATOR, List.of(variantFor(seed)));
    }

    /** The variant {@code seed} selects, spread so consecutive rooms do not read alike. */
    static String variantFor(long seed) {
        return VARIANTS.get((int) Math.floorMod(mix(seed), VARIANTS.size()));
    }

    /** Splittable-mix, so consecutive pair keys do not walk the list in order. */
    private static long mix(long seed) {
        long state = seed ^ 0x54414C4C594D414EL; // "TALLYMAN"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }
}
