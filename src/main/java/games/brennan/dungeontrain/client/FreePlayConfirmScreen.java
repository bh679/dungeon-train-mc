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
 * Drawn as a centred bordered window (mirrors {@link DeveloperWelcomePopupScreen}'s
 * panel style). Continue starts Free Play and the held action is re-run
 * server-side; Cancel (or ESC) backs out and the run is untouched. A "Don't show
 * this again" checkbox persists to {@link ClientDisplayConfig} on Continue.
 */
public final class FreePlayConfirmScreen extends Screen {

    private static final int FREE_PLAY_TEAL = 0xFF5BC8C2;
    private static final int COLOUR_PANEL_BG = 0xFF1E1E1E;
    private static final int COLOUR_PANEL_BORDER = 0xFF3A3A3A;
    private static final int COLOUR_BODY = 0xFFE0E0E0;
    private static final int COLOUR_CONSEQ = 0xFFB8B8B8;
    private static final int COLOUR_TRIGGER = 0xFF8B8B8B;

    private static final int MAX_PANEL_W = 300;
    private static final int PADDING = 14;
    private static final int LINE_GAP = 2;
    private static final int SECTION_GAP = 8;
    private static final int BUTTON_H = 20;
    private static final int CHECKBOX_H = 20;
    private static final int BUTTON_GAP = 8;

    private final String triggerLabel;
    private Checkbox dontShowBox;
    private boolean responded = false;

    // Computed in init(), reused by render().
    private int panelX, panelY, panelW, panelH;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> conseq1 = List.of();
    private List<FormattedCharSequence> conseq2 = List.of();
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
        triggerLine = Component.translatable("gui.dungeontrain.free_play.confirm.trigger", triggerLabel)
                .getVisualOrderText();

        int textBlockH = lh                                          // title
                + SECTION_GAP + bodyLines.size() * (lh + LINE_GAP)   // body
                + SECTION_GAP + (conseq1.size() + conseq2.size()) * (lh + LINE_GAP)  // consequences
                + SECTION_GAP + lh;                                  // trigger
        panelH = PADDING + textBlockH + SECTION_GAP + CHECKBOX_H + SECTION_GAP + BUTTON_H + PADDING;

        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int buttonY = panelY + panelH - PADDING - BUTTON_H;
        int checkboxY = buttonY - SECTION_GAP - CHECKBOX_H;

        dontShowBox = Checkbox.builder(
                Component.translatable("gui.dungeontrain.free_play.confirm.dont_show"), this.font)
            .pos(panelX + PADDING, checkboxY)
            .selected(ClientDisplayConfig.isFreePlayConfirmOptedOut())
            .build();
        addRenderableWidget(dontShowBox);

        int buttonW = (innerW - BUTTON_GAP) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.free_play.confirm.continue"), b -> respond(true))
            .bounds(panelX + PADDING, buttonY, buttonW, BUTTON_H).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.free_play.confirm.cancel"), b -> respond(false))
            .bounds(panelX + PADDING + buttonW + BUTTON_GAP, buttonY, buttonW, BUTTON_H).build());
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
        // ESC / external close counts as backing out — answer once.
        if (!responded) {
            responded = true;
            DungeonTrainNet.sendToServer(new FreePlayConfirmResponsePacket(false));
        }
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // super.render() draws the dimmed background + the widgets (checkbox /
        // buttons render above the panel — same ordering as DeveloperWelcomePopupScreen).
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, COLOUR_PANEL_BORDER);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, COLOUR_PANEL_BG);

        int cx = panelX + panelW / 2;
        int lh = this.font.lineHeight;
        int y = panelY + PADDING;
        g.drawCenteredString(this.font, this.title, cx, y, FREE_PLAY_TEAL);
        y += lh + SECTION_GAP;
        for (FormattedCharSequence line : bodyLines) { g.drawCenteredString(this.font, line, cx, y, COLOUR_BODY); y += lh + LINE_GAP; }
        y += SECTION_GAP - LINE_GAP;
        for (FormattedCharSequence line : conseq1) { g.drawCenteredString(this.font, line, cx, y, COLOUR_CONSEQ); y += lh + LINE_GAP; }
        for (FormattedCharSequence line : conseq2) { g.drawCenteredString(this.font, line, cx, y, COLOUR_CONSEQ); y += lh + LINE_GAP; }
        y += SECTION_GAP - LINE_GAP;
        g.drawCenteredString(this.font, triggerLine, cx, y, COLOUR_TRIGGER);
    }
}
