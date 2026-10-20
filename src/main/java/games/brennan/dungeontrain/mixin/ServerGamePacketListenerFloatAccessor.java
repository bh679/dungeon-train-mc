package games.brennan.dungeontrain.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets sphere gravity ({@code worldgen.SphereGravityServer}) clear a player's "floating" tick count.
 *
 * <p>A sphere's upward pull can nearly cancel vanilla gravity under it, so a player hangs over the
 * void falling slower than vanilla's flight check allows — and gets kicked with "Flying is not enabled
 * on this server" after 80 ticks. Levels tick before connections, so zeroing the count each level tick
 * while a sphere is pulling keeps it below the limit; it counts normally everywhere else.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerFloatAccessor {

    @Accessor("aboveGroundTickCount")
    void dungeontrain$setAboveGroundTickCount(int ticks);
}
