package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The Advancement Points counter on the advancements screen: the player's unspent perk unlock points
 * ({@link PerkClientBalances#unlockPoints}) in a small box under the window's bottom-right corner, the
 * same on every tab. Hovering it names it. Works on vanilla's screen and Better Advancements', whose
 * window bounds {@code BetterAdvancementsScreenBoundsMixin} reports through {@link #reportWindow}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AdvancementPointsBadge {

    static final String KEY_TOOLTIP = "gui.dungeontrain.advancements.points";

    private static final int VANILLA_WINDOW_WIDTH = 252;
    private static final int VANILLA_WINDOW_HEIGHT = 140;
    private static final int PAD_X = 4;
    private static final int BOX_HEIGHT = 12;
    private static final int GAP = 2;
    private static final int BG = 0xE0101018;
    private static final int BORDER = 0xFFA0A0B0;
    private static final int TEXT = 0xFFFFD24A;

    /** Better Advancements' window, as its last frame drew it ({@code right} is 0 until reported). */
    private static int baRight;
    private static int baBottom;

    private AdvancementPointsBadge() {}

    /** Better Advancements' renderWindow reports its window here every frame. */
    public static void reportWindow(int left, int top, int right, int bottom) {
        baRight = right;
        baBottom = bottom;
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!DefaultAdvancementsTab.isAdvancementsScreen(screen)) return;
        int right;
        int bottom;
        if (screen instanceof AdvancementsScreen) {
            right = (screen.width + VANILLA_WINDOW_WIDTH) / 2;
            bottom = (screen.height + VANILLA_WINDOW_HEIGHT) / 2;
        } else {
            if (baRight <= 0) return;
            right = baRight;
            bottom = baBottom;
        }
        Font font = Minecraft.getInstance().font;
        String text = Integer.toString(PerkClientBalances.unlockPoints());
        int w = font.width(text) + PAD_X * 2;
        int x1 = right;
        int x0 = x1 - w;
        // Below the window when there is room, else tucked inside its bottom-right corner.
        int y0 = bottom + GAP + BOX_HEIGHT <= screen.height ? bottom + GAP : bottom - BOX_HEIGHT - GAP * 3;
        int y1 = y0 + BOX_HEIGHT;
        GuiGraphics g = event.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(x0, y0, x1, y1, BORDER);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, BG);
        g.drawString(font, text, x0 + PAD_X, y0 + 2, TEXT, false);
        g.pose().popPose();
        int mx = event.getMouseX();
        int my = event.getMouseY();
        if (mx >= x0 && mx < x1 && my >= y0 && my < y1) {
            g.renderTooltip(font, Component.translatable(KEY_TOOLTIP), mx, my);
        }
    }
}
