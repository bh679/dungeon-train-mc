package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.bootstrap.BootstrapProgress;
import games.brennan.dungeontrain.client.LoadingScreenTheme;
import games.brennan.dungeontrain.client.LoadingSequenceProgress;
import games.brennan.dungeontrain.client.LoadingStories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla chunk-progress dot grid on {@link LevelLoadingScreen} with
 * the same themed train-fill animation as {@link games.brennan.dungeontrain.client.CinematicLoadingScreen}
 * (world-entry → spawn cinematic), so the whole load reads as one continuous,
 * themed screen instead of two unrelated ones. See {@link LoadingScreenTheme}.
 *
 * <p>Progress is a two-phase eased local estimate, mirroring the blend used by
 * {@link games.brennan.dungeontrain.client.CinematicPreloadGate}: an
 * indeterminate climb while the world is generating, then — once
 * {@link BootstrapProgress}'s carriage-placement phase starts (blocking the
 * integrated-server thread) — a determinate tail driven by its count, or a
 * further eased climb if that phase has no count. That local 0..1 estimate is
 * folded into the shared {@link LoadingSequenceProgress} timeline (along with
 * a shared animation clock for the smoke/pulse), so the bar and animation
 * continue smoothly into {@link games.brennan.dungeontrain.client.CinematicLoadingScreen}
 * instead of resetting at the handoff. Never reported as fully complete; the
 * screen simply closes whenever vanilla is done underneath it.</p>
 *
 * <p>{@code @Inject(at = HEAD, cancellable = true)} + {@code ci.cancel()} fully
 * replaces the vanilla render, the same pattern used by
 * {@link games.brennan.dungeontrain.mixin.client.MainMenuLogoMixin}.</p>
 *
 * <p>The {@link LoadingStories} line always owns the main status slot — the
 * {@link BootstrapProgress} phase text used to swap in there and interrupt the
 * story, so it now lives in its own line at the bottom of the screen, hidden
 * unless Space is held (polled directly via GLFW each frame, the same
 * held-key pattern used by {@code BlockVariantMenuInputHandler}).</p>
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenThemeMixin {

    /** Fraction of this screen's own local progress reserved for the indeterminate world-gen phase. */
    private static final double PHASE_A_CAP = 0.6;
    /** Eases-in rate (per second) for the world-gen phase. */
    private static final double PHASE_A_RATE = 0.4;
    /** Eases-in rate (per second) for the carriage-placement phase when it has no count. */
    private static final double PHASE_B_RATE = 0.5;
    private static final int TIP_MAX_WIDTH = 260;
    private static final int INFO_BOTTOM_MARGIN = 20;

    @Unique private long dungeontrain$phaseBStartNanos = -1L;
    @Unique private double dungeontrain$localFraction = 0.0;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$renderThemed(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        LevelLoadingScreen self = (LevelLoadingScreen) (Object) this;
        Font font = Minecraft.getInstance().font;

        LoadingScreenTheme.fillBackground(g, self.width, self.height);

        int cx = self.width / 2;
        int cy = self.height / 2;
        double progress = LoadingSequenceProgress.reportWorldLoad(dungeontrain$computeLocalProgress());
        long animNanos = LoadingSequenceProgress.animNanos();

        int railW = Math.min(LoadingScreenTheme.MAX_RAIL_W, self.width - 80);
        int railLeft = cx - railW / 2;
        int railY = cy + 8;

        LoadingScreenTheme.drawTitle(g, font, Component.translatable("gui.dungeontrain.loading.title"), cx, cy - 30);
        LoadingScreenTheme.drawFillingTrain(g, font, railLeft, railW, railY, progress, animNanos);
        LoadingScreenTheme.drawPercent(g, font, progress, cx, cy + 34);
        LoadingScreenTheme.drawTip(g, font, LoadingStories.currentLine(), cx, cy + 52, TIP_MAX_WIDTH);

        if (BootstrapProgress.isActive() && dungeontrain$isSpaceHeld()) {
            LoadingScreenTheme.drawTip(g, font, dungeontrain$bootstrapStatus(), cx, self.height - INFO_BOTTOM_MARGIN, TIP_MAX_WIDTH);
        }

        ci.cancel();
    }

    private boolean dungeontrain$isSpaceHeld() {
        return GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
    }

    private Component dungeontrain$bootstrapStatus() {
        // BootstrapProgress.phase() holds a translation key — localize it client-side so the
        // loading-screen phase label follows the player's selected language.
        MutableComponent status = Component.translatable(BootstrapProgress.phase());
        if (BootstrapProgress.hasCount()) {
            status.append(Component.literal(": " + BootstrapProgress.current() + " / " + BootstrapProgress.total()));
        }
        return status;
    }

    /** This screen's own 0..1 progress estimate — folded into the shared timeline by the caller. */
    private double dungeontrain$computeLocalProgress() {
        long now = System.nanoTime();
        double elapsedSec = LoadingSequenceProgress.animNanos() / 1.0e9;
        double phaseA = PHASE_A_CAP * (1.0 - Math.exp(-PHASE_A_RATE * elapsedSec));

        double target;
        if (BootstrapProgress.isActive()) {
            if (dungeontrain$phaseBStartNanos < 0) {
                dungeontrain$phaseBStartNanos = now;
            }
            if (BootstrapProgress.hasCount()) {
                int total = BootstrapProgress.total();
                double frac = total <= 0 ? 0.0
                    : Mth.clamp(BootstrapProgress.current() / (double) total, 0.0, 1.0);
                target = PHASE_A_CAP + (1.0 - PHASE_A_CAP) * frac;
            } else {
                double elapsedB = (now - dungeontrain$phaseBStartNanos) / 1.0e9;
                double easeB = 1.0 - Math.exp(-PHASE_B_RATE * elapsedB);
                target = PHASE_A_CAP + (1.0 - PHASE_A_CAP) * 0.9 * easeB;
            }
        } else {
            dungeontrain$phaseBStartNanos = -1L;
            target = phaseA;
        }

        dungeontrain$localFraction = Math.max(dungeontrain$localFraction, Math.min(target, 0.99));
        return dungeontrain$localFraction;
    }
}
