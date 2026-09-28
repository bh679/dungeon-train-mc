package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.client.ShaderDiagnostics;
import games.brennan.dungeontrain.client.TrainDebugState;
import games.brennan.dungeontrain.client.shader.ShaderBisect;
import games.brennan.dungeontrain.client.shader.ShaderPackSwitcher;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Claims <b>F3 + 4</b> for the Dungeon Train debug panel
 * ({@link games.brennan.dungeontrain.client.TrainDebugHudOverlay}) and <b>F3 + 5</b> for the
 * shader-compatibility read-out
 * ({@link games.brennan.dungeontrain.client.ShaderDiagnosticsHud}) and <b>F3 + 6</b> for the
 * shader-feature bisect ({@link ShaderBisect}) and <b>F3 + 7</b> for the shader-pack
 * switcher ({@link ShaderPackSwitcher}), alongside vanilla's own F3
 * chords. {@code 4} through {@code 7} are free in 1.21.1 — vanilla's {@code handleDebugKeys} takes
 * {@code 1}/{@code 2}/{@code 3} for the profiler charts but stops there.
 *
 * <p>Returning {@code true} is the whole reason this is a mixin rather than an
 * {@code InputEvent.Key} subscriber: the caller reads that return to set its private
 * {@code handledDebugKey} flag, which is what suppresses the vanilla F3 screen toggling when F3 is
 * finally released. A NeoForge event listener can observe the keypress but cannot reach that flag,
 * so every use of the chord would also flip vanilla's debug overlay.</p>
 *
 * <p>The toggle itself is a no-op without a live access grant (see
 * {@link TrainDebugState#toggleVisible()}). We still consume the key in that case — deliberately,
 * so pressing the chord is indistinguishable from pressing an unbound one and the panel's
 * existence isn't advertised to players who can't open it.</p>
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerDebugChordMixin {

    @Inject(method = "handleDebugKeys", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$debugPanelChord(int key, CallbackInfoReturnable<Boolean> cir) {
        if (key == InputConstants.KEY_4) {
            TrainDebugState.toggleVisible();
            cir.setReturnValue(true);
            return;
        }
        if (key == InputConstants.KEY_5) {
            ShaderDiagnostics.toggleVisible();
            cir.setReturnValue(true);
            return;
        }
        if (key == InputConstants.KEY_6) {
            ShaderBisect.cycle();
            cir.setReturnValue(true);
            return;
        }
        if (key == InputConstants.KEY_7) {
            ShaderPackSwitcher.cycle();
            cir.setReturnValue(true);
        }
    }
}
