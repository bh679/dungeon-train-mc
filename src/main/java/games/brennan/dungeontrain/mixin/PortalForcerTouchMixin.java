package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.world.TouchedChunks;
import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** An exit portal built for a traveller is theirs: its chunk is always saved ({@link TouchedChunks}). */
@Mixin(PortalForcer.class)
public abstract class PortalForcerTouchMixin {

    @Shadow @Final protected ServerLevel level;

    @Inject(method = "createPortal", at = @At("RETURN"))
    private void dungeontrain$markBuiltPortal(BlockPos pos, Direction.Axis axis,
                                             CallbackInfoReturnable<Optional<BlockUtil.FoundRectangle>> cir) {
        cir.getReturnValue().ifPresent(rect -> {
            TouchedChunks.mark(level, rect.minCorner);
            TouchedChunks.mark(level, rect.minCorner.offset(rect.axis1Size, 0, rect.axis1Size));
        });
    }
}
