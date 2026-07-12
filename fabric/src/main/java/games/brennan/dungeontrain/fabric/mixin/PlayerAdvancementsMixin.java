package games.brennan.dungeontrain.fabric.mixin;

import games.brennan.dungeontrain.fabric.DtFire;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gap-filler for {@code DtEvents.ADVANCEMENT_EARN} (NeoForge
 * {@code AdvancementEvent.AdvancementEarnEvent}). Fabric has no advancement event; fires
 * from {@code PlayerAdvancements.award} on the award call that completes the advancement
 * (progress changed AND now done), matching NeoForge's fire-once-earned semantics.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    @Shadow private ServerPlayer player;

    @Shadow public abstract AdvancementProgress getOrStartProgress(AdvancementHolder advancement);

    @Inject(method = "award", at = @At("RETURN"))
    private void dungeonTrain$award(AdvancementHolder advancement, String criterionName,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return; // this award changed nothing
        }
        if (!getOrStartProgress(advancement).isDone()) {
            return; // not fully earned yet
        }
        DtFire.fire(DtEvents.ADVANCEMENT_EARN.listeners(), cb -> cb.onAdvancementEarn(player, advancement));
    }
}
