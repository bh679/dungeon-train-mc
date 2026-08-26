package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.VersionInfo;
import net.minecraft.network.chat.Component;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * The "how much work is behind this train" line on the Contribute page.
 *
 * <p>The figure is a count of <b>clock hours in which a commit landed</b>, unioned across every
 * repo the project spans (Dungeon Train, the sibling mods, the relay) so an hour spent committing
 * to two of them counts once. It is computed at build time — a committed snapshot of the sibling
 * repos' hours plus this checkout's live git history — and baked into the jar as
 * {@link VersionInfo#DEV_HOURS}. See {@code scripts/dev-hours/collect.py} and the {@code devHours}
 * closure in build.gradle.</p>
 *
 * <p>A build that could read no history at all bakes {@code 0}. That is "unknown", not "no work":
 * {@link #line()} returns empty and the page shows nothing rather than boasting about zero hours
 * or a wrong number.</p>
 */
public final class DevHours {

    /** @return the baked hour count; {@code 0} when the build could not determine one. */
    public static int hours() {
        return VersionInfo.DEV_HOURS;
    }

    /** The player-facing line for this client, or empty when there is no figure worth showing. */
    public static Optional<Component> line() {
        return line(hours(), clientLocale());
    }

    /**
     * The player-facing line for an explicit count — the whole decision, kept pure so it can be
     * tested without a baked jar or a running client.
     *
     * @param hours  de-duplicated commit-hours; anything {@code <= 0} means "unknown"
     * @param locale the locale whose digit grouping to use
     */
    public static Optional<Component> line(int hours, Locale locale) {
        if (hours <= 0) return Optional.empty();
        return Optional.of(Component.translatable(
                "gui.dungeontrain.death.narr.donate_hours", format(hours, locale)));
    }

    /** Group the count for readability ("1,394"), in whatever way {@code locale} groups digits. */
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
