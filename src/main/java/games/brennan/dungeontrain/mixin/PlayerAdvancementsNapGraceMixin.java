package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.nap.NapWakeGrace;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.Criterion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuse wake-caused advancement criteria while a nap wakes ({@link NapWakeGrace#refuses}): the new
 * world, train and biome are not earned; event criteria still pass. Returning {@code false} is what
 * {@code award} does for an already-granted criterion, so callers need no special case.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsNapGraceMixin {

    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$refuseWhileWaking(AdvancementHolder holder, String criterion,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (player == null || !NapWakeGrace.isActive(player.getUUID())) return;
        Criterion<?> c = holder.value().criteria().get(criterion);
        ResourceLocation id = c == null ? null : BuiltInRegistries.TRIGGER_TYPES.getKey(c.trigger());
        if (NapWakeGrace.refuses(player.getUUID(), id == null ? null : id.toString())) {
            cir.setReturnValue(false);
        }
    }
}
