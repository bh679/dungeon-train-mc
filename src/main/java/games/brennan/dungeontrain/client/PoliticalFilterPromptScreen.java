package games.brennan.dungeontrain.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The one-time Political Filter choice, shown on the title screen to players on a Chinese-language
 * client (see {@link PoliticalFilterPrefs} for who is asked and why).
 *
 * <p>A flat card in the same shape as Discord Presence's network-consent screen, so the two one-time
 * questions a player may meet on their first title screen look like they came from the same place.
 * Deliberately a CHOICE, not a warning: both buttons are equal-weight and neither answer is presented
 * as the correct one — the filter is about the player's own circumstances, not about the content
 * being unacceptable.</p>
 *
 * <p>Client-only — never class-loaded on a dedicated server.</p>
 */
public final class PoliticalFilterPromptScreen extends Screen {

    private static final String KEY_TITLE = "gui.dungeontrain.political_filter.title";
    private static final String KEY_BODY = "gui.dungeontrain.political_filter.body";
    private static final String KEY_FOOTNOTE = "gui.dungeontrain.political_filter.footnote";
    private static final String KEY_ENABLE = "gui.dungeontrain.political_filter.enable";
    private static final String KEY_SHOW_ALL = "gui.dungeontrain.political_filter.show_all";

    // Flat card geometry — mirrors DP's NetworkConsentScreen so the two cards read as one family.
    private static final int CARD_W = 300;
    private static final int PAD = 14;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;

    private static final int GAP_TITLE = 9;
    private static final int GAP_BODY = 10;
    private static final int GAP_FOOTNOTE = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_FOOTNOTE = 0xFF808080;

    private final Screen previousScreen;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> footnoteLines = List.of();
    private int titleY;
    private int bodyY;
    private int footnoteY;

    public PoliticalFilterPromptScreen(Screen previousScreen) {
        super(Component.translatable(KEY_TITLE)); // narration title
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(Component.translatable(KEY_BODY), innerWidth);
        footnoteLines = font.split(Component.translatable(KEY_FOOTNOTE), innerWidth);

        int contentH = font.lineHeight + GAP_TITLE
                + bodyLines.size() * LINE_STEP + GAP_BODY
                + footnoteLines.size() * LINE_STEP + GAP_FOOTNOTE
                + BUTTON_H;

        panelW = CARD_W;
        panelH = PAD + contentH + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(16, (height - panelH) / 2);
        centerX = panelX + panelW / 2;

        int cursor = panelY + PAD;
        titleY = cursor;
        cursor += font.lineHeight + GAP_TITLE;
        bodyY = cursor;
        cursor += bodyLines.size() * LINE_STEP + GAP_BODY;
        footnoteY = cursor;
        cursor += footnoteLines.size() * LINE_STEP + GAP_FOOTNOTE;

        int innerLeft = panelX + PAD;
        int buttonW = (innerWidth - BUTTON_GAP) / 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY_ENABLE), b -> answer(true))
                .bounds(innerLeft, cursor, buttonW, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_SHOW_ALL), b -> answer(false))
                .bounds(innerLeft + buttonW + BUTTON_GAP, cursor, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
                .build());
    }

    /** Persist the choice, push it to any connected server, and return to the title screen. */
    private void answer(boolean filterEnabled) {
        PoliticalFilterPrefs.answer(filterEnabled);
        this.minecraft.setScreen(previousScreen);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, BACKDROP_DIM);

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, CARD_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, CARD_BORDER);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, CARD_BORDER);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelH, CARD_BORDER);
        graphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, CARD_BORDER);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, Component.translatable(KEY_TITLE), centerX, titleY, COLOR_TITLE);

        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }

        int fy = footnoteY;
        for (FormattedCharSequence line : footnoteLines) {
            graphics.drawCenteredString(font, line, centerX, fy, COLOR_FOOTNOTE);
            fy += LINE_STEP;
        }
    }

    /**
     * Esc records the filter as ON. A dismissed prompt is still an ANSWER — leaving it UNSET would
     * re-ask on every title screen, and the one thing worse than asking once is nagging. ON is the
     * side to err on: it matches what an unanswered prompt already resolves to for this audience
     * ({@link PoliticalFilterPrefs#isEnabled()}), and it is one click to undo in
     * Options &gt; Dungeon Train, whereas content already served cannot be taken back.
     */
    @Override
    public void onClose() {
        answer(true);
    }
}
