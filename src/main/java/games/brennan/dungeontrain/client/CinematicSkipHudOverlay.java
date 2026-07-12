package games.brennan.dungeontrain.client;
import games.brennan.dungeontrain.DtCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Bottom-centre "Press Space to skip" prompt for the spawn intro cinematic.
 *
 * <p>Hidden until the player tries a non-Space input (matching the requested
 * behaviour: Space skips, anything else surfaces the hint). Visibility is
 * flipped from {@link CinematicInputHandler}; drawn only while the cinematic is
 * active and the GUI isn't hidden (F1).</p>
 */
public final class CinematicSkipHudOverlay {

    private static final Component LABEL = Component.translatable("gui.dungeontrain.cinematic.press_space_to_skip");
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int BACKDROP_COLOR = 0x80000000;
    private static final int PAD = 4;
    private static final int BOTTOM_MARGIN = 48;

    /** Flipped true on first non-Space input; reset when a cinematic starts/ends. */
    private static volatile boolean showPrompt = false;

    private CinematicSkipHudOverlay() {}

    public static void show() {
        showPrompt = true;
    }

    public static void reset() {
        showPrompt = false;
    }

        public static void onRegisterGuiLayers(games.brennan.dungeontrain.platform.event.DtGuiLayerRegistrar registrar) {
        registrar.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(DtCore.MOD_ID, "cinematic_skip"),
            (graphics, deltaTracker) -> {
                if (!CinematicCameraController.isActive() || !showPrompt) return;
                // No hideGui guard: the cinematic forces hideGui=true to hide the
                // rest of the HUD, but this skip prompt is the one intended UI.
                Minecraft mc = Minecraft.getInstance();
                Font font = mc.font;
                int width = HudText.scaledWidth(font, LABEL);
                int lineHeight = HudText.scaledLineHeight(font);
                int x = (graphics.guiWidth() - width) / 2;
                int y = graphics.guiHeight() - BOTTOM_MARGIN;

                graphics.fill(x - PAD, y - PAD, x + width + PAD, y + lineHeight + PAD, BACKDROP_COLOR);
                HudText.drawScaled(graphics, font, LABEL, x, y, TEXT_COLOR, true);
            });
    }
}
