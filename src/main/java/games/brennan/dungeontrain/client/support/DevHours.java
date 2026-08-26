package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.VersionInfo;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * How many hours of work are behind the train — the figure the engine-room ledger shows once the
 * funding ladder has been climbed.
 *
 * <p>The unit is <b>clock hours in which a commit landed</b>, unioned across every repo the project
 * spans (Dungeon Train, the sibling mods, the relay) so an hour spent committing to two of them
 * counts once. Computed at build time — a committed snapshot of the sibling repos' hours plus this
 * checkout's live git history — and baked into the jar as {@link VersionInfo#DEV_HOURS}. See
 * {@code scripts/dev-hours/README.md}.</p>
 *
 * <p>A build that could read no history bakes {@code 0}. That is <b>unknown</b>, not "no work":
 * {@link #takesGoalSlot} then keeps the ledger in the layout it had before this tile existed,
 * rather than putting a zero in front of a would-be donor.</p>
 */
public final class DevHours {

    /** @return the baked hour count; {@code 0} when the build could not determine one. */
    public static int hours() {
        return VersionInfo.DEV_HOURS;
    }

    /**
     * Whether the hours tile takes the ledger's lead slot — the one the current funding goal
     * occupies until that goal is met.
     *
     * <p>The ledger climbs: the server bill leads until it is paid, then the next goal leads and
     * the bill drops below it, ticked off. When that goal is met too there is nothing left to ask
     * against, so the goal drops the same way and this is what leads instead — the work itself.</p>
     *
     * @param hours              the baked count; {@code 0} (unknown) declines the slot
     * @param serverCostsMet     the running-cost rung is settled and no longer the ask
     * @param activeGoalComplete the rung after it is funded too
     */
    public static boolean takesGoalSlot(int hours, boolean serverCostsMet, boolean activeGoalComplete) {
        return hours > 0 && serverCostsMet && activeGoalComplete;
    }

    /** The tile's figure for this client, grouped for the language chosen in Minecraft. */
    public static String value() {
        return value(clientLocale());
    }

    /** Group the count for readability ("1,394"), in whatever way {@code locale} groups digits. */
    public static String value(Locale locale) {
        return NumberFormat.getIntegerInstance(locale).format(hours());
    }

    /** As {@link #value(Locale)}, for an explicit count — kept pure for tests. */
    public static String format(int hours, Locale locale) {
        return NumberFormat.getIntegerInstance(locale).format(hours);
    }

    /**
     * Grouping follows the language the player chose <i>in Minecraft</i>, not the JVM default — a
     * German client on an English machine should read "1.394". Falls back to the JVM default before
     * the client is up or for a code Minecraft reports in an unexpected shape.
     */
    static Locale clientLocale() {
        return localeOf(ClientLanguage.selected());
    }

    /** {@code "de_de"} -> {@code de-DE}. Null/blank/odd input yields the JVM default. */
    static Locale localeOf(String minecraftCode) {
        if (minecraftCode == null || minecraftCode.isBlank()) return Locale.getDefault();
        String[] parts = minecraftCode.trim().split("_");
        if (parts.length == 1) return Locale.of(parts[0]);
        return Locale.of(parts[0], parts[1].toUpperCase(Locale.ROOT));
    }

    private DevHours() {}
}
