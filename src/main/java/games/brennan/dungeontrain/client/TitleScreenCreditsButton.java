package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.credits.CreditsScreen;
import games.brennan.dungeontrain.client.menu.CreditsIconButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Adds a small <b>Credits</b> icon button (a vanilla book) to the top-right
 * corner of the title screen, opening {@link CreditsScreen}.
 *
 * <p>Independent of {@link TitleScreenSupportButton} and
 * {@link TitleScreenLayoutHandler} — those reshape the central button rows and
 * the top-left version widget; the top-right corner is otherwise unused, so this
 * handler never collides with them. It no-ops on any non-{@link TitleScreen}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TitleScreenCreditsButton {

    private static final Component NARRATION = Component.translatable("gui.dungeontrain.credits.title");
    private static final Component TOOLTIP = Component.translatable("gui.dungeontrain.credits.button.tooltip");

    /** Square icon side and inset from the top-right corner (matches the top-left version widget inset). */
    private static final int SIZE = 20;
    private static final int MARGIN = 4;

    private TitleScreenCreditsButton() {}

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }
        int x = titleScreen.width - MARGIN - SIZE;
        CreditsIconButton button = new CreditsIconButton(x, MARGIN, SIZE, NARRATION,
                b -> Minecraft.getInstance().setScreen(new CreditsScreen(titleScreen)));
        button.setTooltip(Tooltip.create(TOOLTIP));
        event.addListener(button);
    }
}
