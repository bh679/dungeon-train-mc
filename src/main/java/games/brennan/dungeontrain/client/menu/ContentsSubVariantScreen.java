package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.train.CarriageContentsGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sub-variant drilldown reached from {@link CategoryTemplatesScreen} when the
 * player picks a group parent (an id with a {@code <id>.group.json} sidecar,
 * e.g. {@code maze}) in the Contents template picker. Lists the parent's
 * group members — its sub-variants — as {@link CommandMenuEntry.Run} rows that
 * dispatch the same {@code /dungeontrain editor contents enter <id>} command
 * used for a top-level contents, so one click teleports straight to that
 * sub-variant's plot.
 *
 * <p>The first row enters the <b>parent's own</b> plot ({@code "<parent> (self)"}) —
 * the parent has its own contents drawn against its {@code selfWeight}, and
 * folding its members under a drill-in would otherwise hide it. The row
 * matching the player's current model (per {@link EditorStatusHudOverlay#modelId()})
 * is highlighted so the player can see where they are.</p>
 *
 * <p>Group membership comes from {@link CarriageContentsGroupStore#get(String)}
 * — the same source the spawn-time weighted pick and the world-space plot
 * layout consult, so the menu tree matches the data hierarchy.</p>
 */
public final class ContentsSubVariantScreen implements MenuScreen {

    private final String parentId;

    public ContentsSubVariantScreen(String parentId) {
        this.parentId = parentId == null ? "" : parentId.toLowerCase(Locale.ROOT);
    }

    @Override
    public String title() {
        return parentId;
    }

    @Override
    public List<CommandMenuEntry> entries() {
        String activeId = EditorStatusHudOverlay.modelId();
        List<CommandMenuEntry> out = new ArrayList<>();
        // The parent's own contents (drawn against selfWeight) — kept reachable
        // even though its members are now nested one level down.
        out.add(new CommandMenuEntry.Run(
            parentId + " (self)",
            "dungeontrain editor contents enter " + parentId,
            parentId.equals(activeId)));
        Optional<CarriageContentsGroup> group = CarriageContentsGroupStore.get(parentId);
        if (group.isPresent()) {
            for (CarriageContentsGroup.Member m : group.get().members()) {
                out.add(new CommandMenuEntry.Run(
                    m.id(),
                    "dungeontrain editor contents enter " + m.id(),
                    m.id().equals(activeId)));
            }
        }
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    /** Visible-for-test accessor — the parent id this screen targets. */
    public String parentId() {
        return parentId;
    }
}
