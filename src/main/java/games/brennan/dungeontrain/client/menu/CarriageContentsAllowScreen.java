package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
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
 * <p>Per spec: only top-level (parent) contents appear here — sub-variants
 * (group members) are picked through their parent's resolution, so toggling
 * them individually in the allow-list would be meaningless. The default for
 * a content with no record in the sidecar is "allowed" — matches the rule
 * "by default all are yes".</p>
 */
public final class CarriageContentsAllowScreen implements MenuScreen {

    /** The editor subcommand a carriage's toggles dispatch to. */
    private static final String CARRIAGE_COMMAND = "carriage-contents";

    /** The editor subcommand a portal room's toggles dispatch to. */
    private static final String PORTAL_ROOM_COMMAND = "portal-room-contents";

    private final String targetId;
    private final String command;

    public CarriageContentsAllowScreen(String variantId) {
        this(variantId, CARRIAGE_COMMAND);
    }

    private CarriageContentsAllowScreen(String targetId, String command) {
        this.targetId = targetId == null ? "" : targetId;
        this.command = command;
    }

    /** The allow-list for a carriage variant's shell. */
    public static CarriageContentsAllowScreen forCarriage(String variantId) {
        return new CarriageContentsAllowScreen(variantId, CARRIAGE_COMMAND);
    }

    /**
     * The allow-list for a portal room.
     *
     * <p>Same screen, same toggles, same excluded set off the status packet — only the subcommand
     * differs, because the two write to different sidecar directories. Reached from the plot
     * panel's Contents button, which only appears while the room's Contents setting is on: with it
     * Off there is no pool for these toggles to steer.</p>
     */
    public static CarriageContentsAllowScreen forPortalRoom(String roomName) {
        return new CarriageContentsAllowScreen(roomName, PORTAL_ROOM_COMMAND);
    }

    @Override
    public String title() {
        return "Contents";
    }

    @Override
    public List<CommandMenuEntry> entries() {
        Set<String> excluded = EditorStatusHudOverlay.excludedContents();
        // Filter out group children — only parents (or leaves) get a toggle
        // row, since picks resolve through parents at spawn time.
        Set<String> children = CarriageContentsGroupStore.allChildIds();
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        List<CommandMenuEntry> out = new ArrayList<>(all.size() + 1);
        for (CarriageContents c : all) {
            String id = c.id();
            if (children.contains(id)) continue;
            boolean allowed = !excluded.contains(id);
            out.add(new CommandMenuEntry.Toggle(
                id,
                allowed,
                commandFor(id, true),
                commandFor(id, false)
            ));
        }
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    /** The slash command one row's toggle dispatches, in the direction {@code on} names. */
    private String commandFor(String contentsId, boolean on) {
        return "dungeontrain editor " + command + " " + targetId + " " + contentsId
            + (on ? " on" : " off");
    }

    /** Visible-for-test accessor — the id this screen targets (a variant id or a room name). */
    public String variantId() {
        return targetId;
    }
}
