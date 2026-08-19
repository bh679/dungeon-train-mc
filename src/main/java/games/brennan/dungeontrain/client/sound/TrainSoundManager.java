package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
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
 * <p>Also owns the on/off half of the player's <b>Train Engine Volume</b> setting. Every volume
 * between zero and full is the sound's own business — it scales its curve in
 * {@link TrainEngineSound#tick()} — but zero has to be handled here, because a sound cannot stop
 * itself while this class exists to restart anything that stops. So a zero setting stops the
 * instance and holds the spawn, and raising the setting re-spawns on the next tick.</p>
 *
 * <p>Mirrors the {@code @EventBusSubscriber + ClientTickEvent.Post} pattern
 * used by {@link games.brennan.dungeontrain.client.ContainerHotkeyClient}
 * and {@link games.brennan.dungeontrain.client.VariantHotkeyClient}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TrainSoundManager {

    private static TrainEngineSound active;

    private TrainSoundManager() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // Sound auto-stops itself when level becomes null; clear our
            // reference so a fresh instance gets created on next world load.
            active = null;
            return;
        }
        // "Off" is not a quiet volume: release the streamed channel instead of ticking a loop
        // nobody can hear. Checked before the spawn below, which would otherwise resurrect the
        // instance we just stopped — the same respawn that covers a world reload.
        if (!TrainEngineVolume.audible(ClientDisplayConfig.getTrainEngineVolume())) {
            if (active != null) {
                mc.getSoundManager().stop(active);
                active = null;
            }
            return;
        }

        if (active == null || active.isStopped()) {
            TrainEngineSound sound = new TrainEngineSound();
            mc.getSoundManager().play(sound);
            active = sound;
        }
    }
}
