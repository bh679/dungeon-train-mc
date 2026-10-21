package games.brennan.dungeontrain.mixin.client;

import games.brennan.discordpresence.client.SurveyScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Discord Presence's feedback survey a pause screen when it opens over the world or the pause
 * menu, so writing a {@code /bug} (or {@code /feedback}) report pauses the run: in singleplayer the
 * integrated server stops ticking behind it, and in multiplayer
 * {@link games.brennan.dungeontrain.client.ClientActivityReporter} reads {@code isPauseScreen()} and
 * tells the server the player is paused, stopping the train-time clocks the way the pause menu does.
 *
 * <p>DP's {@code SurveyScreen} answers {@code false} on its own so the survey can sit over the death
 * screen without freezing the world (the death screen keeps the engine sounding, see
 * {@code NarrativeDeathScreen#isPauseScreen}). That case is kept: the survey only pauses when its
 * parent is nothing (opened from the world by a command) or itself a pause screen (the Builder pause
 * menu's Bug button).</p>
 */
@Mixin(SurveyScreen.class)
public abstract class SurveyScreenPauseMixin {

    @Shadow @Final private Screen parent;

    @Inject(method = "isPauseScreen", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$pauseWhenOverWorld(CallbackInfoReturnable<Boolean> cir) {
        if (parent == null || parent.isPauseScreen()) {
            cir.setReturnValue(true);
        }
    }
}
