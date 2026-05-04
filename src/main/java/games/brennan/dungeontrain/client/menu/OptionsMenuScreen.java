package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.config.ClientDisplayConfig;

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
            new CommandMenuEntry.Back("< Back")
        );
    }

    private static CommandMenuEntry scaleStepper(String name, double currentValue, Runnable onMinus, Runnable onPlus) {
        String label = name + ": " + String.format(Locale.ROOT, "%.1f", currentValue);
        CommandMenuEntry minus  = new CommandMenuEntry.ClientAction("-", onMinus);
        CommandMenuEntry middle = new CommandMenuEntry.ClientAction(label, () -> {});
        CommandMenuEntry plus   = new CommandMenuEntry.ClientAction("+", onPlus);
        return new CommandMenuEntry.Triple(minus, middle, plus, 0.10, 0.90);
    }
}
