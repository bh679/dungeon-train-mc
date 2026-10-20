package games.brennan.dungeontrain.compat.jade;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.HitResult;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.callback.JadeRayTraceCallback;

/**
 * Keeps Jade from naming an infested block in survival. Wall-miner carts and the abandoned room
 * hide silverfish inside ordinary-looking stone, and Jade's tooltip would otherwise read
 * "Infested Stone Bricks" over them. The hit block is swapped for its host block before Jade
 * builds the tooltip, so name, icon and registry id all show the plain block, as vanilla does.
 *
 * <p>Creative and spectator players keep the real name: they are building or debugging, not
 * playing. Client only; Jade's ray-trace callback runs on the client every frame.</p>
 */
final class InfestedBlockJadeClient implements JadeRayTraceCallback {

    private final IWailaClientRegistration registration;

    private InfestedBlockJadeClient(IWailaClientRegistration registration) {
        this.registration = registration;
    }

    static void register(IWailaClientRegistration registration) {
        registration.addRayTraceCallback(new InfestedBlockJadeClient(registration));
    }

    @Override
    public Accessor<?> onRayTrace(HitResult hit, Accessor<?> accessor, Accessor<?> original) {
        if (!(accessor instanceof BlockAccessor block)) return accessor;
        if (!(block.getBlock() instanceof InfestedBlock infested)) return accessor;
        if (!shouldHide(block.getPlayer())) return accessor;
        Block host = infested.getHostBlock();
        return registration.blockAccessor()
            .from(block)
            .blockState(host.defaultBlockState())
            .blockEntity((BlockEntity) null)
            .build();
    }

    /** Survival and adventure players must not learn what the block really is. */
    static boolean shouldHide(Player player) {
        return player != null && !player.isCreative() && !player.isSpectator();
    }
}
