package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.train.SharedBuildBlockEntityEdits;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a drifting build its sign changed, so the text reaches the relay
 * (see {@link SharedBuildBlockEntityEdits} for why no block-change path can).
 *
 * <p>{@code markUpdated} is the one method every content change runs through — a player's edit,
 * dye, glow ink, wax — and hanging signs inherit it. Loading a sign from NBT assigns its text
 * directly and never calls it, so stamping a leased carriage queues nothing.</p>
 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityEditMixin {

    @Inject(method = "markUpdated", at = @At("TAIL"))
    private void dungeontrain$queueSharedBuildEdit(CallbackInfo ci) {
        SignBlockEntity self = (SignBlockEntity) (Object) this;
        if (self.getLevel() instanceof ServerLevel level) {
            SharedBuildBlockEntityEdits.onBlockEntityEdited(level, self.getBlockPos());
        }
    }
}
