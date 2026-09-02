package games.brennan.dungeontrain.client.menu.editorscreen;

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
        out.addAll(EditorMenuScreen.settingsRows(standingCategory, standingName));
        return out;
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
