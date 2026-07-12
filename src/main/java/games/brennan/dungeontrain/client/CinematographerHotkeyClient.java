package games.brennan.dungeontrain.client;
import games.brennan.dungeontrain.DtCore;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Client-side key binding that fires {@code /dungeontrain cinematic} when "C" is
 * pressed while the player is in cinematographer (spectator) mode.
 *
 * <p>The inner {@link CinematographerTickWatcher} class must have a unique name —
 * NeoForge silently dedupes inner @EventBusSubscriber classes that share a simple
 * name (see {@link ContainerHotkeyClient} warning).</p>
 */
public final class CinematographerHotkeyClient {

    static final KeyMapping CINEMATIC_KEY = new KeyMapping(
        "key." + DtCore.MOD_ID + ".cinematographer_cinematic",
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_C,
        VariantHotkeyClient.CATEGORY
    );

    private CinematographerHotkeyClient() {}

        public static void onRegister(java.util.function.Consumer<net.minecraft.client.KeyMapping> registrar) {
        registrar.accept(CINEMATIC_KEY);
    }

    public static final class CinematographerTickWatcher {

        private CinematographerTickWatcher() {}

        public static void onClientTick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null || mc.screen != null) return;
            if (!mc.player.isSpectator()) return;

            while (CINEMATIC_KEY.consumeClick()) {
                mc.getConnection().sendCommand("dungeontrain cinematic");
            }
        }
    }
}
