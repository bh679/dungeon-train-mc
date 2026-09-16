package games.brennan.dungeontrain.client.menu;

import java.util.List;

/** Spawn a train / drill into the speed or carriage-count presets. */
public final class TrainMenuScreen implements MenuScreen {

    @Override public String title() { return MenuLang.t("main.train"); }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run(MenuLang.t("train.spawn"), "dungeontrain spawn"),
            new CommandMenuEntry.DrillIn(MenuLang.t("category.carriages"), new CarriagesMenuScreen()),
            new CommandMenuEntry.DrillIn(MenuLang.t("speed.title"), new SpeedMenuScreen()),
            new CommandMenuEntry.Back(MenuLang.t("common.back"))
        );
    }
}
