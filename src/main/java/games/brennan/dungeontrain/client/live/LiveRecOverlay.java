package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * The streamer's viewfinder: while the camcorder is broadcasting, a red dot blinks in the top-right
 * corner and two white frame corners sit top-left and bottom-right, the way a camera's own screen
 * looks. Drawn only on the wearer's client, only while {@link LiveStreamController#streaming()},
 * and hidden with the rest of the HUD (F1).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveRecOverlay {

    static final int MARGIN = 14;
    static final int CORNER_LEN = 26;
    static final int LINE = 2;
    static final int DOT = 8;
    static final long BLINK_MS = 500;
    private static final int WHITE = 0xE6FFFFFF;
    private static final int RED = 0xFFE62E2E;

    private LiveRecOverlay() {}

    /** On for the first half of every second — the blink every camcorder has. */
    static boolean dotOn(long nowMs) {
        return (nowMs / BLINK_MS) % 2 == 0;
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_rec"),
            (graphics, deltaTracker) -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.options.hideGui || !LiveStreamController.get().streaming()) return;
                int w = graphics.guiWidth();
                int h = graphics.guiHeight();
                // top-left corner
                graphics.fill(MARGIN, MARGIN, MARGIN + CORNER_LEN, MARGIN + LINE, WHITE);
                graphics.fill(MARGIN, MARGIN, MARGIN + LINE, MARGIN + CORNER_LEN, WHITE);
                // bottom-right corner
                graphics.fill(w - MARGIN - CORNER_LEN, h - MARGIN - LINE, w - MARGIN, h - MARGIN, WHITE);
                graphics.fill(w - MARGIN - LINE, h - MARGIN - CORNER_LEN, w - MARGIN, h - MARGIN, WHITE);
                // blinking record dot, top-right
                if (dotOn(System.currentTimeMillis())) {
                    int x = w - MARGIN - DOT;
                    graphics.fill(x, MARGIN, x + DOT, MARGIN + DOT, RED);
                    graphics.fill(x + 1, MARGIN - 1, x + DOT - 1, MARGIN + DOT + 1, RED);
                    graphics.fill(x - 1, MARGIN + 1, x + DOT + 1, MARGIN + DOT - 1, RED);
                }
            });
    }
}
