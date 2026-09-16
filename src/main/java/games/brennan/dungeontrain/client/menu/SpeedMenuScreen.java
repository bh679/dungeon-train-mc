package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Preset speed choices. Range is {@code [0.0, 20.0]} per
 * {@code DungeonTrainConfig.MIN_SPEED}/{@code MAX_SPEED}; presets chosen
 * to cover a useful spread for iterating on train physics.
 */
public final class SpeedMenuScreen implements MenuScreen {

    @Override public String title() { return MenuLang.t("speed.title"); }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run(MenuLang.t("speed.stop"), "dungeontrain speed 0"),
            new CommandMenuEntry.Run(MenuLang.t("speed.slow"), "dungeontrain speed 2"),
            new CommandMenuEntry.Run(MenuLang.t("speed.medium"), "dungeontrain speed 5"),
            new CommandMenuEntry.Run(MenuLang.t("speed.fast"), "dungeontrain speed 10"),
            new CommandMenuEntry.Run(MenuLang.t("speed.max"), "dungeontrain speed 20"),
            new CommandMenuEntry.Back(MenuLang.t("common.back"))
        );
    }
}
