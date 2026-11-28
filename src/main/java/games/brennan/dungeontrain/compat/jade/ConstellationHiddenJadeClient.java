package games.brennan.dungeontrain.compat.jade;

import games.brennan.dungeontrain.client.constellation.ConstellationClient;
import net.minecraft.world.phys.HitResult;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.callback.JadeRayTraceCallback;

/**
 * A block the constellation patch has hidden is sky to the player, so Jade shows no tooltip for it.
 * Client only; runs every frame with Jade's ray trace.
 */
final class ConstellationHiddenJadeClient implements JadeRayTraceCallback {

    private ConstellationHiddenJadeClient() {}

    static void register(IWailaClientRegistration registration) {
        registration.addRayTraceCallback(new ConstellationHiddenJadeClient());
    }

    @Override
    public Accessor<?> onRayTrace(HitResult hit, Accessor<?> accessor, Accessor<?> original) {
        if (!(accessor instanceof BlockAccessor block)) return accessor;
        return ConstellationClient.isHiddenBlock(block.getPosition().asLong()) ? null : accessor;
    }
}
