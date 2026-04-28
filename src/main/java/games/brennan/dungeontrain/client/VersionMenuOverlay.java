package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Client-only screen overlay: draws "Dungeon Train v&lt;version&gt; (&lt;branch&gt;)"
 * on the Minecraft main menu ({@link TitleScreen}), matching the in-game
 * {@link VersionHudOverlay} style (top-left, 0xFFFFFFFF, shadowed).
 *
 * <p>Uses the FORGE event bus — {@link ScreenEvent.Render.Post} fires per frame
 * after the screen draws, so we can layer on top.</p>
 */
@Mod.EventBusSubscriber(
        modid = DungeonTrain.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class VersionMenuOverlay {

    private VersionMenuOverlay() {}

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        // Release builds run on `main`; the version/branch suffix is dev-only noise there.
        if ("main".equals(VersionInfo.BRANCH)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        event.getGuiGraphics().drawString(mc.font, VersionInfo.DISPLAY, 4, 4, 0xFFFFFFFF, true);
    }
}
