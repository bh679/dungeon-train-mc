package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Drilldown screen reached from the Editor menu's "Contents" row when the
 * player is editing a carriage template. Renders one {@link CommandMenuEntry.Toggle}
 * row per registered content — green {@code [ON]} = allowed in this carriage,
 * red {@code [OFF]} = excluded. Toggling a row dispatches
 * {@code /dungeontrain editor carriage-contents <variantId> <contentsId> on|off}
 * and the server pushes a fresh {@link games.brennan.dungeontrain.net.EditorStatusPacket}
 * carrying the updated excluded set, so the next {@link #entries()} rebuild
 * reflects the new state.
 *
 * <p>Per spec: every registered content always appears in the list (no
 * filtering), and the default for a content with no record in the sidecar is
 * "allowed" — matches the rule "by default all are yes".</p>
 */
public final class CarriageContentsAllowScreen implements MenuScreen {

    private final String variantId;

    public CarriageContentsAllowScreen(String variantId) {
        this.variantId = variantId == null ? "" : variantId;
    }

    @Override
    public String title() {
        return "Contents";
    }

    @Override
    public List<CommandMenuEntry> entries() {
        Set<String> excluded = EditorStatusHudOverlay.excludedContents();
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        List<CommandMenuEntry> out = new ArrayList<>(all.size() + 1);
        for (CarriageContents c : all) {
            String id = c.id();
            boolean allowed = !excluded.contains(id);
            out.add(new CommandMenuEntry.Toggle(
                id,
                allowed,
                "dungeontrain editor carriage-contents " + variantId + " " + id + " on",
                "dungeontrain editor carriage-contents " + variantId + " " + id + " off"
            ));
        }
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    /** Visible-for-test accessor — the variant id this screen targets. */
    public String variantId() {
        return variantId;
    }
}
