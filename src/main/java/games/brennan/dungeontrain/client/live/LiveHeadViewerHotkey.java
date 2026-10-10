package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VariantHotkeyClient;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * "O" flips the pinned live viewer ({@link LiveHeadViewer}) on and off, and remembers the choice in
 * {@code dungeontrain-live-client.toml}.
 *
 * <p>The inner tick class has a unique simple name — NeoForge silently dedupes inner
 * {@code @EventBusSubscriber} classes that share one (see {@code ContainerHotkeyClient}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveHeadViewerHotkey {

    static final KeyMapping KEY = new KeyMapping(
        "key." + DungeonTrain.MOD_ID + ".live_head_viewer",
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_O,
        VariantHotkeyClient.CATEGORY
    );

    private LiveHeadViewerHotkey() {}

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(KEY);
    }

    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class LiveHeadViewerTickWatcher {

        private LiveHeadViewerTickWatcher() {}

        /** Drain every queued press first, so presses made behind a screen are not banked. */
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            int presses = 0;
            while (KEY.consumeClick()) presses++;
            if (presses == 0) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;
            boolean on = LiveFeedClientConfig.toggleHeadViewer();
            mc.player.displayClientMessage(Component.translatable(
                on ? "gui.dungeontrain.live.viewer_on" : "gui.dungeontrain.live.viewer_off"), true);
        }
    }
}
