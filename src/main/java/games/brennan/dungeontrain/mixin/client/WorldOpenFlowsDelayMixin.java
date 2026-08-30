package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.JoinIntroFade;
import games.brennan.dungeontrain.client.JoinOpenDelay;
import games.brennan.dungeontrain.client.LoadingSequenceProgress;
import games.brennan.dungeontrain.client.WorldOpenLoadingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/**
 * Gives the menu dissolve the frames it needs, by holding the world open back until it has played.
 *
 * <p>Both ways into a world land here — {@code createFreshLevel} for a new one,
 * {@code openWorld} for an existing one — and both begin by putting up a message screen and then
 * blocking the render thread for about a second. Nothing is drawn during that second, so the
 * hand-off from the menu has to happen <em>before</em> it: this arms {@link JoinIntroFade}, puts
 * {@link WorldOpenLoadingScreen} up over the menu it will fade out, parks the real call in
 * {@link JoinOpenDelay}, and returns. A couple of ticks later the game loop has painted the
 * dissolve and the parked call runs for real, re-entering this method with nothing pending.</p>
 *
 * <p>Deliberately not a swap on {@code ScreenEvent.Opening} like {@code WorldOpenScreenSwap}:
 * by the time that event fires we are already inside the blocking call and it is too late to
 * render anything. This has to intercept before the open starts at all.</p>
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsDelayMixin {

    @Inject(method = "createFreshLevel", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$delayFreshLevel(
            String levelName, LevelSettings levelSettings, WorldOptions worldOptions,
            Function<RegistryAccess, WorldDimensions> dimensionGetter, Screen lastScreen,
            CallbackInfo ci) {
        WorldOpenFlows self = (WorldOpenFlows) (Object) this;
        dungeontrain$holdForFade(ci, () ->
                self.createFreshLevel(levelName, levelSettings, worldOptions, dimensionGetter, lastScreen));
    }

    @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$delayOpenWorld(String worldName, Runnable onFail, CallbackInfo ci) {
        WorldOpenFlows self = (WorldOpenFlows) (Object) this;
        dungeontrain$holdForFade(ci, () -> self.openWorld(worldName, onFail));
    }

    /**
     * Arm the hand-off and park {@code open}, unless this <em>is</em> the parked call coming back
     * round — in which case let it through untouched.
     */
    private void dungeontrain$holdForFade(CallbackInfo ci, Runnable open) {
        if (JoinOpenDelay.isReleasing()) {
            return; // the parked call, re-entering — this is the one that must actually run
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            return; // not a hand-off from the menu (e.g. reopening from in-world); no fade to play
        }
        // Starts JoinIntroFade's clock too, so the dissolve is timed from this exact instant.
        LoadingSequenceProgress.beginJoin();
        mc.forceSetScreen(new WorldOpenLoadingScreen(mc.screen));
        JoinOpenDelay.defer(open);
        ci.cancel();
    }
}
