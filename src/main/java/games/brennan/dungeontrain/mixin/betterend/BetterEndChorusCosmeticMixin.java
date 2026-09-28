package games.brennan.dungeontrain.mixin.betterend;

import org.betterx.betterend.world.generator.GeneratorOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps chorus plants vanilla. BetterEnd: New Dawn's cosmetic option {@code change_chorus_plant} (on by
 * default) swaps the vanilla {@code chorus_plant} / {@code chorus_flower} block models for its own
 * {@code custom_chorus_*} on the client and changes the flower's hitbox. A model swap applies to the
 * block everywhere, so it would restyle the chorus DT grows on the <b>vanilla</b> End-islands bands too.
 * {@code GeneratorOptions.changeChorusPlant()} is the one getter every reader uses (model loader hook,
 * client model registration, flower shape), so answering {@code false} here covers all of them whatever
 * the player's config file says. BetterEnd's own bands keep every other custom block.
 */
@Mixin(value = GeneratorOptions.class, remap = false)
public abstract class BetterEndChorusCosmeticMixin {

    @Inject(method = "changeChorusPlant", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$keepVanillaChorus(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
