package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.DonationSummaryCache;
import games.brennan.dungeontrain.client.support.SplashPicker;
import games.brennan.dungeontrain.client.support.UpdateStats;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.resources.SplashManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Puts the shipped-update counts into the main menu's splash cycle — "122 changes this week!"
 * alongside "Please remain seated until the dungeon clears."
 *
 * <p>Vanilla picks a splash once per {@link net.minecraft.client.gui.screens.TitleScreen}
 * construction, so every visit to the menu re-rolls, and a player who dies back to the menu a few
 * times will see several of the timeframes.</p>
 *
 * <p><b>Live figures only.</b> These lines are claims about today and this week; the numbers baked
 * into the jar are frozen at build time, so on any jar more than a day or two old they would be
 * confidently wrong. When the relay has not answered — offline, or the menu opened before the
 * prefetch landed — this defers to vanilla and the player sees the ordinary quote cycle.</p>
 *
 * <p>Cancelling at HEAD also overrides vanilla's date specials ("Happy new year!"). That is
 * accepted: those fire on a handful of days a year and the pool is re-rolled every visit.</p>
 */
@Mixin(SplashManager.class)
public abstract class SplashUpdateCountsMixin {

    /** Vanilla's own pool, so the weighting knows what it is competing against. */
    @Shadow @org.spongepowered.asm.mixin.Final private List<String> splashes;

    @Inject(method = "getSplash", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$updateCountSplash(CallbackInfoReturnable<SplashRenderer> cir) {
        try {
            UpdateStats.Figures figures = UpdateStats.current(
                    DonationSummaryCache.get() == null ? null : DonationSummaryCache.get().updates());
            List<UpdateStats.Timeframe> available = UpdateStats.splashTimeframes(figures);
            if (available.isEmpty()) return;

            UpdateStats.Timeframe chosen = SplashPicker.choose(available,
                    splashes == null ? 0 : splashes.size(),
                    ThreadLocalRandom.current().nextDouble());
            if (chosen == null) return;

            cir.setReturnValue(new SplashRenderer(UpdateStats.splash(figures, chosen).getString()));
        } catch (Throwable ignored) {
            // A splash is decoration. Anything unexpected here falls through to vanilla's pick
            // rather than taking the main menu down with it.
        }
    }
}
