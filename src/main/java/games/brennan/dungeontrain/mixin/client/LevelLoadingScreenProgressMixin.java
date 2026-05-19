package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.bootstrap.BootstrapProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.server.level.progress.StoringChunkProgressListener;

/**
 * Renders a Dungeon Train bootstrap progress line on the world-load screen
 * while {@link games.brennan.dungeontrain.train.TrainCarriageAppender#eagerFillForBootstrap}
 * is blocking the integrated-server thread. Reads from the shared
 * {@link BootstrapProgress} holder; renders nothing when no bootstrap
 * fill is active.
 *
 * <p>Placement: centred horizontally, below the vanilla chunk-progress
 * grid. Cell size 2 px × diameter (default 25) puts the grid bottom
 * at roughly {@code height/2 + 25}; we draw at {@code height/2 + 40} to
 * clear it cleanly with breathing room.</p>
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenProgressMixin {

    @Shadow @Final private StoringChunkProgressListener progressListener;

    @Inject(method = "render", at = @At("RETURN"))
    private void dungeontrain$drawBootstrapProgress(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci
    ) {
        if (!BootstrapProgress.isActive()) return;
        LevelLoadingScreen self = (LevelLoadingScreen) (Object) this;
        int centerX = self.width / 2;
        int centerY = self.height / 2;
        int gridHalfHeight = progressListener.getDiameter();
        int yBase = centerY + gridHalfHeight + 12;

        String phase = BootstrapProgress.phase();
        String text = BootstrapProgress.hasCount()
            ? phase + ": " + BootstrapProgress.current() + " / " + BootstrapProgress.total()
            : phase;

        Font font = Minecraft.getInstance().font;
        graphics.drawCenteredString(font, Component.literal(text), centerX, yBase, 0xFFFFFFFF);
    }
}
