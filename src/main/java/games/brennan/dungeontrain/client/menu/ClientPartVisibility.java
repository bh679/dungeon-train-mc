package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.PartVisibilityPacket;
import games.brennan.dungeontrain.train.CarriagePartKind;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Client-side mirror of {@link games.brennan.dungeontrain.editor.EditorPartVisibility} — the set of
 * hidden part plots, fed by {@link PartVisibilityPacket}. The part-list renderer reads
 * {@link #isDisplayed} to draw the {@code ☑}/{@code ☐} state glyph on each row. A part not in the
 * hidden set is displayed (the default), so an empty/absent sync means "everything shown".
 */
public final class ClientPartVisibility {

    private static volatile Set<String> hidden = Set.of();

    private ClientPartVisibility() {}

    private static String key(CarriagePartKind kind, String name) {
        return kind.id() + ":" + name.toLowerCase(Locale.ROOT);
    }

    public static void apply(PartVisibilityPacket packet) {
        CarriagePartKind[] kinds = CarriagePartKind.values();
        Set<String> next = new HashSet<>(packet.hidden().size());
        for (PartVisibilityPacket.Entry e : packet.hidden()) {
            if (e.kindOrd() >= 0 && e.kindOrd() < kinds.length) {
                next.add(key(kinds[e.kindOrd()], e.name()));
            }
        }
        hidden = Set.copyOf(next);
    }

    /** True when the part is displayed in the editor grid (default when unknown). */
    public static boolean isDisplayed(CarriagePartKind kind, String name) {
        return !hidden.contains(key(kind, name));
    }

    public static void clear() {
        hidden = Set.of();
    }

    public static void onLoggingOut() {
        clear();
    }
}
