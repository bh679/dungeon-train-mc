package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.SpheresGravityConfig;
import games.brennan.dungeontrain.worldgen.SphereField;
import games.brennan.dungeontrain.worldgen.SphereGravity;
import games.brennan.dungeontrain.worldgen.SphereGravityPush;
import games.brennan.dungeontrain.worldgen.SphereGravityShelter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * Client half of sphere gravity: pulls the local player toward the spheres around them each tick.
 * Player movement is client-authoritative, so the server can't do this (it pulls everything else —
 * {@code worldgen.SphereGravityServer}). Both sides roll the same seed-stable spheres
 * ({@link ClientSphereField}), so no per-tick packet is needed.
 *
 * <p>Off for spectators, creative flight, elytra gliding, anyone riding something and anyone in a dimensional
 * carriage (portal twin space, {@link ClientUpsideDownBand#isInPortalTwinSpace}). On the train the
 * pull is cut to the rider factor, and to nothing inside a carriage ({@link SphereGravityShelter}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SphereGravityClient {

    /** Last tick's pull and exposure, for the debug command's client echo and logging. */
    private static volatile SphereGravity.Pull lastPull = SphereGravity.Pull.ZERO;

    private SphereGravityClient() {}

    public static SphereGravity.Pull lastPull() {
        return lastPull;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.isPaused() || !Level.OVERWORLD.equals(player.level().dimension())) return;
        lastPull = SphereGravity.Pull.ZERO;
        if (!SpheresGravityConfig.enabled() || SpheresGravityConfig.strength() <= 0.0) return;
        if (player.isSpectator() || player.getAbilities().flying || player.isFallFlying() || player.isPassenger()) return;
        if (ClientUpsideDownBand.isInPortalTwinSpace(player.getBlockX(), player.getBlockY())) return;
        double midY = player.getY() + player.getBbHeight() * 0.5;
        List<SphereField.Sphere> spheres = ClientSphereField.spheresNear(player.getX(), midY, player.getZ(),
                SphereGravityPush.lookupReach());
        SphereGravity.Pull pull = SphereGravityPush.pullOn(player, spheres);
        if (pull.isZero()) return;
        double factor = SphereGravityShelter.factor(player, SpheresGravityConfig.riderFactor());
        SphereGravityPush.apply(player, pull, factor);
        lastPull = pull.scale(factor);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSphereField.reset();
        SphereGravityShelter.clearClient();
        lastPull = SphereGravity.Pull.ZERO;
    }
}
