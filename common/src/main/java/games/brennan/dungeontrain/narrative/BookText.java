package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a paginated book-page string into a renderable {@link Component},
 * expanding inline keybind tokens of the form <code>{key.some.binding}</code>
 * into live {@link Component#keybind} components.
 *
 * <p>A keybind component resolves to each client's own bound key at <em>render</em>
 * time (e.g. <code>{key.advancements}</code> → "L" by default), even though written
 * books are authored server-side and synced to clients as NBT. This is standard
 * vanilla text-component behaviour; the {@code resolved=true} flag on
 * {@link net.minecraft.world.item.component.WrittenBookContent} only skips
 * server-side selector/score/nbt resolution, which keybinds don't use.</p>
 *
 * <p>Pages with no token take a fast path and return a plain
 * {@link Component#literal} — byte-for-byte the previous behaviour — so existing
 * books are unaffected. The token carries no spaces, so {@link BookFactory#paginate}
 * treats it as a single word and never splits it across a page boundary.</p>
 *
 * <p>Used by {@link RandomBookFactory}, {@link BookFactory} and
 * {@link StartingBookFactory} at the string→Component step, after pagination.</p>
 */
public final class BookText {

    /** Matches a {key.xxx} placeholder and captures the keybind id ("key.xxx"). */
    private static final Pattern KEYBIND_TOKEN =
        Pattern.compile("\\{(key\\.[A-Za-z0-9_.]+)\\}");

    private BookText() {}

    /**
     * Convert a single page body into a render {@link Component}, expanding any
     * {@code {key.*}} tokens into {@link Component#keybind} children. Returns a
     * plain literal when the page contains no token (and an empty literal for a
     * {@code null} page).
     */
    public static Component toPage(String page) {
        if (page == null) return Component.literal("");
        Matcher m = KEYBIND_TOKEN.matcher(page);
        if (!m.find()) return Component.literal(page); // fast path: no token

        MutableComponent out = Component.empty();
        int cursor = 0;
        do {
            if (m.start() > cursor) {
                out.append(Component.literal(page.substring(cursor, m.start())));
            }
            out.append(Component.keybind(m.group(1)));
            cursor = m.end();
        } while (m.find());
        if (cursor < page.length()) {
            out.append(Component.literal(page.substring(cursor)));
        }
        return out;
    }
}
