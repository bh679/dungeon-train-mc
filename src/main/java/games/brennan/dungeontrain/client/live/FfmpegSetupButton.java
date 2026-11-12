package games.brennan.dungeontrain.client.live;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The Stream tab's Download FFmpeg button ({@link FfmpegButtonLabel}). Re-reads the state every
 * frame, so it counts the download up and greys out as Installed while the screen stays open.
 */
public final class FfmpegSetupButton extends Button {

    public FfmpegSetupButton(int width, int height) {
        super(0, 0, width, height, Component.empty(), b -> FfmpegHint.startInstall(), DEFAULT_NARRATION);
        refresh();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refresh();
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    private void refresh() {
        FfmpegButtonLabel label = FfmpegButtonLabel.of(FfmpegSupport.state(), FfmpegInstaller.running(),
            FfmpegSupport.downloadProgress());
        setMessage(label.percent() < 0
            ? Component.translatable(label.key())
            : Component.translatable(label.key(), label.percent()));
        active = label.active();
    }
}
