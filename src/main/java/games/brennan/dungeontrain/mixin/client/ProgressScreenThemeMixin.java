package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.JoinIntroFade;
import games.brennan.dungeontrain.client.LoadingScreenTheme;
import games.brennan.dungeontrain.client.LoadingSequenceProgress;
import games.brennan.dungeontrain.client.builder.BuilderWorldCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Themes the {@link ProgressScreen} vanilla shows between the world-open message and
 * {@code LevelLoadingScreen} — a visible flash back to vanilla chrome roughly a third of a second
 * long, and the last un-themed frame of the join.
 *
 * <p>A mixin rather than a screen swap (the trick used one screen earlier by
 * {@code WorldOpenScreenSwap}) because this screen is also the {@code ProgressListener} the
 * level-load flow drives — it must stay the live instance, only its painting changes. It extends
 * {@link Screen} so the inherited {@code PANORAMA} is in reach: while the hand-off from the menu
 * is still fading, this screen has to keep drawing the same panorama the previous one did, rather
 * than let vanilla draw panorama <em>plus</em> blur <em>plus</em> the dark tiled menu background —
 * which would jump partway through the cross-fade.</p>
 *
 * <p>Two guards, both load-bearing:</p>
 * <ul>
 *   <li><b>{@code stop}</b> — vanilla's render closes the screen ({@code setScreen(null)}) once
 *       stopped. Cancelling that branch would strand the screen forever, so it is left to vanilla.</li>
 *   <li><b>{@link LoadingSequenceProgress#isJoining()}</b> — the same class is what
 *       {@code Minecraft.disconnect} uses when leaving a world, which must keep its vanilla look.</li>
 * </ul>
 *
 * <p>That second guard is subtler than it looks: {@code Minecraft.doWorldLoad} <em>opens</em> with
 * {@code disconnect()}, so this very screen is created by a disconnect even on the way <em>into</em>
 * a world, and DT used to clear its own join flag from the null-player logout that came with it —
 * which is why this mixin never painted until {@code CinematicPreloadGate.onLoggingOut} learned to
 * tell the two apart.</p>
 */
@Mixin(ProgressScreen.class)
public abstract class ProgressScreenThemeMixin extends Screen {

    @Shadow private boolean stop;

    private ProgressScreenThemeMixin(Component title) {
        super(title); // never called — the mixin only needs a constructor to extend Screen
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$renderThemed(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!LoadingSequenceProgress.isJoining() || BuilderWorldCheck.isBuilderWorld()) {
            return; // leaving a world, or a builder sandbox — vanilla renders
        }

        float themeAlpha = JoinIntroFade.themeAlpha();
        if (themeAlpha < 1.0f) {
            // Mid hand-off: the backdrop is still the menu panorama, with the panel rising over it.
            PANORAMA.render(g, this.width, this.height, 1.0f, partialTick);
        }
        LoadingScreenTheme.drawPanel(g, Minecraft.getInstance().font, this.width, this.height,
                LoadingSequenceProgress.displayed(), LoadingSequenceProgress.animNanos(), themeAlpha);

        // Painted first, cancelled second — because vanilla's own render is what CLOSES this
        // screen once stopped ({@code setScreen(null)}), and that branch draws nothing at all.
        // Returning early there (rather than painting, then letting it run) left the last frames
        // of the screen unpainted: the flash at the hand-off to the world-load screen.
        if (this.stop) {
            return;
        }
        ci.cancel();
    }
}
