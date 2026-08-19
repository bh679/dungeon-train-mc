package games.brennan.dungeontrain.narrative;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The note left on a library room's lectern, naming the author its shelves are a tribute to.
 *
 * <h2>Why a lectern book and not a sign</h2>
 * <p>A room full of one person's writing reads as a coincidence until something says otherwise —
 * and the shelves themselves cannot say it, because a player has to open two books and notice the
 * same name on both covers. The lectern is where a library keeps its explanation, so that is where
 * the explanation goes.</p>
 *
 * <p>Twenty variants, drawn per room, so a player who finds several libraries in one run is told
 * the same fact a different way rather than reading the same sentence again. Each is deliberately
 * uncertain about <i>why</i> the room exists — the rooms are found, not built, and a note that
 * explained them would be answering a question the train never answers.</p>
 *
 * <p>Written as plain English rather than a lang key: it is book prose, which this mod keeps in the
 * same place as its other book prose (the datapack narratives) rather than in the GUI strings. The
 * chat line that greets a player entering the room IS localized — see
 * {@link PortalLibraryMessage} — so a non-English player is still told whose library it is.</p>
 */
public final class PortalLibraryTribute {

    /** Title on the lectern's book. Deliberately plain — the page is where the voice is. */
    private static final String TITLE = "A Note on This Library";

    /** Who the note is credited to. Not the author it names: somebody else assembled these shelves. */
    private static final String CURATOR = "The Curator";

    /**
     * The twenty ways of saying it. Each takes the author's name once, at the end, on its own line —
     * the shape of a signature under a note.
     */
    private static final List<String> VARIANTS = List.of(
        "This library appears to be a tribute to a single author, who made it and why is unclear.",
        "Every book on these shelves is by one hand. Nobody here remembers whose idea that was.",
        "A collection with one name in it. Whoever gathered these did not leave their own.",
        "The shelves were filled deliberately and from one source. The reason has not survived.",
        "Somebody built a room for one writer's work. They did not say why, and they did not stay.",
        "One author, all the way along. Whether this is honour or accident is not recorded.",
        "This place keeps a single body of work. The keeping seems to have mattered more than the work.",
        "Every spine here agrees on its author. Nothing here agrees on the purpose.",
        "A monument, perhaps. Or a filing error that grew comfortable. The books are all by one person.",
        "These were collected, not written here. One name runs through the whole room.",
        "The shelves hold one writer entire. What was owed to them, and by whom, is not written down.",
        "Assembled by somebody patient, from the work of somebody prolific. Only the second is named.",
        "One author fills this room. The gaps are where they stopped, not where the shelves ran out.",
        "A room arranged around a single body of work, for reasons that left with whoever arranged it.",
        "Whoever stocked these shelves cared about one writer and said nothing else about it.",
        "The catalogue here is one person deep. It is not clear whether that is devotion or convenience.",
        "This is somebody's complete works, shelved by somebody else, for nobody who is still here.",
        "One name, repeated the length of the room. The repetition is the only statement made.",
        "A library of one. It was built to be found, and beyond that it declines to explain itself.",
        "These shelves were filled from a single hand, and then abandoned to whoever came next."
    );

    private PortalLibraryTribute() {}

    /** How many ways the note can be written — twenty. */
    public static int variantCount() {
        return VARIANTS.size();
    }

    /**
     * The note for {@code author}, in the variant {@code seed} selects.
     *
     * <p>Seeded rather than random so a room that is re-stamped puts the same note back, the same
     * property the shelves themselves have.</p>
     */
    public static ItemStack buildStack(String author, long seed) {
        return BookFactory.buildPlainBook(TITLE, CURATOR, List.of(pageFor(author, seed)));
    }

    /** The page text: one variant, a blank line, and the author's name under a bullet. */
    static String pageFor(String author, long seed) {
        String name = author == null || author.isBlank() ? "an unknown hand" : author;
        return variantFor(seed) + "\n\n* " + name;
    }

    /** The variant {@code seed} selects, spread so consecutive rooms do not read alike. */
    static String variantFor(long seed) {
        return VARIANTS.get((int) Math.floorMod(mix(seed), VARIANTS.size()));
    }

    /** Splittable-mix, so consecutive pair keys do not walk the list in order. */
    private static long mix(long seed) {
        long state = seed ^ 0x54524942555445L; // "TRIBUTE"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }
}
