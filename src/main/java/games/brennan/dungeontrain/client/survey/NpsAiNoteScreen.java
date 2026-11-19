package games.brennan.dungeontrain.client.survey;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.policy.AiPolicyScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Brennan's note to a player who scored the game 0-4 and mentioned AI ({@link AiMentionDetector}):
 * who made the game, who writes the stories, and a way into the {@link AiPolicyScreen}.
 *
 * <p>Same flat card as {@link games.brennan.dungeontrain.client.moty.ModOfTheYearPopupScreen}, so
 * every one-time message looks like it came from the same place. The policy returns here, and
 * "Close" and Esc return to the survey underneath.</p>
 */
public final class NpsAiNoteScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.nps_ai_note.";
    private static final String TARGET_NOTE = "note";
    private static final String TARGET_POLICY = "policy";
    private static final String TARGET_CLOSE = "close";

    private static final int CARD_W = 280;
    private static final int PAD = 14;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int GAP_TITLE = 9;
    private static final int GAP_PARAGRAPH = 6;
    private static final int GAP_BODY = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;

    private final Screen parent;
    // The policy can return here more than once; "Close" and Esc count once however the card is left.
    private boolean closed;

    private int panelX;
    private int panelY;
    private int panelH;
    private int titleY;
    private int bodyY;
    private List<List<FormattedCharSequence>> paragraphs = List.of();

    public NpsAiNoteScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        UiAnalytics.shown(UiAnalytics.SURFACE_NPS_AI_NOTE, TARGET_NOTE);
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        List<List<FormattedCharSequence>> split = new ArrayList<>();
        for (String part : List.of("body.1", "body.2", "signature")) {
            split.add(font.split(Component.translatable(KEY + part), innerWidth));
        }
        paragraphs = List.copyOf(split);

        int bodyH = GAP_PARAGRAPH * (paragraphs.size() - 1);
        for (List<FormattedCharSequence> lines : paragraphs) bodyH += lines.size() * LINE_STEP;
        panelH = PAD + font.lineHeight + GAP_TITLE + bodyH + GAP_BODY + BUTTON_H + PAD;
        panelX = (width - CARD_W) / 2;
        panelY = Math.max(16, (height - panelH) / 2);
        titleY = panelY + PAD;
        bodyY = titleY + font.lineHeight + GAP_TITLE;

        int buttonY = bodyY + bodyH + GAP_BODY;
        int innerLeft = panelX + PAD;
        int buttonW = (innerWidth - BUTTON_GAP) / 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "button.policy"), b -> openPolicy())
                .bounds(innerLeft, buttonY, buttonW, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY + "button.close"), b -> onClose())
                .bounds(innerLeft + buttonW + BUTTON_GAP, buttonY, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
                .build());
    }

    private void openPolicy() {
        UiAnalytics.click(UiAnalytics.SURFACE_NPS_AI_NOTE, TARGET_POLICY);
        this.minecraft.setScreen(new AiPolicyScreen(this));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, BACKDROP_DIM);

        int panelRight = panelX + CARD_W;
        int panelBottom = panelY + panelH;
        graphics.fill(panelX, panelY, panelRight, panelBottom, CARD_BG);
        graphics.fill(panelX, panelY, panelRight, panelY + 1, CARD_BORDER);
        graphics.fill(panelX, panelBottom - 1, panelRight, panelBottom, CARD_BORDER);
        graphics.fill(panelX, panelY, panelX + 1, panelBottom, CARD_BORDER);
        graphics.fill(panelRight - 1, panelY, panelRight, panelBottom, CARD_BORDER);

        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = panelX + CARD_W / 2;
        graphics.drawCenteredString(font, this.title, centerX, titleY, COLOR_TITLE);
        int y = bodyY;
        for (List<FormattedCharSequence> lines : paragraphs) {
            for (FormattedCharSequence line : lines) {
                graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
                y += LINE_STEP;
            }
            y += GAP_PARAGRAPH;
        }
    }

    @Override
    public void onClose() {
        if (!closed) {
            closed = true;
            UiAnalytics.click(UiAnalytics.SURFACE_NPS_AI_NOTE, TARGET_CLOSE);
        }
        this.minecraft.setScreen(parent);
    }
}
