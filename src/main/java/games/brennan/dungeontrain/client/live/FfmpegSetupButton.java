package games.brennan.dungeontrain.client.live;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The Stream tab's Download FFmpeg button ({@link FfmpegButtonLabel}): green while it can be pressed.
 * Re-reads the state every frame, so it counts the download up and greys out as Installed while the
 * screen stays open.
 */
public final class FfmpegSetupButton extends Button {

    public FfmpegSetupButton(int width, int height) {
        super(0, 0, width, height, Component.empty(), b -> FfmpegHint.startInstall(), DEFAULT_NARRATION);
        refresh();
    }

    /** This client has no FFmpeg and none on its way: the button is pressable, and leads the Stream tab. */
    public static boolean needed() {
        return current().active();
    }

    private static FfmpegButtonLabel current() {
        return FfmpegButtonLabel.of(FfmpegSupport.state(), FfmpegInstaller.running(), FfmpegSupport.downloadProgress());
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refresh();
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    private void refresh() {
        FfmpegButtonLabel label = current();
        MutableComponent message = label.percent() < 0
            ? Component.translatable(label.key())
            : Component.translatable(label.key(), label.percent());
        setMessage(label.active() ? message.withStyle(ChatFormatting.GREEN) : message);
        active = label.active();
    }
}
