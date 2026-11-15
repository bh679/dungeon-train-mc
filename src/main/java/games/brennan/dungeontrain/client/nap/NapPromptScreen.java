package games.brennan.dungeontrain.client.nap;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.NapResponsePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * "You feel comfortable here." The nap prompt a natural bed offers ({@code nap/NapBedEvents}), in its
 * daytime or night copy. Main Menu saves and quits; Stay awake / Wake up. (or Escape) carries on.
 */
public final class NapPromptScreen extends Screen {

    private static final int COLOUR_TITLE = 0xFFFFFFFF;
    private static final int COLOUR_BODY = 0xFFA0A0A0;
    private static final int TEXT_WIDTH = 300;
    private static final int BUTTON_W = 150;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int LINE_GAP = 2;
    private static final int SECTION_GAP = 14;

    private final String prefix;
    private boolean responded = false;
    private List<FormattedCharSequence> titleLines = List.of();
    private List<FormattedCharSequence> bodyLines = List.of();
    private int textTop;

    public NapPromptScreen(boolean night) {
        super(Component.translatable(keyPrefix(night) + "title"));
        this.prefix = keyPrefix(night);
    }

    private static String keyPrefix(boolean night) {
        return "gui.dungeontrain.nap." + (night ? "night." : "day.");
    }

    @Override
    protected void init() {
        int width = Math.min(TEXT_WIDTH, this.width - 40);
        titleLines = this.font.split(Component.translatable(prefix + "title")
            .append("\n").append(Component.translatable(prefix + "question")), width);
        bodyLines = this.font.split(Component.translatable(prefix + "body"), width);
        int lh = this.font.lineHeight + LINE_GAP;
        int blockH = titleLines.size() * lh + SECTION_GAP + bodyLines.size() * lh + SECTION_GAP + BUTTON_H;
        textTop = (this.height - blockH) / 2;
        int buttonY = textTop + blockH - BUTTON_H;
        int left = this.width / 2 - BUTTON_W - BUTTON_GAP / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.nap.main_menu"), b -> respond(true))
            .bounds(left, buttonY, BUTTON_W, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable(prefix + "decline"), b -> respond(false))
            .bounds(left + BUTTON_W + BUTTON_GAP, buttonY, BUTTON_W, BUTTON_H).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int lh = this.font.lineHeight + LINE_GAP;
        int y = textTop;
        for (FormattedCharSequence line : titleLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, COLOUR_TITLE);
            y += lh;
        }
        y += SECTION_GAP;
        for (FormattedCharSequence line : bodyLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, COLOUR_BODY);
            y += lh;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        respond(false);
    }

    /** Pushed aside by another screen without an answer: that is a no, so the server isn't left waiting. */
    @Override
    public void removed() {
        if (!responded) {
            responded = true;
            DungeonTrainNet.sendToServer(new NapResponsePacket(false));
        }
    }

    private void respond(boolean accepted) {
        if (responded) return;
        responded = true;
        DungeonTrainNet.sendToServer(new NapResponsePacket(accepted));
        // Main Menu waits for the server's NapSavedPacket to leave; until then the screen just closes.
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }
}
