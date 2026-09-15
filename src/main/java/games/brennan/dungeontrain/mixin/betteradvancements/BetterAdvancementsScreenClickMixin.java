package games.brennan.dungeontrain.mixin.betteradvancements;

import games.brennan.dungeontrain.client.AdvancementTrackClick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Better Advancements half of the tracking click — see {@link AdvancementTrackClick} for the
 * rule and {@code games.brennan.dungeontrain.mixin.client.AdvancementsScreenClickMixin} for the
 * vanilla screen. BA's {@code mouseClicked} (verified against 0.4.3.21 via javap) only selects
 * tabs and then defers to {@code Screen.mouseClicked}; it has no per-widget click handling of
 * its own, so this runs alongside it without cancelling.
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementsScreen", remap = false)
public abstract class BetterAdvancementsScreenClickMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void dungeontrain$toggleTracking(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        AdvancementTrackClick.handle(button);
    }
}
