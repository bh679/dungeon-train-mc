package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.player.FlyDoubleTapSprint;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a flying player double-tap forward to sprint-fly (see {@link FlyDoubleTapSprint} for why
 * vanilla doesn't). HEAD captures forward before {@code aiStep} ticks the input, TAIL compares.
 * Keeps its own timer rather than vanilla's {@code sprintTriggerTime}, so ground double-tap is
 * untouched; stopping (releasing forward, hitting a wall) is still vanilla's job, and the sprint
 * state reaches the server through vanilla's own player-command packet.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerFlyDoubleTapSprintMixin {

    @Shadow public Input input;

    @Shadow protected abstract boolean canStartSprinting();

    @Unique private boolean dungeontrain$wasForward;
    @Unique private int dungeontrain$flySprintTimer;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void dungeontrain$captureForward(CallbackInfo ci) {
        dungeontrain$wasForward = input.hasForwardImpulse();
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void dungeontrain$flyDoubleTapSprint(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        boolean eligible = self.getAbilities().flying && !self.isPassenger()
                && !self.isSprinting() && canStartSprinting();
        FlyDoubleTapSprint.Step step = FlyDoubleTapSprint.tick(
                dungeontrain$flySprintTimer, dungeontrain$wasForward, input.hasForwardImpulse(), eligible);
        dungeontrain$flySprintTimer = step.timer();
        if (step.sprint()) self.setSprinting(true);
    }
}
