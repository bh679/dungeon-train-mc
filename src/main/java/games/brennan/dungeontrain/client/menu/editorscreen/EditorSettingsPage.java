package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.EditorMenuScreen;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.editor.PlotCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Settings tab: the old menu's Settings rows, plus this screen's own theme switch.
 *
 * <p>Built as rows rather than a {@code MenuScreen} because the page is drawn in the browser
 * pane directly, not hosted as a modal.</p>
 */
public final class EditorSettingsPage {

    private EditorSettingsPage() {}

    /**
     * @param standingCategory the plot the player stands in, which decides the mirror and Stages rows
     * @param theme            the current theme, drawn as the active cell of the Theme row
     * @param setTheme         what the other cell does
     */
    public static List<CommandMenuEntry> rows(PlotCategory standingCategory, String standingName,
                                              EditorScreenTheme theme, Consumer<EditorScreenTheme> setTheme) {
        List<CommandMenuEntry> out = new ArrayList<>();
        out.add(themeRow(theme, setTheme));
        if (DungeonTrain.isDevBuild()) out.add(relayRow());
        out.addAll(EditorMenuScreen.settingsRows(standingCategory, standingName));
        return out;
    }

    /**
     * Relay | Live | Dev — which relay the creator search and the profile screens talk to.
     *
     * <p>The same switch My Builds carries, and hidden on release builds for the same reason: it is
     * how whoever is reviewing player content reaches production data from a dev build. Having it
     * here means switching target without leaving the editor for the pause menu and back.</p>
     *
     * <p>Flipping it drops the cached list and forgets the viewed creator, because the two pools hold
     * different builds under different ids — a remembered creator is a row that need not exist on the
     * other side. The chips fall back to "Find creator…" and the search re-asks.</p>
     */
    static CommandMenuEntry relayRow() {
        boolean live = BuilderProfileState.live();
        return new CommandMenuEntry.Triple(
            new CommandMenuEntry.Label(EditorScreenLang.text(EditorScreenLang.RELAY)),
            relayCell(true, EditorScreenLang.RELAY_LIVE, live),
            relayCell(false, EditorScreenLang.RELAY_DEV, live),
            0.46, 0.73);
    }

    /** One cell of the Relay row: highlighted while it is the active target, a switch otherwise. */
    private static CommandMenuEntry relayCell(boolean cellLive, String labelKey, boolean live) {
        return new CommandMenuEntry.ClientAction(EditorScreenLang.text(labelKey),
            () -> setRelay(cellLive), cellLive == live);
    }

    /** Point the profile screens at the other relay, and forget what only made sense on this one. */
    static void setRelay(boolean live) {
        if (live == BuilderProfileState.live()) return;
        BuilderProfileState.setLive(live);
        BuilderProfileState.clearCache();
        BuilderProfileState.setViewed("", "");
    }

    /** Theme | Light | Dark — the same Label-plus-cells shape as the Editor Menus row. */
    static CommandMenuEntry themeRow(EditorScreenTheme theme, Consumer<EditorScreenTheme> setTheme) {
        return new CommandMenuEntry.Triple(
            new CommandMenuEntry.Label(EditorScreenLang.text(EditorScreenLang.THEME)),
            themeCell(EditorScreenTheme.LIGHT, EditorScreenLang.THEME_LIGHT, theme, setTheme),
            themeCell(EditorScreenTheme.DARK, EditorScreenLang.THEME_DARK, theme, setTheme),
            0.46, 0.73);
    }

    /** One cell of the Theme row: highlighted while it is the active theme, a client action otherwise. */
    private static CommandMenuEntry themeCell(EditorScreenTheme cell, String labelKey,
                                              EditorScreenTheme active, Consumer<EditorScreenTheme> setTheme) {
        return new CommandMenuEntry.ClientAction(EditorScreenLang.text(labelKey),
            () -> setTheme.accept(cell), cell == active);
    }
}
