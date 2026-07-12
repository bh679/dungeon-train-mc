package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Marks the advancements-keybind hint as "seen" the first time the player opens
 * the advancements screen. DT forces its own tab to be the default-selected one
 * (see {@link DefaultAdvancementsTab}), so opening the screen at all means the
 * player saw the Dungeon Train advancements — at which point the hint has done
 * its job and should never show again.
 *
 * <p>Hooks {@link ScreenEvent.Closing} on {@link AdvancementsScreen} (fires once
 * per screen instance, so no per-frame work) and flips the persistent
 * {@link ClientDisplayConfig} flag. Modeled on {@code StartingBookClientEvents}.</p>
 */
public final class AdvancementsScreenWatcher {

    private AdvancementsScreenWatcher() {}

    public static void onScreenClosing(net.minecraft.client.gui.screens.Screen screen) {
        if (screen instanceof AdvancementsScreen) {
            ClientDisplayConfig.setOpenedAdvancementsBefore(true);
        }
    }
}
