package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import games.brennan.dungeontrain.world.PortalRoofCap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Holds an exit portal under a Nether core's roof while {@link PortalRoofCap} is set: a frame above it is not reused,
 * and a new one is searched for (and, with no room anywhere, carved) from the cap down. Everything else about
 * finding and building the frame stays vanilla.
 */
@Mixin(PortalForcer.class)
public abstract class PortalForcerRoofCapMixin {

    @Shadow @Final protected ServerLevel level;

    @ModifyReturnValue(method = "findClosestPortalPosition", at = @At("RETURN"))
    private Optional<BlockPos> dungeontrain$ignorePortalOnRoof(Optional<BlockPos> found) {
        return found.filter(pos -> pos.getY() <= PortalRoofCap.topY());
    }

    /** {@code createPortal}'s top row is {@code minBuildHeight + logicalHeight - 1}, so this makes it the cap. */
    @ModifyExpressionValue(method = "createPortal",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getLogicalHeight()I"))
    private int dungeontrain$searchUnderRoof(int logicalHeight) {
        int top = PortalRoofCap.topY();
        return top == Integer.MAX_VALUE ? logicalHeight : Math.min(logicalHeight, top + 1 - level.getMinBuildHeight());
    }
}
