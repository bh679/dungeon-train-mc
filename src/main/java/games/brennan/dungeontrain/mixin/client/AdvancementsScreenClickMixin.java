package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.AdvancementTrackClick;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a click on a trackable advancement's tile toggle tracking (see
 * {@link AdvancementTrackClick}). Vanilla's {@code mouseClicked} only switches tabs, so the
 * injection runs alongside it without cancelling. Better Advancements twin:
 * {@code BetterAdvancementsScreenClickMixin}.
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsScreenClickMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void dungeontrain$toggleTracking(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        AdvancementTrackClick.handle(button);
    }
}
