package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Owns the lifecycle of the single {@link TrainEngineSound} instance:
 * spawns one as soon as a client level is loaded, lets it run while the
 * world is connected, and releases the reference once the sound stops itself
 * (server disconnect, world unload) so the next world-load gets a fresh
 * instance.
 *
 * <p>Mirrors the {@code @EventBusSubscriber + ClientTickEvent.Post} pattern
 * used by {@link games.brennan.dungeontrain.client.ContainerHotkeyClient}
 * and {@link games.brennan.dungeontrain.client.VariantHotkeyClient}.</p>
 */
public final class TrainSoundManager {

    private static TrainEngineSound active;

    private TrainSoundManager() {}

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // Sound auto-stops itself when level becomes null; clear our
            // reference so a fresh instance gets created on next world load.
            active = null;
            return;
        }
        if (active == null || active.isStopped()) {
            TrainEngineSound sound = new TrainEngineSound();
            mc.getSoundManager().play(sound);
            active = sound;
        }
    }
}
