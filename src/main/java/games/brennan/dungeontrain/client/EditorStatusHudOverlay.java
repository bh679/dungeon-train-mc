package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Client-side HUD overlay: when the player stands inside an editor plot, show
 * a top-centre bar with the current category + model name. Updated via
 * {@link games.brennan.dungeontrain.net.EditorStatusPacket} only when the
 * (category, model) pair changes, so the pipe stays quiet.
 *
 * <p>Text-only — mirrors {@link VariantHoverHudOverlay}'s no-frills rendering.
 * Mutated on the main client thread from packet handling; no synchronisation
 * needed.</p>
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.MOD,
    value = Dist.CLIENT
)
public final class EditorStatusHudOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Current status. Empty strings = hidden. Mutated on the main client thread. */
    private static String category = "";
    private static String model = "";

    /** Distance from the top of the screen in GUI pixels. */
    private static final int OFFSET_FROM_TOP = 8;
    /** Padding around the label text for the backdrop. */
    private static final int PAD = 4;

    private EditorStatusHudOverlay() {}

    /** Called from {@code EditorStatusPacket.handle} on the main client thread. */
    public static void setStatus(String newCategory, String newModel) {
        category = newCategory == null ? "" : newCategory;
        model = newModel == null ? "" : newModel;
    }

    public static void clear() {
        category = "";
        model = "";
    }

    /**
     * True when the editor status HUD currently has a status to display.
     * Other overlays ({@link VersionHudOverlay}) use this to step aside so
     * the editor bar isn't competing with the mod version string.
     */
    public static boolean isActive() {
        return !category.isEmpty() || !model.isEmpty();
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        IGuiOverlay overlay = (gui, graphics, partialTick, screenWidth, screenHeight) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) return;
            String c = category;
            String m = model;
            if (c.isEmpty() && m.isEmpty()) return;
            drawBar(graphics, mc.font, c, m, screenWidth);
        };
        event.registerAboveAll("editor_status", overlay);
        LOGGER.info("Editor status HUD overlay registered");
    }

    private static void drawBar(GuiGraphics graphics, Font font, String categoryText, String modelText, int screenWidth) {
        Component label = Component.literal("Editor: " + categoryText + " / " + modelText);
        int textWidth = font.width(label);
        int x = (screenWidth - textWidth) / 2;
        int y = OFFSET_FROM_TOP;

        // Dark translucent backdrop so the text reads against any sky colour.
        graphics.fill(x - PAD, y - PAD, x + textWidth + PAD, y + font.lineHeight + PAD, 0x80000000);
        graphics.drawString(font, label, x, y, 0xFFFFFF, true);
    }
}
