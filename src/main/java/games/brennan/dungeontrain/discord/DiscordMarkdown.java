package games.brennan.dungeontrain.discord;

/**
 * Escapes player-supplied text (names, item names, chat) before it is woven into a Discord post, so
 * Discord's markdown reads it literally. Without this a name like {@code ashy_} pairs its trailing
 * underscore with a later one and underlines or italicises the rest of the story. Pure string logic
 * (no Minecraft types), so it is unit-testable without a game bootstrap.
 *
 * <p>Mentions are not handled here: Discord Presence already restricts {@code allowed_mentions}, so a
 * typed {@code @everyone} or {@code <@id>} never pings.</p>
 */
public final class DiscordMarkdown {

    /** Characters Discord treats as markdown (emphasis, code, spoilers, quotes, headings, masked links). */
    private static final String SPECIALS = "\\*_~`|>#[]";

    private DiscordMarkdown() {}

    /** {@code text} with every markdown special backslash-escaped; {@code null} stays {@code null}. */
    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (SPECIALS.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
