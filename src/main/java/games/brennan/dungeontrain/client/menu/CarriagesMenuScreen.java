package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Preset carriage-count choices. Range is {@code [1, 50]} per
 * {@code DungeonTrainConfig.MIN_CARRIAGES}/{@code MAX_CARRIAGES}; presets
 * chosen to cover a useful spread for iterating on train length. Each
 * choice persists to config and live-updates any active train (rolling
 * window grows or shrinks on the next tick).
 */
public final class CarriagesMenuScreen implements MenuScreen {

    @Override public String title() { return MenuLang.t("category.carriages"); }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run(MenuLang.t("carriages.count.one", 1), "dungeontrain carriages 1"),
            new CommandMenuEntry.Run(MenuLang.t("carriages.count.other", 3), "dungeontrain carriages 3"),
            new CommandMenuEntry.Run(MenuLang.t("carriages.count.other", 5), "dungeontrain carriages 5"),
            new CommandMenuEntry.Run(MenuLang.t("carriages.count.other", 10), "dungeontrain carriages 10"),
            new CommandMenuEntry.Run(MenuLang.t("carriages.count.other", 20), "dungeontrain carriages 20"),
            new CommandMenuEntry.Run(MenuLang.t("carriages.count.other", 50), "dungeontrain carriages 50"),
            new CommandMenuEntry.Back(MenuLang.t("common.back"))
        );
    }
}
