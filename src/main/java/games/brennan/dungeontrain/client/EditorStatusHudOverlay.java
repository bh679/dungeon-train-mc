package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Set;

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
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorStatusHudOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Sentinel mirrors {@code EditorStatusPacket.NO_WEIGHT} — duplicated here to avoid coupling the client render to a server-side packet class. */
    private static final int NO_WEIGHT = -1;

    /** Current status. Empty strings = hidden. Mutated on the main client thread. */
    private static String category = "";
    private static String model = "";
    /** Command-token id for the current model — what the menu hands to {@code /dt editor ...}. May differ from {@link #model} (HUD path string) for track-side models. */
    private static String modelId = "";
    /**
     * Bare variant-name segment of the current model (e.g. {@code track2},
     * {@code stone}, {@code default}). For carriages and contents this equals
     * {@link #modelId}; for track/pillar/tunnel models it's the trailing name
     * the menu splices into {@code /dt editor tracks weight <kind> <name> ...}.
     */
    private static String modelName = "";
    private static boolean devmode = false;
    /** Current variant weight for the active model, or {@link #NO_WEIGHT} when not applicable. */
    private static int weight = NO_WEIGHT;
    /** Server-reported per-player part-position auto-open menu flag. Defaults true for fresh sessions. */
    private static boolean partMenuEnabled = true;
    /**
     * Content ids the active carriage variant has explicitly disallowed. Empty
     * for non-carriage statuses and for carriages with no exclusions. The
     * Contents drilldown screen reads this for per-row red/green toggle state.
     */
    private static Set<String> excludedContents = Collections.emptySet();

    /** Distance from the top of the screen in GUI pixels. */
    private static final int OFFSET_FROM_TOP = 8;
    /** Padding around the label text for the backdrop. */
    private static final int PAD = 4;
    /** Gap between the main status bar and the secondary weight line. */
    private static final int LINE_GAP = 2;

    private EditorStatusHudOverlay() {}

    /** Called from {@code EditorStatusPacket.handle} on the main client thread. */
    public static void setStatus(String newCategory, String newModel, String newModelId, String newModelName,
                                 boolean newDevmode, int newWeight, boolean newPartMenuEnabled,
                                 Set<String> newExcludedContents) {
        category = newCategory == null ? "" : newCategory;
        model = newModel == null ? "" : newModel;
        modelId = newModelId == null ? "" : newModelId;
        modelName = newModelName == null ? "" : newModelName;
        devmode = newDevmode;
        weight = newWeight;
        partMenuEnabled = newPartMenuEnabled;
        excludedContents = (newExcludedContents == null || newExcludedContents.isEmpty())
            ? Collections.emptySet()
            : Set.copyOf(newExcludedContents);
    }

    public static void clear() {
        category = "";
        model = "";
        modelId = "";
        modelName = "";
        devmode = false;
        weight = NO_WEIGHT;
        partMenuEnabled = true;
        excludedContents = Collections.emptySet();
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

    /** Friendly path-style display string for the active model (e.g. {@code track / track2}). Empty when not in an editor plot. Use {@link #modelId()} for command construction. */
    public static String model() {
        return model;
    }

    /** Command-token id for the active model (e.g. {@code track}, {@code pillar_bottom}, {@code standard}). Empty when not in an editor plot. */
    public static String modelId() {
        return modelId;
    }

    /** Bare variant-name segment of the active model (e.g. {@code track2}, {@code stone}, {@code default}). Empty when not in an editor plot. */
    public static String modelName() {
        return modelName;
    }

    /** Server-reported devmode flag. False when not in an editor plot. */
    public static boolean isDevModeOn() {
        return devmode;
    }

    /** Current variant weight for the active carriage model, or -1 when not applicable. */
    public static int weight() {
        return weight;
    }

    /** Server-reported per-player part-position auto-open menu flag. Default true. */
    public static boolean isPartMenuEnabled() {
        return partMenuEnabled;
    }

    /**
     * Content ids the active carriage variant currently has disallowed.
     * Empty when not on a carriage variant or when none are excluded.
     */
    public static Set<String> excludedContents() {
        return excludedContents;
    }

    /**
     * Wipe the HUD's static state when the player disconnects from a world
     * (single-player exit-to-menu fires this too). Without this, the next
     * world inherits the previous session's "Editor: …" text plus any
     * {@code [DEV]} badge until a fresh {@link games.brennan.dungeontrain.net.EditorStatusPacket}
     * arrives — which only happens once the player steps onto an editor plot.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        LayeredDraw.Layer overlay = (graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) return;
            String c = category;
            String m = model;
            boolean d = devmode;
            int w = weight;
            if (c.isEmpty() && m.isEmpty()) return;
            drawBar(graphics, mc.font, c, m, d, w, graphics.guiWidth());
        };
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_status"), overlay);
        LOGGER.info("Editor status HUD overlay registered");
    }

    private static void drawBar(GuiGraphics graphics, Font font, String categoryText, String modelText,
                                boolean devmodeOn, int weightValue, int screenWidth) {
        Component label = Component.literal("Editor: " + categoryText + " / " + modelText);
        int textWidth = HudText.scaledWidth(font, label);
        int lineHeight = HudText.scaledLineHeight(font);
        int x = (screenWidth - textWidth) / 2;
        int y = OFFSET_FROM_TOP;

        // Dark translucent backdrop so the text reads against any sky colour.
        graphics.fill(x - PAD, y - PAD, x + textWidth + PAD, y + lineHeight + PAD, 0x80000000);
        HudText.drawScaled(graphics, font, label, x, y, 0xFFFFFF, true);

        if (devmodeOn) {
            // Yellow [DEV] badge to the right of the status — visually obvious
            // that saves will also write-through to the source tree.
            Component devBadge = Component.literal("[DEV]");
            int badgeWidth = HudText.scaledWidth(font, devBadge);
            int bx = x + textWidth + PAD + 4;
            graphics.fill(bx - PAD, y - PAD, bx + badgeWidth + PAD, y + lineHeight + PAD, 0x80000000);
            HudText.drawScaled(graphics, font, devBadge, bx, y, 0xFFFF55, true);
        }

        if (weightValue >= 0) {
            // Second line below the main bar — shows the variant's random-pick
            // weight (0..100). Updates live as `/dt editor weight <id> <n>`
            // runs and the server pushes a new packet.
            Component weightLine = Component.literal("weight = " + weightValue);
            int ww = HudText.scaledWidth(font, weightLine);
            int wx = (screenWidth - ww) / 2;
            int wy = y + lineHeight + PAD + LINE_GAP;
            graphics.fill(wx - PAD, wy - PAD, wx + ww + PAD, wy + lineHeight + PAD, 0x80000000);
            // Weight 0 is "excluded" — tint it orange so the state is visually obvious.
            int color = weightValue == 0 ? 0xFFAA55 : 0xDDDDDD;
            HudText.drawScaled(graphics, font, weightLine, wx, wy, color, true);
        }
    }
}
