package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's <b>Create New World</b> starts on the difficulty the player last chose
 * ({@link ClientDisplayConfig#getNewRunDifficulty()}), and a world created here remembers its
 * difficulty for the next one — so the screen agrees with the title screen's New World and the
 * death-screen reboard, which read the same setting.
 *
 * <p>{@code <init>} TAIL for the same reason as {@link CreateWorldScreenDefaultPresetMixin}: the
 * constructor runs once per screen, so a choice made on the screen survives its resize/tab
 * re-inits. A Hardcore world is never remembered — vanilla pins Hardcore to Hard, which says
 * nothing about the difficulty the player wants for their ordinary runs.</p>
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenDifficultyMixin {

    @Shadow
    public abstract WorldCreationUiState getUiState();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void dungeontrain$applyRememberedDifficulty(CallbackInfo ci) {
        this.getUiState().setDifficulty(ClientDisplayConfig.getNewRunDifficulty());
    }

    @Inject(method = "onCreate", at = @At("HEAD"))
    private void dungeontrain$rememberDifficulty(CallbackInfo ci) {
        WorldCreationUiState state = this.getUiState();
        if (!state.isHardcore()) {
            ClientDisplayConfig.setNewRunDifficulty(state.getDifficulty());
        }
    }
}
