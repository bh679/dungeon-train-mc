package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The one-time "connection blocker detected" notice, shown on the title screen when
 * {@link DpiBypassDetector} finds a DPI-bypass tool running (see {@link DpiBypassPromptHandler} for
 * who is shown it and when).
 *
 * <p>Same flat card as {@link PoliticalFilterPromptScreen}, so every one-time message a player can
 * meet at the title screen looks like it came from the same place.</p>
 *
 * <p>Unlike that one, this is a <b>notice, not a choice</b> — there is nothing here for the player
 * to decide, and the mod is in no position to decide anything either: it cannot turn the tool off,
 * and would have no business doing so if it could. Somebody running zapret is routing around a
 * national filter and has better reasons for it than a Minecraft mod has for objecting. So the copy
 * says what was seen, what it may cost, and what to do if it does — and stops there. Both buttons
 * dismiss; the second one just means "and stop telling me".</p>
 *
 * <p>Client-only — never class-loaded on a dedicated server.</p>
 */
public final class DpiBypassPromptScreen extends Screen {

    private static final String KEY_TITLE = "gui.dungeontrain.dpi_bypass.title";
    private static final String KEY_BODY = "gui.dungeontrain.dpi_bypass.body";
    private static final String KEY_FOOTNOTE = "gui.dungeontrain.dpi_bypass.footnote";
    private static final String KEY_OK = "gui.dungeontrain.dpi_bypass.ok";
    private static final String KEY_DONT_SHOW = "gui.dungeontrain.dpi_bypass.dont_show";

    // Flat card geometry — mirrors PoliticalFilterPromptScreen so the cards read as one family.
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

    /** The process name that was matched, e.g. {@code winws.exe} — named in the body text. */
    private final String toolName;

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

    public DpiBypassPromptScreen(Screen previousScreen, String toolName) {
        super(Component.translatable(KEY_TITLE)); // narration title
        this.previousScreen = previousScreen;
        this.toolName = toolName;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(Component.translatable(KEY_BODY, toolName), innerWidth);
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
        addRenderableWidget(Button.builder(Component.translatable(KEY_OK), b -> dismiss(false))
                .bounds(innerLeft, cursor, buttonW, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_DONT_SHOW), b -> dismiss(true))
                .bounds(innerLeft + buttonW + BUTTON_GAP, cursor, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
                .build());
    }

    /** Close, optionally recording that the notice should never appear again. */
    private void dismiss(boolean optOut) {
        if (optOut) ClientDisplayConfig.setDpiBypassWarningOptedOut(true);
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
     * Esc dismisses without opting out. Deliberately the softer of the two: an unread notice is
     * still unread, and silently signing someone up to never being told again — on the strength of
     * a keypress that in every other screen just means "back" — would take the information away
     * from the player least likely to have taken it in.
     */
    @Override
    public void onClose() {
        dismiss(false);
    }
}
