package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The status-effect icons and the Live box both live in the top-right corner, and the box (drawn after
 * the HUD) hid every icon — the Live effect included — whenever it was up. While the box is on screen
 * the effects layer is drawn lower, {@link #GAP} under the box and its caption
 * ({@link LiveHeadViewer#hudBottom}), worked out every frame so it follows the box as it changes size. The icons move in the broadcast too, where the box never shows,
 * so the stream keeps the corner the streamer sees empty.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class HudEffectsBelowViewer {

    /** Vanilla's gap between neighbouring icons, and between the icons and the screen's edge. */
    static final int ICON_SPACING = 1;
    /** Space between the box's lowest edge and the first row of icons: double the icons' own spacing. */
    static final int GAP = 2 * ICON_SPACING;
    /** Vanilla draws the first row this far down; the shift replaces it with {@link #GAP} under the box. */
    private static final int VANILLA_TOP = 1;

    /** Whether the pose was pushed for this frame's effects layer (render thread). */
    private static boolean shifted;

    private HudEffectsBelowViewer() {}

    /** Lowest priority, so a mod that cancels the layer has done so and Post (which pops) will not be skipped. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPre(RenderGuiLayerEvent.Pre event) {
        if (event.isCanceled() || !VanillaGuiLayers.EFFECTS.equals(event.getName())) return;
        int bottom = LiveHeadViewer.hudBottom(Minecraft.getInstance());
        if (bottom <= 0) return;
        event.getGuiGraphics().pose().pushPose();
        event.getGuiGraphics().pose().translate(0, offset(bottom), 0);
        shifted = true;
    }

    /** How far the effects layer moves down so its first row starts {@link #GAP} under {@code boxBottom}. */
    static int offset(int boxBottom) {
        return boxBottom + GAP - VANILLA_TOP;
    }

    @SubscribeEvent
    public static void onPost(RenderGuiLayerEvent.Post event) {
        if (!shifted || !VanillaGuiLayers.EFFECTS.equals(event.getName())) return;
        event.getGuiGraphics().pose().popPose();
        shifted = false;
    }
}
