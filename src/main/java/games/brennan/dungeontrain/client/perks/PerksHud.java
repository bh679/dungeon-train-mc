package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.HudText;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * The line above the hotbar after a perk changes ("Health unlocked", "Health 3"): held while the
 * menu is open, shown for three seconds once it closes (or at once when the change arrives with
 * no menu up, such as a debris eaten with auto-open blocked by another screen).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PerksHud {

    private static final long SHOW_MS = 3000;
    private static final int BOTTOM_MARGIN = 59;
    private static final int PAD = 3;

    private static volatile Component line;
    private static volatile int colour;
    private static volatile long shownAt;

    private PerksHud() {}

    public static void show(Component text, int argb) {
        line = text;
        colour = argb | 0xFF000000;
        shownAt = Util.getMillis();
    }

    /** Restart the clock so the last change is readable once the sky is gone. */
    static void onMenuClosed() {
        if (line != null) shownAt = Util.getMillis();
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perks_hud"), (g, delta) -> {
            Component text = line;
            if (text == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui || mc.screen instanceof PerksScreen) return;
            long age = Util.getMillis() - shownAt;
            if (age > SHOW_MS) {
                line = null;
                return;
            }
            double fade = age > SHOW_MS - 500 ? (SHOW_MS - age) / 500.0 : 1.0;
            Font font = mc.font;
            int w = HudText.scaledWidth(font, text);
            int h = HudText.scaledLineHeight(font);
            int x = (g.guiWidth() - w) / 2;
            int y = g.guiHeight() - BOTTOM_MARGIN;
            int a = (int) (fade * 255);
            g.fill(x - PAD, y - PAD, x + w + PAD, y + h + PAD, (int) (a * 0.5) << 24);
            HudText.drawScaled(g, font, text, x, y, (a << 24) | (colour & 0xFFFFFF), true);
        });
    }
}
