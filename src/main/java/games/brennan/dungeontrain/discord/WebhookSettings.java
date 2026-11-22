package games.brennan.dungeontrain.discord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The per-link settings a player keeps next to their webhook links on their own computer: a name and a
 * feed set, each stored as {@code "<webhook id>=<value>"} lines. Keyed by the webhook's numeric id (it is
 * not the secret part of the link), so the settings follow a link whatever order the links are in.
 *
 * <p>No Minecraft types, so the parsing and sorting are unit tested.</p>
 */
public final class WebhookSettings {

    /** Longest name kept. */
    public static final int MAX_NAME_LENGTH = 32;

    private WebhookSettings() {}

    /** {@code "id=value"} lines as a map; later lines win; lines without an id are dropped. */
    public static Map<String, String> parse(Collection<? extends String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        if (lines == null) return out;
        for (String line : lines) {
            if (line == null) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String id = line.substring(0, eq).strip();
            if (!isId(id)) continue;
            out.put(id, line.substring(eq + 1));
        }
        return out;
    }

    /** The map as {@code "id=value"} lines, kept only for {@code ids} (the links still saved). */
    public static List<String> encode(Map<String, String> values, Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (ids.contains(e.getKey())) out.add(e.getKey() + "=" + e.getValue());
        }
        return List.copyOf(out);
    }

    /** Whether {@code s} looks like a webhook id. */
    public static boolean isId(String s) {
        if (s == null || s.isEmpty() || s.length() > 25) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** {@code raw} as a name worth keeping: trimmed, control characters out, capped. {@code ""} for none. */
    public static String cleanName(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        raw.strip().codePoints().filter(c -> !Character.isISOControl(c)).forEach(b::appendCodePoint);
        String name = b.toString().strip();
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH).strip();
    }

    /** The feed mask stored for {@code id}; {@link WebhookFeed#LEGACY} for a link saved before feeds existed. */
    public static int feedsFor(Map<String, String> feeds, String id) {
        String stored = feeds.get(id);
        return stored == null ? WebhookFeed.LEGACY : WebhookFeed.decode(stored);
    }

    /**
     * {@code urls} in the order the screen lists them: named links by name (case-insensitive), then the
     * unnamed ones in the order they were added.
     */
    public static List<String> displayOrder(List<String> urls, Function<String, String> nameOf) {
        List<String> named = new ArrayList<>();
        List<String> unnamed = new ArrayList<>();
        for (String url : urls) (nameOf.apply(url).isEmpty() ? unnamed : named).add(url);
        named.sort(Comparator.comparing((String u) -> nameOf.apply(u).toLowerCase(Locale.ROOT)));
        named.addAll(unnamed);
        return List.copyOf(named);
    }
}
