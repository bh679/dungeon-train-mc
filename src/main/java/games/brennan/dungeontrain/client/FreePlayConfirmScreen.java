package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.FreePlayConfirmResponsePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * "Enter Free Play?" — a soft, non-judgemental confirmation shown before a
 * tainting action (a creative/spectator switch or a cheat command) commits.
 *
 * <p>Drawn as a centred, narrow window using the vanilla tooltip frame
 * (dark fill + purple gradient border) so it matches the in-game effect
 * tooltip and reads as native Minecraft UI. Continue starts Free Play and the
 * held action is re-run server-side; Cancel (or ESC) backs out, run untouched.
 * A "Don't show this again" checkbox persists to {@link ClientDisplayConfig}.</p>
 */
public final class FreePlayConfirmScreen extends Screen {

    // Vanilla tooltip palette (matches the effect hover tooltip's frame).
    private static final int FRAME_BG = 0xF0100010;
    private static final int FRAME_BORDER_TOP = 0x505000FF;
    private static final int FRAME_BORDER_BOTTOM = 0x5028007F;

    private static final int TITLE_TEAL = 0xFF5BC8C2;
    private static final int COLOUR_BODY = 0xFFE0E0E0;
    private static final int COLOUR_CONSEQ = 0xFFB8B8B8;
    private static final int COLOUR_TRIGGER = 0xFF7E7E8C;
    private static final int COLOUR_SEPARATOR = 0x40FFFFFF;

    private static final int MAX_PANEL_W = 220;   // narrow → short, readable lines
    private static final int PADDING = 12;
    private static final int LINE_GAP = 1;
    private static final int SECTION_GAP = 7;
    private static final int TITLE_SEP_GAP = 6;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 4;
    private static final int CHECKBOX_H = 20;

    private final String triggerLabel;
    private Checkbox dontShowBox;
    private boolean responded = false;

    // Layout, computed in init() and reused by render().
    private int panelX, panelY, panelW, panelH;
    private int titleRelY, bodyRelY, desc1RelY, desc2RelY, desc3RelY, triggerRelY;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> conseq1 = List.of();
    private List<FormattedCharSequence> conseq2 = List.of();
    private List<FormattedCharSequence> conseq3 = List.of();
    private FormattedCharSequence triggerLine = FormattedCharSequence.EMPTY;

    public FreePlayConfirmScreen(String triggerLabel) {
        super(Component.translatable("gui.dungeontrain.free_play.confirm.title"));
        this.triggerLabel = triggerLabel;
    }

    @Override
    protected void init() {
        panelW = Math.min(MAX_PANEL_W, this.width - 40);
        int innerW = panelW - 2 * PADDING;
        int lh = this.font.lineHeight;

        bodyLines = this.font.split(Component.translatable("gui.dungeontrain.free_play.confirm.body"), innerW);
        conseq1 = this.font.split(Component.translatable("effect.dungeontrain.free_play.desc.1"), innerW);
        conseq2 = this.font.split(Component.translatable("effect.dungeontrain.free_play.desc.2"), innerW);
        conseq3 = this.font.split(Component.translatable("effect.dungeontrain.free_play.desc.3"), innerW);
        triggerLine = Component.translatable("gui.dungeontrain.free_play.confirm.trigger", triggerLabel)
                .getVisualOrderText();

        int y = PADDING;
        titleRelY = y;   y += lh + TITLE_SEP_GAP;
        bodyRelY = y;    y += bodyLines.size() * (lh + LINE_GAP) + SECTION_GAP;
        desc1RelY = y;   y += conseq1.size() * (lh + LINE_GAP);
        desc2RelY = y;   y += conseq2.size() * (lh + LINE_GAP);
        desc3RelY = y;   y += conseq3.size() * (lh + LINE_GAP) + SECTION_GAP;
        triggerRelY = y; y += lh + SECTION_GAP;
        int checkboxRelY = y; y += CHECKBOX_H + SECTION_GAP;
        int continueRelY = y; y += BUTTON_H + BUTTON_GAP;
        int cancelRelY = y;   y += BUTTON_H + PADDING;
        panelH = y;

        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        dontShowBox = Checkbox.builder(
                Component.translatable("gui.dungeontrain.free_play.confirm.dont_show"), this.font)
            .pos(panelX + PADDING, panelY + checkboxRelY)
            .selected(ClientDisplayConfig.isFreePlayConfirmOptedOut())
            .build();
        addRenderableWidget(dontShowBox);

        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.free_play.confirm.continue"), b -> respond(true))
            .bounds(panelX + PADDING, panelY + continueRelY, innerW, BUTTON_H).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.free_play.confirm.cancel"), b -> respond(false))
            .bounds(panelX + PADDING, panelY + cancelRelY, innerW, BUTTON_H).build());
    }

    private void respond(boolean confirmed) {
        if (responded) return;
        responded = true;
        if (confirmed && dontShowBox != null && dontShowBox.selected()) {
            ClientDisplayConfig.setFreePlayConfirmOptedOut(true);
        }
        DungeonTrainNet.sendToServer(new FreePlayConfirmResponsePacket(confirmed));
        onClose();
    }

    @Override
    public void onClose() {
        if (!responded) {
            responded = true;
            DungeonTrainNet.sendToServer(new FreePlayConfirmResponsePacket(false));
        }
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // super.render() dims the world behind and draws the widgets above the
        // panel (same ordering as DeveloperWelcomePopupScreen).
        super.render(g, mouseX, mouseY, partialTick);
        drawFrame(g, panelX, panelY, panelX + panelW, panelY + panelH);

        int cx = panelX + panelW / 2;
        int lh = this.font.lineHeight;

        g.drawCenteredString(this.font, this.title, cx, panelY + titleRelY, TITLE_TEAL);
        int sepY = panelY + titleRelY + lh + 2;
        g.fill(panelX + 10, sepY, panelX + panelW - 10, sepY + 1, COLOUR_SEPARATOR);

        int y = panelY + bodyRelY;
        for (FormattedCharSequence line : bodyLines) { g.drawCenteredString(this.font, line, cx, y, COLOUR_BODY); y += lh + LINE_GAP; }
        y = panelY + desc1RelY;
        for (FormattedCharSequence line : conseq1) { g.drawCenteredString(this.font, line, cx, y, COLOUR_CONSEQ); y += lh + LINE_GAP; }
        y = panelY + desc2RelY;
        for (FormattedCharSequence line : conseq2) { g.drawCenteredString(this.font, line, cx, y, COLOUR_CONSEQ); y += lh + LINE_GAP; }
        y = panelY + desc3RelY;
        for (FormattedCharSequence line : conseq3) { g.drawCenteredString(this.font, line, cx, y, COLOUR_CONSEQ); y += lh + LINE_GAP; }
        g.drawCenteredString(this.font, triggerLine, cx, panelY + triggerRelY, COLOUR_TRIGGER);
    }

    /** Vanilla tooltip-style frame: dark fill + purple gradient border. */
    private static void drawFrame(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, FRAME_BG);
        g.fill(x0, y0, x1, y0 + 1, FRAME_BORDER_TOP);                  // top
        g.fill(x0, y1 - 1, x1, y1, FRAME_BORDER_BOTTOM);              // bottom
        g.fillGradient(x0, y0 + 1, x0 + 1, y1 - 1, FRAME_BORDER_TOP, FRAME_BORDER_BOTTOM);   // left
        g.fillGradient(x1 - 1, y0 + 1, x1, y1 - 1, FRAME_BORDER_TOP, FRAME_BORDER_BOTTOM);   // right
    }
}
