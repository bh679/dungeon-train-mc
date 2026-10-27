package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.perks.PerksScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * A Perks button on the advancements screen, on the Advancement Points badge's row just left of it
 * ({@link AdvancementPointsBadge#row}), opening {@link PerksScreen}. Re-placed every frame, since
 * Better Advancements only reports its window while drawing; hidden until it has.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AdvancementPerksButton {

    private static final Component LABEL = Component.translatable("gui.dungeontrain.perks.title");
    private static final int PAD_X = 6;

    private static final Map<Screen, Button> BUTTONS = new WeakHashMap<>();

    private AdvancementPerksButton() {}

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!DefaultAdvancementsTab.isAdvancementsScreen(screen)) return;
        int w = Minecraft.getInstance().font.width(LABEL) + PAD_X * 2;
        Button button = new DarkTintedButton(0, 0, w, AdvancementPointsBadge.BOX_HEIGHT, LABEL,
                b -> PerksScreen.open(false));
        button.visible = false;
        event.addListener(button);
        BUTTONS.put(screen, button);
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        Screen screen = event.getScreen();
        Button button = BUTTONS.get(screen);
        if (button == null) return;
        AdvancementPointsBadge.Row row = AdvancementPointsBadge.row(screen);
        button.visible = row != null;
        if (row == null) return;
        int badgeLeft = row.right() - AdvancementPointsBadge.width(Minecraft.getInstance().font);
        button.setPosition(badgeLeft - AdvancementPointsBadge.GAP - button.getWidth(), row.top());
    }
}
