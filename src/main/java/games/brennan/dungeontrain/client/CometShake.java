package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.SpheresComets;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Shakes the camera when a spheres-band comet lands near the player: hardest at the impact, nothing beyond
 * {@value SpheresComets#SHAKE_RADIUS_BLOCKS} blocks ({@link SpheresComets#shakeStrength}). Landings don't stack, the
 * strongest one in progress wins, and the shake fades out over {@value #DURATION_TICKS} ticks. Scaled by the
 * Distortion Effects accessibility slider, so players who turn screen effects off feel none. Render-thread state only.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CometShake {

    private static final int DURATION_TICKS = 15;
    /** Largest yaw/pitch/roll kick, degrees, for a comet landing right on the player. */
    private static final float MAX_DEGREES = 4.0F;

    private static double strength;
    private static int ticksLeft;
    private static int ticks;

    private CometShake() {}

    /** A comet landed at {@code at}; shake if it was close enough to feel. */
    public static void impact(Vec3 at) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        double felt = SpheresComets.shakeStrength(player.position().distanceTo(at));
        if (felt <= 0.0 || felt < strength * fade(0.0, ticksLeft)) return;
        strength = felt;
        ticksLeft = DURATION_TICKS;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (ticksLeft <= 0 || Minecraft.getInstance().isPaused()) return;
        ticksLeft--;
        ticks++;
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (ticksLeft <= 0) return;
        float scale = Minecraft.getInstance().options.screenEffectScale().get().floatValue();
        double partial = event.getPartialTick();
        double amount = strength * fade(partial, ticksLeft) * MAX_DEGREES * scale;
        if (amount <= 0.0) return;
        double t = ticks + partial;
        event.setYaw(event.getYaw() + (float) (amount * wobble(t, 1.9, 3.1)));
        event.setPitch(event.getPitch() + (float) (amount * wobble(t, 2.3, 3.7)));
        event.setRoll(event.getRoll() + (float) (amount * 0.5 * wobble(t, 1.7, 2.9)));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ticksLeft = 0;
        strength = 0.0;
    }

    /** 1 as a shake starts, falling linearly to 0 as its ticks run out. */
    private static double fade(double partial, int left) {
        return Math.max(0.0, (left - partial) / DURATION_TICKS);
    }

    /** Smooth jitter in -1..1: two sines at unrelated speeds, so it never settles into an obvious rhythm. */
    private static double wobble(double t, double a, double b) {
        return 0.6 * Math.sin(t * a) + 0.4 * Math.sin(t * b + 1.3);
    }
}
