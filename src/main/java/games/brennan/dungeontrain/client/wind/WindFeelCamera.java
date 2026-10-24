package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Effect E ({@link WindFeelMode#lean}): the view leans a little with the push and shudders at a gust's peak.
 * Scaled by vanilla's Screen Effect Scale, so players who turned distortion off get none; never in the
 * front-facing third-person view.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class WindFeelCamera {

    /** Roll at a full sideways push, degrees. */
    private static final double MAX_ROLL_DEG = 1.5;
    /** Extra roll swing at the height of a gust's shudder, degrees. */
    private static final double SHUDDER_DEG = 0.6;
    /** Shudder oscillation, radians per tick. */
    private static final double SHUDDER_RATE = 1.7;
    /** +1 leans the view toward the push; flip if it reads the wrong way. */
    private static final double ROLL_SIGN = 1.0;

    private WindFeelCamera() {}

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!WindFeelMode.current().lean) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.getCameraType().isMirrored()) return;
        float pt = (float) event.getPartialTick();
        double felt = WindFeel.felt(pt);
        double shudder = WindFeel.shudder();
        if (felt <= 0.0 && shudder <= 0.0) return;
        double scale = mc.options.screenEffectScale().get();
        if (scale <= 0.0) return;
        double lateral = WindFeelMath.lateral(WindFeel.dirX(), WindFeel.dirZ(), event.getYaw());
        double t = WindFeel.ticks() + pt;
        double roll = lateral * MAX_ROLL_DEG * felt + SHUDDER_DEG * shudder * Math.sin(t * SHUDDER_RATE);
        event.setRoll(event.getRoll() + (float) (ROLL_SIGN * roll * scale));
    }
}
