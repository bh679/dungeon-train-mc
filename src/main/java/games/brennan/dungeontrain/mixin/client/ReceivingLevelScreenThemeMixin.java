package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.LoadingScreenTheme;
import games.brennan.dungeontrain.client.LoadingSequenceProgress;
import games.brennan.dungeontrain.client.LoadingStories;
import games.brennan.dungeontrain.client.builder.BuilderWorldCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third and last screen of the world-join sequence, themed to match the other two.
 *
 * <p>Vanilla opens {@link ReceivingLevelScreen} from {@code ClientPacketListener.handleLogin}
 * ({@code Minecraft.setLevel(level, Reason.OTHER)}), between the themed
 * {@link games.brennan.dungeontrain.mixin.client.LevelLoadingScreenThemeMixin} world-load screen
 * and {@link games.brennan.dungeontrain.client.CinematicLoadingScreen}. With
 * {@code Reason.OTHER} it draws the <em>main-menu panorama</em> plus "Downloading terrain" — so
 * the loading sequence visibly snapped back to a vanilla-looking screen for a few frames right
 * at the world → train handoff. {@code CinematicPreloadGate} could not cover it: the gate is
 * still {@code IDLE} at that instant (it is armed by a packet that arrives a moment later).</p>
 *
 * <p>Same replacement pattern as the world-load screen: {@code @Inject(at = HEAD,
 * cancellable = true)} + {@code ci.cancel()}. Cancelling {@code render} also suppresses vanilla's
 * {@code renderBackground} (the panorama), since that is called from within it. Progress is the
 * shared {@link LoadingSequenceProgress} timeline's current value — this screen has no phase
 * model of its own, it simply carries the bar across the gap without moving it backwards.</p>
 *
 * <p>Only themed during the join itself ({@link LoadingSequenceProgress#isJoining()}): the same
 * screen is reused for nether/end portal travel and dimension changes, where the vanilla visual
 * is deliberate. Train Builder worlds keep vanilla throughout, matching the world-load screen.</p>
 */
@Mixin(ReceivingLevelScreen.class)
public abstract class ReceivingLevelScreenThemeMixin {

    private static final int TIP_MAX_WIDTH = 260;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$renderThemed(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!LoadingSequenceProgress.isJoining() || BuilderWorldCheck.isBuilderWorld()) {
            return; // no ci.cancel() — vanilla renders
        }
        ReceivingLevelScreen self = (ReceivingLevelScreen) (Object) this;
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

        ci.cancel();
    }
}
