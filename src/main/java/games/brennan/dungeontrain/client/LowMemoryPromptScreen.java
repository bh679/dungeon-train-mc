package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.List;

/**
 * The "Minecraft is low on memory" notice, shown on the title screen at boot when
 * {@link LowMemoryNotice#shouldWarn} says the player has memory to spare but hasn't given it to the
 * game (see {@link LowMemoryNotice} for who is shown it and when).
 *
 * <p>Same flat card as {@link DpiBypassPromptScreen}, so every one-time message a player can meet at
 * the title screen looks like it came from the same place. A card rather than a toast: a toast
 * fired at boot runs its five seconds out behind the resource-loading splash, and this is advice
 * the player has to quit the game to act on — it should wait for them.</p>
 *
 * <p>A notice, not a choice: the fix lives in the player's launcher, not in the mod. "How to do it"
 * opens the wiki page (through vanilla's link confirmation); "Got it" and Esc just close it;
 * "Don't show again" also turns the warning off.</p>
 *
 * <p>Client-only — never class-loaded on a dedicated server.</p>
 */
public final class LowMemoryPromptScreen extends Screen {

    private static final String KEY_TITLE = "gui.dungeontrain.low_memory.title";
    private static final String KEY_BODY = "gui.dungeontrain.low_memory.body";
    private static final String KEY_FOOTNOTE = "gui.dungeontrain.low_memory.footnote";
    private static final String KEY_HOW = "gui.dungeontrain.low_memory.how";
    private static final String KEY_OK = "gui.dungeontrain.low_memory.ok";
    private static final String KEY_DONT_SHOW = "gui.dungeontrain.low_memory.dont_show";

    // Flat card geometry — mirrors DpiBypassPromptScreen so the cards read as one family.
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

    /** Heap as the player would say it, e.g. {@code "4"} — named in the body text. */
    private final String heapGb;

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

    public LowMemoryPromptScreen(Screen previousScreen, String heapGb) {
        super(Component.translatable(KEY_TITLE)); // narration title
        this.previousScreen = previousScreen;
        this.heapGb = heapGb;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(Component.translatable(KEY_BODY, heapGb), innerWidth);
        footnoteLines = font.split(Component.translatable(KEY_FOOTNOTE), innerWidth);

        int contentH = font.lineHeight + GAP_TITLE
                + bodyLines.size() * LINE_STEP + GAP_BODY
                + footnoteLines.size() * LINE_STEP + GAP_FOOTNOTE
                + BUTTON_H + BUTTON_GAP + BUTTON_H;

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

        // The helpful action gets the full-width row; the two ways out share the row below.
        int innerLeft = panelX + PAD;
        addRenderableWidget(Button.builder(Component.translatable(KEY_HOW), b -> openHowTo())
                .bounds(innerLeft, cursor, innerWidth, BUTTON_H)
                .build());
        cursor += BUTTON_H + BUTTON_GAP;
        int buttonW = (innerWidth - BUTTON_GAP) / 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY_OK), b -> dismiss(false))
                .bounds(innerLeft, cursor, buttonW, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_DONT_SHOW), b -> dismiss(true))
                .bounds(innerLeft + buttonW + BUTTON_GAP, cursor, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
                .build());
    }

    /** The player's own launcher's wiki page, behind vanilla's "open this link?" confirmation. */
    private void openHowTo() {
        String url = LowMemoryNotice.howToUrl();
        this.minecraft.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) Util.getPlatform().openUri(URI.create(url));
            this.minecraft.setScreen(this);
        }, url, true));
    }

    /** Close, optionally turning the low-memory warning off for good. */
    private void dismiss(boolean optOut) {
        if (optOut) ClientDisplayConfig.setLowMemoryNoticeChat(false);
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

    /** Esc closes without opting out — an unread notice is still unread. */
    @Override
    public void onClose() {
        dismiss(false);
    }
}
