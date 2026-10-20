package games.brennan.dungeontrain.client.wind;

import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import games.brennan.dungeontrain.wind.TrainExposure;
import games.brennan.dungeontrain.wind.WindZones;
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

    /** Larger per-tick carriage moves than this are teleports/re-anchors, not motion. */
    private static final double MAX_CARRIAGE_STEP = 4.0;
    private static final double VELOCITY_SMOOTHING = 0.3;

    private static SubLevel lastSub = null;
    private static double lastX = 0.0;
    private static double lastZ = 0.0;
    private static double smoothVx = 0.0;
    private static double smoothVz = 0.0;

    private WindClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.isPaused()) return;
        if (level.dimension() != Level.OVERWORLD || !ClientWind.active()) {
            WindyDrive.release();
            AmbientWind.release();
            WindSound.silence();
            WindDebugState.clear(level.dimension() == Level.OVERWORLD ? "wind off" : "not in the Overworld");
            return;
        }
        // A dimensional carriage is somewhere else entirely: no wind, no streaks, no sound.
        if (ClientUpsideDownBand.isInPortalTwinSpace((int) Math.floor(player.getX()), (int) Math.floor(player.getY()))) {
            WindyDrive.release();
            AmbientWind.release();
            WindSound.silence();
            WindDebugState.clear("in a dimensional carriage: no wind");
            return;
        }
        long tick = level.getGameTime();
        WindProfile profile = ClientWind.profile();
        SubLevel sub = ClientWind.pastStart(player.getX()) ? TrainExposure.subLevelUnder(level, player) : null;
        double deepDark = WindZones.deepDarkFactor(level, player.blockPosition());
        WindSample now = ClientWind.sampleAt(player.getX(), tick).scaled(deepDark);

        TrainExposure.Result exposure = now.isCalm() ? TrainExposure.Result.NOT_ON_TRAIN
                : TrainExposure.classify(sub, player, now.dirX(), now.dirZ());
        String skip = skipReason(player, sub);
        double extra = player.isShiftKeyDown() ? profile.sneakFactor() : 1.0;
        double applied = 0.0;
        if (skip == null && exposure.exposed()) {
            applied = WindPush.apply(player, now, profile, extra);
        }

        WindSample ahead = ClientWind.sampleAt(player.getX(), tick + profile.visualLeadTicks()).scaled(deepDark);
        // Timing of a gust: sound and streaks both start visualLeadTicks early (you hear and see it coming before
        // it shoves); the sound ends that early too, but the streaks hold until the shove itself is over.
        WindSound.update(player, ahead, exposure, TrainExposure.underTerrain(player));
        WindSample visual = visualSample(now, ahead);
        double[] carriageVel = carriageVelocity(sub);
        // Visuals follow the wind, not the player: they keep blowing outside while you shelter, and each streak
        // is kept out of carriages and tunnels on its own (WindVisualShelter).
        WindyDrive.set(visual.strength(), visual.gust01(), visual.headingRad(), carriageVel[0], carriageVel[1]);
        // Smoke, snow, ash and the rain sheets blow with the same wind, in the ground frame (AmbientWind).
        AmbientWind.set(visual);
        boolean directional = WindyPresence.directional();
        if (!directional && sub != null && !visual.isCalm()) {
            fallbackStreaks(level, player, visual, carriageVel);
        }

        WindDebugState.record(new WindDebugState.Frame(
                tick, player.getX() - ClientWind.startX(), ClientWind.startX(), profile.stageAt(player.getX() - ClientWind.startX()),
                now, ahead, profile.visualLeadTicks(), sub != null, exposure, skip,
                player.onGround(), player.isShiftKeyDown(), applied,
                WindPush.accel(player, now, profile, extra),
                player.getDeltaMovement().x, player.getDeltaMovement().z,
                directional, WindyDrive.strength()));
    }

    /**
     * The carriage's velocity (blocks/tick, XZ) from its pose's movement since last tick — zero off the train,
     * on the first tick over a new carriage, and across a jump bigger than a train could make in a tick.
     */
    private static double[] carriageVelocity(SubLevel sub) {
        if (sub == null) {
            lastSub = null;
            return new double[] {0.0, 0.0};
        }
        org.joml.Vector3dc pos = sub.logicalPose().position();
        double vx = 0.0;
        double vz = 0.0;
        if (sub == lastSub) {
            vx = pos.x() - lastX;
            vz = pos.z() - lastZ;
            if (vx * vx + vz * vz > MAX_CARRIAGE_STEP * MAX_CARRIAGE_STEP) {
                vx = 0.0;
                vz = 0.0;
            }
        }
        lastSub = sub;
        lastX = pos.x();
        lastZ = pos.z();
        // Smooth over a few ticks: Sable poses step unevenly against the client tick.
        smoothVx += (vx - smoothVx) * VELOCITY_SMOOTHING;
        smoothVz += (vz - smoothVz) * VELOCITY_SMOOTHING;
        return new double[] {smoothVx, smoothVz};
    }

    /**
     * The wind the streaks show: whichever is stronger of the wind arriving ({@code ahead}) and the wind blowing
     * now. A gust shows from the moment it is coming until it has stopped shoving, never dying down while it is
     * still pushing you.
     */
    static WindSample visualSample(WindSample now, WindSample ahead) {
        return now.strength() > ahead.strength() ? now : ahead;
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
    private static void fallbackStreaks(ClientLevel level, LocalPlayer player, WindSample w, double[] carriageVel) {
        RandomSource r = level.random;
        double expected = Math.min(FALLBACK_MAX_PER_TICK, w.strength() * FALLBACK_RATE);
        int n = (int) expected + (r.nextDouble() < expected - Math.floor(expected) ? 1 : 0);
        double dx = w.dirX();
        double dz = w.dirZ();
        double speed = WindyDrive.SPEED_BASE + w.strength() * WindyDrive.SPEED_PER_PUSH;
        for (int i = 0; i < n; i++) {
            double back = FALLBACK_UPWIND_MIN + r.nextDouble() * (FALLBACK_UPWIND_MAX - FALLBACK_UPWIND_MIN);
            double side = (r.nextDouble() * 2.0 - 1.0) * FALLBACK_SPREAD;
            double x = player.getX() - dx * back - dz * side;
            double z = player.getZ() - dz * back + dx * side;
            double y = player.getY() + r.nextDouble() * 4.0 - 0.5;
            if (WindVisualShelter.sheltered(level, x, y, z)) continue;
            level.addParticle(ParticleTypes.CLOUD, x, y, z,
                    dx * speed + carriageVel[0], 0.0, dz * speed + carriageVel[1]);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientWind.reset();
        WindyDrive.release();
        AmbientWind.reset();
        WindSound.silence();
    }
}
