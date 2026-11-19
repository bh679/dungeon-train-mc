package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.QuickPlaySingleplayerBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.quickplay.QuickPlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a launcher's Quick Play from reopening a singleplayer world: every world is one run. The
 * game opens the title screen instead. See {@link QuickPlaySingleplayerBlock} for the why and the
 * dev-build exemption.
 */
@Mixin(QuickPlay.class)
public abstract class QuickPlaySingleplayerMixin {

    @Inject(method = "joinSingleplayerWorld", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$blockSingleplayerQuickPlay(Minecraft mc, String worldName, CallbackInfo ci) {
        if (!QuickPlaySingleplayerBlock.shouldBlock()) return;
        QuickPlaySingleplayerBlock.onBlocked(worldName);
        mc.setScreen(new TitleScreen());
        ci.cancel();
    }
}
