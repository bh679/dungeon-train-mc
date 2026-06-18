package games.brennan.dungeontrain.util;

/**
 * Rewrites a death message into the second person for the fall-page title on the
 * narrative death screen (e.g. "Brennan fell from a high place" →
 * "You fell from a high place").
 *
 * <p>Vanilla and modded death messages always lead with the victim's display
 * name, so we swap that leading name for {@code "You"} and then fix the dominant
 * {@code "was …"} verb-agreement family ({@code "You was"} → {@code "You were"}),
 * which covers was slain/shot/blown up/killed/impaled/pricked/struck by
 * lightning/frozen/doomed, etc. A message that does not begin with the player's
 * name (an unusual modded format) is returned unchanged.</p>
 *
 * <p>Pure string logic with no Minecraft types so it is unit-testable without a
 * game bootstrap; {@code RunStatsEvents#secondPersonCause} feeds it the localized
 * message + display name pulled from the {@code DamageSource} / player.</p>
 */
public final class SecondPersonDeathMessage {

    private SecondPersonDeathMessage() {}

    private static final String YOU_WAS = "You was ";
    private static final String YOU_WERE = "You were ";

    /**
     * @param message the localized death message, e.g. {@code "Brennan was slain by Zombie"}
     * @param name    the victim's display name, e.g. {@code "Brennan"}
     * @return the second-person form (e.g. {@code "You were slain by Zombie"}); the
     *         original {@code message} if it does not start with {@code name}; or an
     *         empty string if {@code message} is null/empty.
     */
    public static String rewrite(String message, String name) {
        if (message == null || message.isEmpty()) return "";
        String out = message;
        if (name != null && !name.isEmpty() && message.startsWith(name)) {
            out = "You" + message.substring(name.length());
        }
        if (out.startsWith(YOU_WAS)) {
            out = YOU_WERE + out.substring(YOU_WAS.length());
        }
        return out;
    }
}
