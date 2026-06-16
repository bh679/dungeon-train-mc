package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Client-side key binding that fires {@code /dungeontrain cinematic} when "C" is
 * pressed while the player is in cinematographer (spectator) mode.
 *
 * <p>The inner {@link CinematographerTickWatcher} class must have a unique name —
 * NeoForge silently dedupes inner @EventBusSubscriber classes that share a simple
 * name (see {@link ContainerHotkeyClient} warning).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CinematographerHotkeyClient {

    static final KeyMapping CINEMATIC_KEY = new KeyMapping(
        "key." + DungeonTrain.MOD_ID + ".cinematographer_cinematic",
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_C,
        VariantHotkeyClient.CATEGORY
    );

    private CinematographerHotkeyClient() {}

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(CINEMATIC_KEY);
    }

    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class CinematographerTickWatcher {

        private CinematographerTickWatcher() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null || mc.screen != null) return;
            if (!mc.player.isSpectator()) return;

            while (CINEMATIC_KEY.consumeClick()) {
                mc.getConnection().sendCommand("dungeontrain cinematic");
            }
        }
    }
}
