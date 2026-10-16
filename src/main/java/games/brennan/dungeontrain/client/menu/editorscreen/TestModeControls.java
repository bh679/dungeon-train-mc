package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.PortalTestSessionState;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.number.NumberField;
import games.brennan.dungeontrain.command.TestSettingsCommand;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.List;
import java.util.Locale;

/**
 * What the detail pane shows while a test is running: a toolbar of its own above the model, and
 * the test settings rows where the data sheet usually sits.
 *
 * <p>Everything here changes how the test copy stands up and nothing about the template — the
 * editing toolbar above it is still the place for that. Every control ends in a server command,
 * so the X menu and chat can reach the same things.</p>
 */
public final class TestModeControls {

    public static final String RELOAD = "test_reload";
    public static final String REROLL_OTHERS = "test_reroll_others";
    public static final String REROLL_MAIN = "test_reroll_main";
    public static final String RESEED_ALL = "test_reseed_all";

    static final String RELOAD_COMMAND = "dungeontrain portal test reload";
    static final String REROLL_OTHERS_COMMAND = "dungeontrain portal test reroll others";
    static final String SET_COMMAND = "dungeontrain portal test set ";
    static final String AUTO = "auto";

    /** Where each row's arrows split from its value: a narrow cell either side. */
    private static final double ARROW_LEFT = 0.14;
    private static final double ARROW_RIGHT = 0.86;

    private TestModeControls() {}

    /** The test toolbar, left to right: the two reloads, then the two re-rolls. */
    public static List<EditorScreenActions.Icon> icons() {
        return List.of(
            icon(RELOAD, EditorScreenLang.TEST_RELOAD, EditorScreenLang.TEST_RELOAD_TIP, RELOAD_COMMAND),
            icon(REROLL_OTHERS, EditorScreenLang.TEST_REROLL_OTHERS, EditorScreenLang.TEST_REROLL_OTHERS_TIP,
                REROLL_OTHERS_COMMAND),
            icon(REROLL_MAIN, EditorScreenLang.TEST_REROLL_MAIN, EditorScreenLang.TEST_REROLL_MAIN_TIP,
                EditorScreenActions.RESEED_FOCUS_COMMAND),
            icon(RESEED_ALL, EditorScreenLang.TEST_RESEED_ALL, EditorScreenLang.TEST_RESEED_ALL_TIP,
                EditorScreenActions.RESEED_NOW_COMMAND));
    }

    private static EditorScreenActions.Icon icon(String id, String labelKey, String tipKey, String command) {
        return new EditorScreenActions.Icon(id, labelKey,
            new CommandMenuEntry.Run(EditorScreenLang.text(labelKey), command), null,
            EditorScreenLang.text(tipKey));
    }

    /** The settings rows: Stage, Diff level, Band — each with arrows either side of its value. */
    public static List<CommandMenuEntry> rows() {
        return List.of(stageRow(), levelRow(), bandRow());
    }

    private static CommandMenuEntry stageRow() {
        List<String> ids = PortalTestSessionState.stageIds();
        String current = PortalTestSessionState.stage();
        String label = EditorScreenLang.text(EditorScreenLang.TEST_STAGE, stageName(current));
        return arrows(label,
            SET_COMMAND + "stage " + orAuto(cycle(ids, current, -1)),
            SET_COMMAND + "stage " + orAuto(cycle(ids, current, +1)));
    }

    private static CommandMenuEntry levelRow() {
        int picked = PortalTestSessionState.level();
        int shown = picked >= 0 ? picked : PortalTestSessionState.autoLevel();
        String value = picked >= 0 ? Integer.toString(picked)
            : EditorScreenLang.text(EditorScreenLang.TEST_AUTO_VALUE, shown);
        String label = EditorScreenLang.text(EditorScreenLang.TEST_LEVEL, value);
        String title = EditorScreenLang.text(EditorScreenLang.TEST_LEVEL_TITLE);
        Runnable dec = () -> CommandRunner.run(SET_COMMAND + "level " + Math.max(0, shown - 1));
        Runnable inc = () -> CommandRunner.run(SET_COMMAND + "level "
            + Math.min(TestSettingsCommand.MAX_LEVEL, shown + 1));
        // Typed blank is Auto, as a blank max is "no limit" on every other editor number.
        NumberField field = new NumberField(title, picked >= 0 ? picked : NumberField.ALL, 0,
            TestSettingsCommand.MAX_LEVEL, /*allowAll*/ true, inc, dec,
            v -> CommandRunner.run(SET_COMMAND + "level " + (v < 0 ? AUTO : Integer.toString(v))));
        return new CommandMenuEntry.Triple(
            new CommandMenuEntry.ClientAction("◀", dec),
            new CommandMenuEntry.ClientNumber(label, field),
            new CommandMenuEntry.ClientAction("▶", inc),
            ARROW_LEFT, ARROW_RIGHT);
    }

    private static CommandMenuEntry bandRow() {
        int picked = PortalTestSessionState.phase();
        TrainPhase[] all = TrainPhase.values();
        TrainPhase current = picked >= 0 && picked < all.length ? all[picked] : null;
        String label = EditorScreenLang.text(EditorScreenLang.TEST_BAND, bandName(current));
        List<String> names = java.util.Arrays.stream(all).map(TestSettingsCommand::bandArg).toList();
        String now = current == null ? "" : TestSettingsCommand.bandArg(current);
        return arrows(label,
            SET_COMMAND + "band " + orAuto(cycle(names, now, -1)),
            SET_COMMAND + "band " + orAuto(cycle(names, now, +1)));
    }

    /** A row whose value steps back with the left arrow and on with the value or the right arrow. */
    private static CommandMenuEntry arrows(String label, String back, String on) {
        return new CommandMenuEntry.Triple(
            new CommandMenuEntry.Run("◀", back),
            new CommandMenuEntry.Run(label, on),
            new CommandMenuEntry.Run("▶", on),
            ARROW_LEFT, ARROW_RIGHT);
    }

    /**
     * The value {@code dir} steps from {@code current} to, through Auto ("") at the ends: Auto, the
     * first, …, the last, Auto again. A value no longer on the list steps from Auto.
     */
    static String cycle(List<String> values, String current, int dir) {
        if (values.isEmpty()) return "";
        int at = current == null || current.isEmpty() ? -1 : values.indexOf(current);
        // Auto sits at index -1 of a ring one longer than the list.
        int ring = values.size() + 1;
        int next = Math.floorMod(at + 1 + dir, ring) - 1;
        return next < 0 ? "" : values.get(next);
    }

    private static String orAuto(String value) {
        return value == null || value.isEmpty() ? AUTO : value;
    }

    private static String stageName(String id) {
        if (id == null || id.isEmpty()) return EditorScreenLang.text(EditorScreenLang.TEST_AUTO);
        int i = PortalTestSessionState.stageIds().indexOf(id);
        List<String> names = PortalTestSessionState.stageNames();
        return i >= 0 && i < names.size() ? names.get(i) : id;
    }

    private static String bandName(TrainPhase phase) {
        if (phase == null) return EditorScreenLang.text(EditorScreenLang.TEST_AUTO);
        return EditorScreenLang.text("gui.dungeontrain.editor_menu.phase." + phase.name().toLowerCase(Locale.ROOT));
    }
}
