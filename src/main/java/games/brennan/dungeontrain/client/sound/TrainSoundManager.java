package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.live.audio.AudioMirror;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
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
 * <p>Master and Ambient at zero are held the same way, and an instance the sound engine has quietly
 * dropped is replaced — see {@link TrainEngineLifecycle} for why the instance's own
 * {@code isStopped()} cannot be trusted after a mute. A respawn within the same world inherits the
 * fade-in clock, so unmuting resumes the engine at its normal volume rather than a fresh ramp.</p>
 *
 * <p>Mirrors the {@code @EventBusSubscriber + ClientTickEvent.Post} pattern
 * used by {@link games.brennan.dungeontrain.client.ContainerHotkeyClient}
 * and {@link games.brennan.dungeontrain.client.VariantHotkeyClient}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TrainSoundManager {

    private static TrainEngineSound active;
    /** Fade-in start carried across respawns in one world; -1 until the engine first plays. */
    private static long fadeInStartNanos = -1L;
    /** Ticks since the last spawn, for {@link TrainEngineLifecycle#RETRY_INTERVAL_TICKS}. */
    private static int ticksSinceSpawn = 0;

    private TrainSoundManager() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // Sound auto-stops itself when level becomes null; clear our
            // reference so a fresh instance gets created on next world load.
            active = null;
            fadeInStartNanos = -1L;
            return;
        }
        ticksSinceSpawn++;

        TrainEngineLifecycle.Action action = TrainEngineLifecycle.decide(
            TrainEngineVolume.audible(ClientDisplayConfig.getTrainEngineVolume()),
            engineCanHold(mc),
            active != null && !active.isStopped(),
            active != null && mc.getSoundManager().isActive(active),
            ticksSinceSpawn >= TrainEngineLifecycle.RETRY_INTERVAL_TICKS);

        switch (action) {
            case STOP -> release(mc);
            case SPAWN -> {
                release(mc);
                TrainEngineSound sound = new TrainEngineSound(fadeInStartNanos);
                mc.getSoundManager().play(sound);
                active = sound;
                ticksSinceSpawn = 0;
            }
            case KEEP, WAIT -> { }
        }
    }

    /**
     * Would vanilla keep the loop registered? Not with Master or Ambient at zero — unless a stream is
     * live, when {@code SoundEngineStreamVolumeMixin} keeps muted sounds playing for the stream.
     */
    private static boolean engineCanHold(Minecraft mc) {
        if (AudioMirror.live()) return true;
        return mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F
            && mc.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0.0F;
    }

    /** Stop and forget the active instance, keeping its fade-in clock for the next one. */
    private static void release(Minecraft mc) {
        if (active == null) return;
        fadeInStartNanos = active.fadeInStartNanos();
        mc.getSoundManager().stop(active);
        active.retire();
        active = null;
    }
}
