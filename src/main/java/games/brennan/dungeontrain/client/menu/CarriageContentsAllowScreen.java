package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.ClientContentsAllowState;
import games.brennan.dungeontrain.net.ContentsAllowRequestPacket;
import games.brennan.dungeontrain.net.ContentsAllowSyncPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Drilldown screen reached from the Editor menu's "Contents" row when the
 * player is editing a carriage template (or a portal room). Renders one {@link CommandMenuEntry.Toggle}
 * row per top-level content — green {@code [ON]} = may spawn here, red {@code [OFF]} = won't.
 * Toggling a row dispatches
 * {@code /dungeontrain editor carriage-contents <variantId> <contentsId> on|off}
 * and the server pushes a fresh {@link ContentsAllowSyncPacket}, so the next {@link #entries()}
 * rebuild reflects the new state.
 *
 * <p>The ON/OFF state is the server's <b>effective</b> answer ({@link ContentsAllowRequestPacket#build}),
 * because it depends on more than the sidecar: an existing template is on unless excluded, but a new
 * (opt-in) template is off until switched on here. Until the first answer arrives the screen shows
 * a loading row rather than guessing.</p>
 *
 * <p>Only top-level (parent) contents appear here — sub-variants (group members) are picked through
 * their parent's resolution, so toggling them individually would be meaningless.</p>
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
        return MenuLang.t("editor.contents");
    }

    @Override
    public List<CommandMenuEntry> entries() {
        ClientContentsAllowState.requestThrottled(kind(), targetId);
        return entriesFrom(ClientContentsAllowState.snapshot(kind(), targetId).orElse(null));
    }

    /**
     * The rows for one server answer ({@code null} = not arrived yet). Split out of {@link #entries()}
     * so tests can pin the rows without a connection to ask.
     */
    List<CommandMenuEntry> entriesFrom(ContentsAllowSyncPacket state) {
        List<CommandMenuEntry> out = new ArrayList<>();
        if (state == null) {
            out.add(new CommandMenuEntry.Loading(MenuLang.t("common.loading")));
            out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
            return out;
        }
        Set<String> off = Set.copyOf(state.off());
        for (String id : state.rows()) {
            out.add(new CommandMenuEntry.Toggle(
                id,
                !off.contains(id),
                commandFor(id, true),
                commandFor(id, false)
            ));
        }
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
        return out;
    }

    /** Which allow-list this screen reads — the same split its subcommand makes. */
    String kind() {
        return PORTAL_ROOM_COMMAND.equals(command)
            ? ContentsAllowRequestPacket.KIND_PORTAL_ROOM
            : ContentsAllowRequestPacket.KIND_CARRIAGE;
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
