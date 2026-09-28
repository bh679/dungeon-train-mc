package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.cheat.FarmersDelightSoupStacking;
import games.brennan.dungeontrain.cheat.FreePlayText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Asked when the player switches Farmers' Delight's stackable soups back on from a config menu:
 * the switch has already been undone, and this states the trade before they commit to it.
 *
 * <p>"Keep soups unstackable" is the safe answer and what Esc does. "Turn on anyway" writes the
 * setting and the run is Free Play — see {@link FarmersDelightSoupStacking}. Same flat card as
 * {@link ConfigDeviationScreen}. Client-only.</p>
 */
public final class SoupStackingConfirmScreen extends Screen {

    private static final String KEY_TITLE = "gui.dungeontrain.soup_stacking.title";
    private static final String KEY_BODY = "gui.dungeontrain.soup_stacking.body";
    private static final String KEY_KEEP_OFF = "gui.dungeontrain.soup_stacking.keep_off";
    private static final String KEY_ENABLE = "gui.dungeontrain.soup_stacking.enable";

    private static final int CARD_W = 300;
    private static final int PAD = 14;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int GAP_TITLE = 9;
    private static final int GAP_BODY = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;

    private final Screen previousScreen;

    private int panelX;
    private int panelY;
    private int panelH;
    private int centerX;
    private int titleY;
    private int bodyY;
    private List<FormattedCharSequence> bodyLines = List.of();

    public SoupStackingConfirmScreen(Screen previousScreen) {
        super(Component.translatable(KEY_TITLE));
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(FreePlayText.withExplanation(KEY_BODY), innerWidth);
        int contentH = font.lineHeight + GAP_TITLE + bodyLines.size() * LINE_STEP + GAP_BODY + BUTTON_H;
        panelH = PAD + contentH + PAD;
        panelX = (width - CARD_W) / 2;
        panelY = Math.max(16, (height - panelH) / 2);
        centerX = panelX + CARD_W / 2;
        titleY = panelY + PAD;
        bodyY = titleY + font.lineHeight + GAP_TITLE;
        int buttonsY = bodyY + bodyLines.size() * LINE_STEP + GAP_BODY;

        int innerLeft = panelX + PAD;
        int buttonW = (innerWidth - BUTTON_GAP) / 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY_KEEP_OFF), b -> close())
                .bounds(innerLeft, buttonsY, buttonW, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_ENABLE), b -> enable())
                .bounds(innerLeft + buttonW + BUTTON_GAP, buttonsY, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
                .build());
    }

    private void enable() {
        FarmersDelightSoupStacking.enableAfterConfirmation();
        // They have just accepted this exact change — don't ask again about it at the next launch.
        ConfigDeviationPromptHandler.acknowledgeConfirmedChange();
        close();
    }

    private void close() {
        this.minecraft.setScreen(previousScreen);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, BACKDROP_DIM);
        int right = panelX + CARD_W;
        int bottom = panelY + panelH;
        graphics.fill(panelX, panelY, right, bottom, CARD_BG);
        graphics.fill(panelX, panelY, right, panelY + 1, CARD_BORDER);
        graphics.fill(panelX, bottom - 1, right, bottom, CARD_BORDER);
        graphics.fill(panelX, panelY, panelX + 1, bottom, CARD_BORDER);
        graphics.fill(right - 1, panelY, right, bottom, CARD_BORDER);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, this.title, centerX, titleY, COLOR_TITLE);
        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }
    }

    /** Esc keeps soups unstackable — the safe answer, never a silent yes. */
    @Override
    public void onClose() {
        close();
    }
}
