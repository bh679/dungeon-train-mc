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

    /** Sentinel mirrors {@code EditorStatusPacket.NO_WEIGHT} — duplicated here to avoid coupling the client render to a server-side packet class. */
    private static final int NO_WEIGHT = -1;

    /** Current status. Empty strings = hidden. Mutated on the main client thread. */
    private static String category = "";
    private static String model = "";
    private static boolean devmode = false;
    /** Current variant weight for carriage models, or {@link #NO_WEIGHT} when not applicable. */
    private static int weight = NO_WEIGHT;

    /** Distance from the top of the screen in GUI pixels. */
    private static final int OFFSET_FROM_TOP = 8;
    /** Padding around the label text for the backdrop. */
    private static final int PAD = 4;
    /** Gap between the main status bar and the secondary weight line. */
    private static final int LINE_GAP = 2;

    private EditorStatusHudOverlay() {}

    /** Called from {@code EditorStatusPacket.handle} on the main client thread. */
    public static void setStatus(String newCategory, String newModel, boolean newDevmode, int newWeight) {
        category = newCategory == null ? "" : newCategory;
        model = newModel == null ? "" : newModel;
        devmode = newDevmode;
        weight = newWeight;
    }

    public static void clear() {
        category = "";
        model = "";
        devmode = false;
        weight = NO_WEIGHT;
    }

    /**
     * True when the editor status HUD currently has a status to display.
     * Other overlays ({@link VersionHudOverlay}) use this to step aside so
     * the editor bar isn't competing with the mod version string.
     */
    public static boolean isActive() {
        return !category.isEmpty() || !model.isEmpty();
    }

    /** Current editor category name (e.g. "carriages"), or empty string when not in an editor plot. */
    public static String category() {
        return category;
    }

    /** Current model id within the active category, or empty when not in an editor plot. */
    public static String model() {
        return model;
    }

    /** Server-reported devmode flag. False when not in an editor plot. */
    public static boolean isDevModeOn() {
        return devmode;
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        IGuiOverlay overlay = (gui, graphics, partialTick, screenWidth, screenHeight) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) return;
            String c = category;
            String m = model;
            boolean d = devmode;
            int w = weight;
            if (c.isEmpty() && m.isEmpty()) return;
            drawBar(graphics, mc.font, c, m, d, w, screenWidth);
        };
        event.registerAboveAll("editor_status", overlay);
        LOGGER.info("Editor status HUD overlay registered");
    }

    private static void drawBar(GuiGraphics graphics, Font font, String categoryText, String modelText,
                                boolean devmodeOn, int weightValue, int screenWidth) {
        Component label = Component.literal("Editor: " + categoryText + " / " + modelText);
        int textWidth = font.width(label);
        int x = (screenWidth - textWidth) / 2;
        int y = OFFSET_FROM_TOP;

        // Dark translucent backdrop so the text reads against any sky colour.
        graphics.fill(x - PAD, y - PAD, x + textWidth + PAD, y + font.lineHeight + PAD, 0x80000000);
        graphics.drawString(font, label, x, y, 0xFFFFFF, true);

        if (devmodeOn) {
            // Yellow [DEV] badge to the right of the status — visually obvious
            // that saves will also write-through to the source tree.
            Component devBadge = Component.literal("[DEV]");
            int badgeWidth = font.width(devBadge);
            int bx = x + textWidth + PAD + 4;
            graphics.fill(bx - PAD, y - PAD, bx + badgeWidth + PAD, y + font.lineHeight + PAD, 0x80000000);
            graphics.drawString(font, devBadge, bx, y, 0xFFFF55, true);
        }

        if (weightValue >= 0) {
            // Second line below the main bar — shows the variant's random-pick
            // weight (0..100). Updates live as `/dt editor weight <id> <n>`
            // runs and the server pushes a new packet.
            Component weightLine = Component.literal("weight = " + weightValue);
            int ww = font.width(weightLine);
            int wx = (screenWidth - ww) / 2;
            int wy = y + font.lineHeight + PAD + LINE_GAP;
            graphics.fill(wx - PAD, wy - PAD, wx + ww + PAD, wy + font.lineHeight + PAD, 0x80000000);
            // Weight 0 is "excluded" — tint it orange so the state is visually obvious.
            int color = weightValue == 0 ? 0xFFAA55 : 0xDDDDDD;
            graphics.drawString(font, weightLine, wx, wy, color, true);
        }
    }
}
