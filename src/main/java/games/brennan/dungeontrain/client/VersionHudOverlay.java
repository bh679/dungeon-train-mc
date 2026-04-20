package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Client-only HUD overlay: draws "Dungeon Train v&lt;version&gt; (&lt;branch&gt;)"
 * in the top-left corner in-game. Respects F1 (hideGui); F3 debug overlay draws
 * over this, which is intentional.
 *
 * <p>The companion {@link VersionMenuOverlay} handles the main menu case.</p>
 */
@Mod.EventBusSubscriber(
        modid = DungeonTrain.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public final class VersionHudOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    private VersionHudOverlay() {}

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        IGuiOverlay overlay = (gui, graphics, partialTick, screenWidth, screenHeight) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) {
                return;
            }
            graphics.drawString(mc.font, VersionInfo.DISPLAY, 4, 4, 0xFFFFFFFF, true);
        };

        event.registerAboveAll("version_hud", overlay);
        LOGGER.info("Version HUD registered: {}", VersionInfo.DISPLAY);
    }
}
