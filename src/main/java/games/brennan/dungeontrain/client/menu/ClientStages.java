package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client-side read model for the global Stage list. The server ships every Stage to the client as
 * the rows of the {@code isStagesMenu} {@link EditorTypeMenusPacket.Menu} inside the floating
 * type-menu snapshot ({@link EditorTypeMenuRenderer#menus()}), so the keyboard Stages window, the
 * Stage edit screen, and the "Stage / Custom" picker all read their list from that cache — no extra
 * sync packet needed. Each row's {@code modelId} is the stage id, {@code name} the display name, and
 * the gate fields its current band/dimensions (so edit-screen labels can show live values).
 */
public final class ClientStages {

    /** One Stage as seen by the client: id + name + its gate (band + 4-bit dimension mask). */
    public record Info(String id, String name, int minLevel, int maxLevel, int phaseMask) {}

    private ClientStages() {}

    /** Every Stage, in the server's id-sorted order. Empty when no stages exist or the editor is closed. */
    public static List<Info> all() {
        for (EditorTypeMenusPacket.Menu m : EditorTypeMenuRenderer.menus()) {
            if (!m.isStagesMenu()) continue;
            List<Info> out = new ArrayList<>(m.variants().size());
            for (EditorTypeMenusPacket.Variant v : m.variants()) {
                out.add(new Info(v.modelId(), v.name(), v.minLevel(), v.maxLevel(), v.phaseMask()));
            }
            return out;
        }
        return List.of();
    }

    /** The Stage with this id, or {@code null}. */
    public static Info byId(String id) {
        if (id == null) return null;
        String key = id.toLowerCase(Locale.ROOT);
        for (Info i : all()) {
            if (i.id().equals(key)) return i;
        }
        return null;
    }

    /** True when no stages exist (the picker then offers only "Custom"). */
    public static boolean isEmpty() {
        return all().isEmpty();
    }

    /** Human-readable gate summary for a Stage row, e.g. {@code "lvl 10..all · N"}. */
    public static String gateSummary(Info s) {
        String max = s.maxLevel() < 0 ? "all" : Integer.toString(s.maxLevel());
        return "lvl " + s.minLevel() + ".." + max + " · " + dims(s.phaseMask());
    }

    /** Compact dimension letters for a 4-bit mask (OVERWORLD/NETHER/VOID/END); "all" when every bit set. */
    public static String dims(int mask) {
        if ((mask & 0b1111) == 0b1111) return "all";
        String[] letters = {"O", "N", "V", "E"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < letters.length; i++) {
            if ((mask & (1 << i)) != 0) sb.append(letters[i]);
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }
}
