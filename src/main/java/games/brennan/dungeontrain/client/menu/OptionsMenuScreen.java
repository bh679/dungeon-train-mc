package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.EditorMenuSpace;

import java.util.List;
import java.util.Locale;

/**
 * Drilled into from {@link MainMenuScreen} as "Options". Three display-scale
 * steppers, each tied to its own stored value so they don't cross-couple:
 *
 * <ul>
 *   <li><b>All Displays</b> — master multiplier applied on top of both
 *       channels. Bump up/down to scale every display together while
 *       preserving the per-channel offsets.</li>
 *   <li><b>Worldspace</b> — base scale for the X menu, editor menus, and
 *       debug labels.</li>
 *   <li><b>HUD</b> — base scale for the top-left version line and
 *       top-centre editor status bar.</li>
 *   <li><b>Train Volume</b> — how loud the train engine loop plays. The odd one out: not a
 *       display scale, and the third view of a setting that is also a slider on vanilla's
 *       Music &amp; Sounds screen and on the Dungeon Train options screen. A worldspace panel
 *       can't host a vanilla widget, so it appears here as a stepper — on the same
 *       {@link ClientDisplayConfig#STEP}, so the three surfaces can't disagree about a step.</li>
 * </ul>
 *
 * <p>Layout mirrors the editor weight stepper at
 * {@link EditorMenuScreen#weightTripleFor} (Triple row of
 * {@code [-] / Label (value) / [+]}, each {@code ±} a {@link
 * CommandMenuEntry.ClientAction} so the menu stays open after a click).
 * Effective on-screen size for either channel is
 * {@code channel * allScale} — the renderers fold that math in via
 * {@link ClientDisplayConfig#getWorldspaceScale()} and
 * {@link ClientDisplayConfig#getHudScale()}.</p>
 */
public final class OptionsMenuScreen implements MenuScreen {

