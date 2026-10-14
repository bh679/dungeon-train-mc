package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.compat.photo.PhotoFrameBlockPlacer;
import io.github.mortuusars.exposure.world.item.PhotographFrameItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hangs Exposure's photo frames (plain and glass — the glass item inherits {@code useOn}) on walls as
 * DT's {@code photograph_frame} block, which rides the train; see {@link PhotoFrameBlockPlacer}.
 * Injected at the item rather than on {@code RightClickBlock} so the clicked block keeps first use.
 */
@Mixin(PhotographFrameItem.class)
public abstract class PhotographFrameItemPlaceMixin {

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$placeAsBlock(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = PhotoFrameBlockPlacer.tryPlace(context);
        if (result != null) cir.setReturnValue(result);
    }
}
