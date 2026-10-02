package games.brennan.dungeontrain.echo;

import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;

/**
 * Words the credit line an echo leaves in the description of an item it drops or gives — "Once
 * wielded by the echo of Steve", "Pried from the hands of something that looked like Steve".
 *
 * <p>The opening phrase depends on how the item left the echo ({@link Cause}): Plain and Heroic
 * phrases can always appear, and each cause adds its own group — Grim when a player killed the echo,
 * Wistful when anything else did, Gift for a gift. That situational group gets half the rolls; a
 * gear swap has none and draws only from Plain + Heroic. The name form is then picked uniformly.</p>
 *
 * <p>Pure — no Minecraft types — so it is unit-tested directly. Randomness comes in as a
 * {@code bound -> [0, bound)} function so production passes {@code RandomSource::nextInt}.</p>
 */
public final class EchoCreditText {

    /** How the item left the echo; picks the situational phrase group. */
    public enum Cause {
        /** Equipped gear dropped when a player killed the echo. */
        KILLED_BY_PLAYER,
        /** Equipped gear dropped when the echo died to anything else. */
        DIED,
        /** A piece the echo was wearing or holding, dropped as it swapped to better gear. */
        SWAP,
        /** An item the echo tossed to someone as a gift. */
        GIFT
    }

    static final List<String> PLAIN = List.of(
            "Carried by", "Dropped by", "Owned by", "Last held by");
    static final List<String> HEROIC = List.of(
            "Once wielded by", "Borne by", "Taken into battle by", "Trusted by");
    static final List<String> GRIM = List.of(
            "Pried from the hands of", "Taken from", "Left behind by", "Recovered from", "Torn from");
    static final List<String> WISTFUL = List.of(
            "Remembered by", "Still warm from", "Never forgotten by", "Lost twice by");
    static final List<String> GIFT = List.of(
            "Given by", "A gift from", "Handed over by", "Shared by", "Offered by", "With thanks from");

    /** Name forms; {@code %s} is the player's name. */
    static final List<String> NAME_FORMS = List.of(
            "the echo of %s", "%s's echo", "%s", "the ghost of %s", "something that looked like %s");

    /** Always-available phrases (Plain + Heroic). */
    static final List<String> ALWAYS = Stream.concat(PLAIN.stream(), HEROIC.stream()).toList();

    private EchoCreditText() {}

    /** One credit line for {@code name}, worded for {@code cause}. */
    public static String compose(Cause cause, String name, IntUnaryOperator nextInt) {
        String phrase = pick(phrasePool(cause, nextInt), nextInt);
        String form = pick(NAME_FORMS, nextInt);
        return phrase + " " + form.replace("%s", name);
    }

    /**
     * The pool this roll draws its opening phrase from: the cause's own group half the time, else
     * Plain + Heroic. Causes without a group (a swap) always draw Plain + Heroic.
     */
    static List<String> phrasePool(Cause cause, IntUnaryOperator nextInt) {
        List<String> situational = situational(cause);
        if (situational.isEmpty() || nextInt.applyAsInt(2) == 0) {
            return ALWAYS;
        }
        return situational;
    }

    /** The cause's own phrase group; empty when it has none. */
    static List<String> situational(Cause cause) {
        return switch (cause) {
            case KILLED_BY_PLAYER -> GRIM;
            case DIED -> WISTFUL;
            case GIFT -> GIFT;
            case SWAP -> List.of();
        };
    }

    private static String pick(List<String> options, IntUnaryOperator nextInt) {
        return options.get(nextInt.applyAsInt(options.size()));
    }
}