    @Override public String title() { return "Options"; }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            scaleStepper("All Displays", ClientDisplayConfig.getAllScale(),
                () -> ClientDisplayConfig.setAllScale(ClientDisplayConfig.getAllScale() - ClientDisplayConfig.STEP),
                () -> ClientDisplayConfig.setAllScale(ClientDisplayConfig.getAllScale() + ClientDisplayConfig.STEP)),
            scaleStepper("Worldspace", ClientDisplayConfig.getWorldspaceChannel(),
                () -> ClientDisplayConfig.setWorldspaceChannel(ClientDisplayConfig.getWorldspaceChannel() - ClientDisplayConfig.STEP),
                () -> ClientDisplayConfig.setWorldspaceChannel(ClientDisplayConfig.getWorldspaceChannel() + ClientDisplayConfig.STEP)),
            scaleStepper("HUD", ClientDisplayConfig.getHudChannel(),
                () -> ClientDisplayConfig.setHudChannel(ClientDisplayConfig.getHudChannel() - ClientDisplayConfig.STEP),
                () -> ClientDisplayConfig.setHudChannel(ClientDisplayConfig.getHudChannel() + ClientDisplayConfig.STEP)),
            trainVolumeStepper(),
            snapshotChatLogRow(),
            snapshotMaxResolutionRow(),
            bookAuthorChatRow(),
            menuSpaceRow("X Menu", ClientDisplayConfig.getCommandMenuSpace(),
                ClientDisplayConfig::setCommandMenuSpace),
            menuSpaceRow("V Blocks Menu", ClientDisplayConfig.getTemplateBlocksMenuSpace(),
                ClientDisplayConfig::setTemplateBlocksMenuSpace),
            menuSpaceRow("C Contents Menu", ClientDisplayConfig.getContainerContentsMenuSpace(),
                ClientDisplayConfig::setContainerContentsMenuSpace),
            menuSpaceRow("Z Variant Menu", ClientDisplayConfig.getBlockVariantMenuSpace(),
                ClientDisplayConfig::setBlockVariantMenuSpace),
            new CommandMenuEntry.Back("< Back")
        );
    }

    /** Ceiling ladder the resolution row cycles through: 0 = AUTO (adaptive), then fixed long-edge caps. */
    private static final int[] RESOLUTION_LADDER = {0, 1080, 1440, 2160};

    /**
     * Cycles the ride-photo resolution ceiling AUTO → 1080p → 1440p → 2160p → AUTO. Like
     * {@link #snapshotChatLogRow()} it's a {@link CommandMenuEntry.ClientAction} that mutates client
     * config and stays open; the per-tick rebuild refreshes the label. AUTO keeps the adaptive
     * DH+shaders/Fabulous behaviour; a cap only ever lowers the captured resolution.
     */
    private static CommandMenuEntry snapshotMaxResolutionRow() {
        int current = ClientDisplayConfig.getRideSnapshotMaxResolution();
        String label = "Snapshot Max Resolution: " + (current <= 0 ? "AUTO" : current + "p");
        return new CommandMenuEntry.ClientAction(label,
            () -> ClientDisplayConfig.setRideSnapshotMaxResolution(nextResolution(
                ClientDisplayConfig.getRideSnapshotMaxResolution())));
    }

    /** Next rung after {@code current} in {@link #RESOLUTION_LADDER}, wrapping; an off-ladder value
     *  (hand-edited toml) advances to the first larger rung, else wraps to AUTO. */
    private static int nextResolution(int current) {
        for (int v : RESOLUTION_LADDER) {
            if (v > current) return v;
        }
        return RESOLUTION_LADDER[0];
    }

    /**
     * ON/OFF toggle for the ride-snapshot chat log. A {@link CommandMenuEntry.ClientAction}
     * (like the steppers above) so it flips purely client-side config and stays open —
     * the per-tick rebuild refreshes the {@code ON}/{@code OFF} label.
     */
    private static CommandMenuEntry snapshotChatLogRow() {
        boolean on = ClientDisplayConfig.isRideSnapshotChatLogEnabled();
        String label = "Snapshot Chat Log: " + (on ? "ON" : "OFF");
        return new CommandMenuEntry.ClientAction(label,
            () -> ClientDisplayConfig.setRideSnapshotChatLog(!ClientDisplayConfig.isRideSnapshotChatLogEnabled()));
    }

    /**
     * ON/OFF toggle for the "The book by X burns" author line. Same {@link CommandMenuEntry.ClientAction}
     * shape as {@link #snapshotChatLogRow()}; the setter also pushes the new value to the server,
     * which is what actually prints the line when a book ignites.
     */
    private static CommandMenuEntry bookAuthorChatRow() {
        boolean on = ClientDisplayConfig.isBookAuthorBurnChatEnabled();
        String label = "Book Author Chat: " + (on ? "ON" : "OFF");
        return new CommandMenuEntry.ClientAction(label,
            () -> ClientDisplayConfig.setBookAuthorBurnChat(!ClientDisplayConfig.isBookAuthorBurnChatEnabled()));
    }

    /**
     * Train engine volume as a percentage, reading {@code OFF} at zero the way the sliders on the
     * other two surfaces do. Steps the same stored value they set, by the same {@code STEP}.
     */
    private static CommandMenuEntry trainVolumeStepper() {
        double current = ClientDisplayConfig.getTrainEngineVolume();
        String value = current <= 0.0 ? "OFF" : Math.round(current * 100) + "%";
        return stepper("Train Volume: " + value,
            () -> ClientDisplayConfig.setTrainEngineVolume(
                ClientDisplayConfig.getTrainEngineVolume() - ClientDisplayConfig.STEP),
            () -> ClientDisplayConfig.setTrainEngineVolume(
                ClientDisplayConfig.getTrainEngineVolume() + ClientDisplayConfig.STEP));
    }

    /**
     * Worldspace/Screenspace toggle for one editor menu. Same
     * {@link CommandMenuEntry.ClientAction} shape as the toggles above, so it flips client-side
     * config and stays open; the per-tick rebuild refreshes the label.
     *
     * <p>Flipping the row the X menu itself is drawn by does not redraw it mid-session — the mode
     * is latched when a menu opens, so the change lands the next time it is opened. That is
     * deliberate: the two modes tear down differently, and swapping under a live panel would
     * strand it half torn down.</p>
     */
    private static CommandMenuEntry menuSpaceRow(String name, EditorMenuSpace current,
                                                 java.util.function.Consumer<EditorMenuSpace> set) {
        String label = name + ": " + (current.isScreenspace() ? "Screen" : "World");
        return new CommandMenuEntry.ClientAction(label, () -> set.accept(current.toggled()));
    }

    private static CommandMenuEntry scaleStepper(String name, double currentValue, Runnable onMinus, Runnable onPlus) {
        return stepper(name + ": " + String.format(Locale.ROOT, "%.1f", currentValue), onMinus, onPlus);
    }

    /** The shared {@code [-] / label / [+]} row: each {@code ±} a client action, so the menu stays open. */
    private static CommandMenuEntry stepper(String label, Runnable onMinus, Runnable onPlus) {
        CommandMenuEntry minus  = new CommandMenuEntry.ClientAction("-", onMinus);
        CommandMenuEntry middle = new CommandMenuEntry.ClientAction(label, () -> {});
        CommandMenuEntry plus   = new CommandMenuEntry.ClientAction("+", onPlus);
        return new CommandMenuEntry.Triple(minus, middle, plus, 0.10, 0.90);
    }
}
