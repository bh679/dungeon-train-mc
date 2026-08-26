package games.brennan.dungeontrain.narrative;

import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * The name pool for the Technoblade pig easter egg — a small tribute set of
 * proper nouns and catchphrases drawn from the late Minecraft creator whose
 * avatar was a crowned pig.
 *
 * <p>Deliberately <strong>not localized</strong>. Every entry is a proper noun
 * or a fixed catchphrase, so unlike DT's other name material (see
 * {@code AinLocaleOverlay} and {@code data/dungeontrain/ain_localizations/})
 * these read the same in every language and are not routed through a lang key.</p>
 *
 * <p>Applied by {@code TechnobladePigEvents}, which replaces the generic
 * Adventure Item Names composition on a small slice of already-named pigs.</p>
 */
public final class TechnobladePigNames {

    /**
     * Tribute names, in no particular order. Immutable — picked from, never
     * mutated. Add to this list to widen the pool; nothing else needs changing.
     */
    private static final List<String> NAMES = List.of(
        "Technoblade",
        "Technoblade Never Dies",
        "The Blood God",
        "Blood for the Blood God",
        "Techno",
        "The Potato King"
    );

    private TechnobladePigNames() {}

    /**
     * Picks one tribute name uniformly at random.
     *
     * @param rng the entity's own {@link RandomSource}; never null
     * @return a name from {@link #NAMES}, never null or empty
     */
    public static String pick(RandomSource rng) {
        return NAMES.get(rng.nextInt(NAMES.size()));
    }

    /** The pool, exposed read-only for tests and diagnostics. */
    public static List<String> names() {
        return NAMES;
    }
}
