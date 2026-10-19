package games.brennan.dungeontrain.client.wind;

import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.wind.TrainExposure;
import games.brennan.dungeontrain.wind.WindProfile;
import games.brennan.dungeontrain.wind.WindPush;
import games.brennan.dungeontrain.wind.WindSample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client half of the train wind, once per client tick:
 * <ul>
 *   <li><b>Push the local player</b> with the wind <em>now</em> when they are out in the open on the train
 *       ({@link TrainExposure}) — player movement is client-authoritative, so this side does it.</li>
 *   <li><b>Show the wind {@code visualLeadTicks} ahead</b>: hand it to Windy through {@link WindyDrive}, or,
 *       without Windy: Configurable's hook, spawn DT's own vanilla streaks that fly along the heading.</li>
 * </ul>
 * The server pushes everything else with the same {@code WindField}, so both agree without a per-tick packet.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class WindClientEvents {

    /** Fallback particles per tick at push 1.0 (capped below). */
    private static final double FALLBACK_RATE = 60.0;
    private static final int FALLBACK_MAX_PER_TICK = 3;
    private static final double FALLBACK_UPWIND_MIN = 6.0;
    private static final double FALLBACK_UPWIND_MAX = 18.0;
    private static final double FALLBACK_SPREAD = 10.0;

    private WindClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.isPaused()) return;
        if (level.dimension() != Level.OVERWORLD || !ClientWind.active()) {
            WindyDrive.release();
            WindDebugState.clear(level.dimension() == Level.OVERWORLD ? "wind off" : "not in the Overworld");
            return;
        }
        long tick = level.getGameTime();
        WindProfile profile = ClientWind.profile();
        SubLevel sub = ClientWind.pastStart(player.getX()) ? TrainExposure.subLevelUnder(level, player) : null;
        WindSample now = ClientWind.sampleAt(player.getX(), tick);

        TrainExposure.Result exposure = now.isCalm() ? TrainExposure.Result.NOT_ON_TRAIN
                : TrainExposure.classify(sub, player, now.dirX(), now.dirZ());
        String skip = skipReason(player, sub);
        double extra = player.isShiftKeyDown() ? profile.sneakFactor() : 1.0;
        double applied = 0.0;
        if (skip == null && exposure.exposed()) {
            applied = WindPush.apply(player, now, profile, extra);
        }

        WindSample ahead = ClientWind.sampleAt(player.getX(), tick + profile.visualLeadTicks());
        WindyDrive.set(true, ahead.strength(), ahead.headingRad());
        boolean directional = WindyPresence.directional();
        if (!directional && sub != null && !ahead.isCalm()) {
            fallbackStreaks(level, player, ahead);
        }

        WindDebugState.record(new WindDebugState.Frame(
                tick, player.getX() - ClientWind.startX(), ClientWind.startX(), profile.stageAt(player.getX() - ClientWind.startX()),
                now, ahead, profile.visualLeadTicks(), sub != null, exposure, skip,
                player.onGround(), player.isShiftKeyDown(), applied,
                WindPush.accel(player, now, profile, extra),
                player.getDeltaMovement().x, player.getDeltaMovement().z,
                directional, WindyDrive.strength()));
    }

    /** Why the local player isn't pushed at all, or {@code null} when they can be. */
    private static String skipReason(LocalPlayer player, SubLevel sub) {
        if (player.isSpectator()) return "spectator";
        if (player.getAbilities().flying) return "flying";
        if (player.isPassenger()) return "riding";
        if (player.isFallFlying()) return "elytra";
        if (sub == null) return "not over a carriage";
        return null;
    }

    /** DT's own wind streaks for installs without Windy: Configurable's direction hook. */
    private static void fallbackStreaks(ClientLevel level, LocalPlayer player, WindSample w) {
        RandomSource r = level.random;
        double expected = Math.min(FALLBACK_MAX_PER_TICK, w.strength() * FALLBACK_RATE);
        int n = (int) expected + (r.nextDouble() < expected - Math.floor(expected) ? 1 : 0);
        double dx = w.dirX();
        double dz = w.dirZ();
        double speed = 0.25 + w.strength() * 8.0;
        for (int i = 0; i < n; i++) {
            double back = FALLBACK_UPWIND_MIN + r.nextDouble() * (FALLBACK_UPWIND_MAX - FALLBACK_UPWIND_MIN);
            double side = (r.nextDouble() * 2.0 - 1.0) * FALLBACK_SPREAD;
            double x = player.getX() - dx * back - dz * side;
            double z = player.getZ() - dz * back + dx * side;
            double y = player.getY() + r.nextDouble() * 4.0 - 0.5;
            level.addParticle(ParticleTypes.CLOUD, x, y, z, dx * speed, 0.0, dz * speed);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientWind.reset();
        WindyDrive.release();
    }
}
