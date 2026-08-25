package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.CustomContentGate;
import games.brennan.dungeontrain.mixin.CreateWorldScreenAccessor;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the custom-content question in front of vanilla's <b>Create New World</b>, the one route
 * that starts a run without passing through Dungeon Train's own buttons.
 *
 * <p>Every other way of starting a run already asks before the world exists
 * ({@code CustomContentGate}); worlds made here used to reach the join-time prompt instead, which
 * asks after worldgen — by which point "run without my changes" is answering for a world already
 * built from them.</p>
 *
 * <p>The question is only put when the world would otherwise <b>count</b>. {@code askFirst} decides
 * that from the game mode selected on this very screen, so a Creative world made here is as exempt
 * as the Train Editor's is.</p>
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenCustomContentMixin {

    /**
     * Set while re-entering {@code onCreate} from the prompt's answer callback, so the second pass
     * falls through instead of asking again. Without it the prompt reopens on every answer and the
     * world is never created.
     */
    @Unique
    private boolean dungeontrain$answered;

    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$askBeforeCreating(CallbackInfo ci) {
        if (dungeontrain$answered) {
            dungeontrain$answered = false;
            return;
        }
        CreateWorldScreen self = (CreateWorldScreen) (Object) this;
        WorldCreationUiState state = ((CreateWorldScreenAccessor) self).dungeontrain$getUiState();
        // Read at the moment of creation, not at screen init: the player can change the mode right
        // up until they press the button.
        GameType mode = state != null && state.getGameMode() != null
            ? state.getGameMode().gameType
            : GameType.SURVIVAL;

        boolean asking = CustomContentGate.askFirst(mode, self, () -> {
            dungeontrain$answered = true;
            ((CreateWorldScreenInvoker) self).dungeontrain$onCreate();
        });
        // Not asking means the answer is already recorded (remembered, creative, or no content at
        // all) and vanilla may carry straight on.
        if (asking) {
            ci.cancel();
        }
    }
}
