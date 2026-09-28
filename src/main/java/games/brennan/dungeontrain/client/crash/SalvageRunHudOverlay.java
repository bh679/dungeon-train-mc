package games.brennan.dungeontrain.client.crash;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.HudText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.slf4j.Logger;

/**
 * Top-centre reminder while a salvage session is open: this world crashed, stash into the Ender
 * Chest, then start fresh. Persistent on purpose — a chat line scrolls away, and the whole point
 * of the session is that the player leaves once they've stashed rather than settling in.
 *
 * <p>Text-only, the same no-frills bar as {@code EditorStatusHudOverlay}. Reads
 * {@link CrashRunTracker#isSalvageSession()} each frame; nothing to sync.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SalvageRunHudOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String KEY_BANNER = "gui.dungeontrain.crash_recovery.hud";
    static final String KEY_BANNER_HINT = "gui.dungeontrain.crash_recovery.hud.hint";

    private static final int OFFSET_FROM_TOP = 8;
    private static final int PAD = 4;
    private static final int LINE_GAP = 2;
    private static final int BACKDROP = 0xA0000000;
    private static final int COLOR_BANNER = 0xFFF0B45A;
    private static final int COLOR_HINT = 0xFFE0E0E0;

    private SalvageRunHudOverlay() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        LayeredDraw.Layer overlay = (graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) return;
            if (!CrashRunTracker.isSalvageSession()) return;
            drawBanner(graphics, mc.font, graphics.guiWidth());
        };
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "salvage_run"), overlay);
        LOGGER.info("Salvage run HUD overlay registered");
    }

    private static void drawBanner(GuiGraphics graphics, Font font, int screenWidth) {
        Component banner = Component.translatable(KEY_BANNER);
        Component hint = Component.translatable(KEY_BANNER_HINT);
        int bannerW = HudText.scaledWidth(font, banner);
        int hintW = HudText.scaledWidth(font, hint);
        int lineH = HudText.scaledLineHeight(font);
        int boxW = Math.max(bannerW, hintW);
        int x = (screenWidth - boxW) / 2;
        int y = OFFSET_FROM_TOP;

        // Dark translucent backdrop so the text reads against any sky colour.
        graphics.fill(x - PAD, y - PAD, x + boxW + PAD, y + 2 * lineH + LINE_GAP + PAD, BACKDROP);
        HudText.drawScaled(graphics, font, banner, (screenWidth - bannerW) / 2, y, COLOR_BANNER, true);
        HudText.drawScaled(graphics, font, hint, (screenWidth - hintW) / 2, y + lineH + LINE_GAP, COLOR_HINT, true);
    }
}
