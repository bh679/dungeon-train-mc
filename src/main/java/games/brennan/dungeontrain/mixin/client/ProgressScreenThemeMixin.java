package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.LoadingScreenTheme;
import games.brennan.dungeontrain.client.LoadingSequenceProgress;
import games.brennan.dungeontrain.client.LoadingStories;
import games.brennan.dungeontrain.client.builder.BuilderWorldCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Themes the {@link ProgressScreen} vanilla shows between the world-open message and
 * {@code LevelLoadingScreen} — the last un-themed frame of the join, and a visible flash back to
 * vanilla chrome roughly a third of a second long.
 *
 * <p>A mixin rather than a screen swap (the trick used one screen earlier by
 * {@code WorldOpenScreenSwap}) because this screen is also the {@code ProgressListener} the
 * level-load flow drives — it must stay the live instance, only its painting changes.</p>
 *
 * <p>Two guards, both load-bearing:</p>
 * <ul>
 *   <li><b>{@code stop}</b> — vanilla's render closes the screen ({@code setScreen(null)}) once
 *       stopped. Cancelling that branch would strand the screen forever, so it is left to vanilla.</li>
 *   <li><b>{@link LoadingSequenceProgress#isJoining()}</b> — the same class is what
 *       {@code Minecraft.disconnect} uses when leaving a world, which must keep its vanilla look.
 *       The join flag is raised by the first screen of the sequence and cleared once the player is
 *       in-world.</li>
 * </ul>
 */
@Mixin(ProgressScreen.class)
public abstract class ProgressScreenThemeMixin {

    @Shadow private boolean stop;

    private static final int TIP_MAX_WIDTH = 260;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$renderThemed(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!LoadingSequenceProgress.isJoining() || BuilderWorldCheck.isBuilderWorld()) {
            return; // leaving a world, or a builder sandbox — vanilla renders
        }
        ProgressScreen self = (ProgressScreen) (Object) this;
        Font font = Minecraft.getInstance().font;

        LoadingScreenTheme.fillBackground(g, self.width, self.height);

        int cx = self.width / 2;
        int cy = self.height / 2;
        double progress = LoadingSequenceProgress.displayed();
        long animNanos = LoadingSequenceProgress.animNanos();

        int railW = Math.min(LoadingScreenTheme.MAX_RAIL_W, self.width - 80);
        int railLeft = cx - railW / 2;
        int railY = cy + 8;

        LoadingScreenTheme.drawTitle(g, font, Component.translatable("gui.dungeontrain.loading.title"), cx, cy - 30);
        LoadingScreenTheme.drawFillingTrain(g, font, railLeft, railW, railY, progress, animNanos);
        LoadingScreenTheme.drawPercent(g, font, progress, cx, cy + 34);
        LoadingScreenTheme.drawTip(g, font, LoadingStories.currentLine(), cx, cy + 52, TIP_MAX_WIDTH);

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
