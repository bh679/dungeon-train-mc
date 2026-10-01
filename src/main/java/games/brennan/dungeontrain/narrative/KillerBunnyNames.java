package games.brennan.dungeontrain.narrative;

import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * The name pool for the Killer Bunny — the Rabbit of Caerbannog from
 * <em>Monty Python and the Holy Grail</em>, its keeper Tim, and the lines
 * shouted at it.
 *
 * <p>Deliberately <strong>not localized</strong>, like {@link TechnobladePigNames}:
 * every entry is a proper noun or a fixed quote that reads the same in every
 * language, so none is routed through a lang key.</p>
 *
 * <p>Applied by {@code KillerBunnyEvents} on the slice of Killer Bunnies that win
 * the {@code killerBunnyNameChance} roll.</p>
 */
public final class KillerBunnyNames {

    /**
     * Monty Python names, in no particular order. Immutable — picked from, never
     * mutated. Add to this list to widen the pool; nothing else needs changing.
     */
    private static final List<String> NAMES = List.of(
        "Rabbit of Caerbannog",
        "Bunny of Caerbannog",
        "Grail Protector",
        "Protector Of The Grail",
        "Killer Rabbit",
        "Killer Bunny",
        "Caerbannog",
        "Tim",
        "Tim's Rabbit",
        "Tim's Bunny",
        "Tim's Pet",
        "Silly Son",
        "It Is The Rabbit",
        "No Ordinary Rabbit",
        "The Most Foul Cruel and Bad Tempered Rabbit You Ever Set Your Eyes On",
        "Killer",
        "Manky Scots Git",
        "Nibble Your Bum",
        "Bum Nibbler",
        "Rabbit Stew",
        "It's Just a Harmless Little Bunny",
        "Run Away",
        "Holy Hand Grenade Eater",
        "The Holy Hand Grenade of Antioch",
        "Brother Maynard",
        "Saint Attila",
        "Tiny Bits",
        "Snuff It",
        "Unladen Swallow",
        "Coconuts"
    );

    private KillerBunnyNames() {}

    /**
     * Picks one name uniformly at random.
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
