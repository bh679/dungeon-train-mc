package games.brennan.dungeontrain.client.replay;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VariantHotkeyClient;
import games.brennan.dungeontrain.compat.ReplayModRecordingProbe;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * G (rebindable, Controls → Dungeon Train) flips {@link ClientDisplayConfig#isReplayFollowIntoCarriages()}
 * while a replay is playing, so a videographer can switch between "camera follows the player into
 * dimensional carriages" and "camera stays put" without leaving the shot. The choice persists —
 * it is the same setting as the Options row. Outside playback the key does nothing.
 *
 * <p>Inner subscriber class names must be unique across the mod — NeoForge silently dedupes
 * inner {@code @EventBusSubscriber} classes that share a simple name.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ReplayFollowHotkeyClient {

    static final KeyMapping TOGGLE_KEY = new KeyMapping(
        "key." + DungeonTrain.MOD_ID + ".replay_follow_toggle",
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_G,
        VariantHotkeyClient.CATEGORY
    );

    private ReplayFollowHotkeyClient() {}

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_KEY);
    }

    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class ReplayFollowTickWatcher {

        private ReplayFollowTickWatcher() {}

        /** Drain queued clicks first so presses made outside playback never bank up. */
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            boolean pressed = false;
            while (TOGGLE_KEY.consumeClick()) {
                pressed = true;
            }
            if (!pressed) return;
            if (!ReplayModRecordingProbe.isReplaying()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;

            boolean on = !ClientDisplayConfig.isReplayFollowIntoCarriages();
            ClientDisplayConfig.setReplayFollowIntoCarriages(on);
            mc.gui.setOverlayMessage(
                Component.translatable("gui." + DungeonTrain.MOD_ID + ".replay_follow." + (on ? "on" : "off")),
                false);
        }
    }
}
