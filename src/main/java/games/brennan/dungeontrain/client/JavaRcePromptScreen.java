package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.util.JavaRceCheck;
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
 * The Java security warning card (see {@link JavaRceNotice} for who sees it). Same flat card as
 * {@link LowMemoryPromptScreen}, so every title-screen notice reads as one family.
 *
 * <p>Leads with the fix that works on every Java today, the JVM argument, shown verbatim with a
 * Copy button and the one line of directions for the player's own launcher. Updating Java comes
 * second, because the launchers' bundled Java may not have a fixed build yet.</p>
 *
 * <p>Client-only: never class-loaded on a dedicated server.</p>
 */
public final class JavaRcePromptScreen extends Screen {

    private static final String KEY_TITLE = "gui.dungeontrain.java_rce.title";
    private static final String KEY_BODY = "gui.dungeontrain.java_rce.body";
    private static final String KEY_PASTE = "gui.dungeontrain.java_rce.paste";
    private static final String KEY_UPDATE ="gui.dungeontrain.java_rce.update";
    private static final String KEY_FOOTNOTE = "gui.dungeontrain.java_rce.footnote";
    private static final String KEY_COPY = "gui.dungeontrain.java_rce.copy";
    private static final String KEY_COPIED = "gui.dungeontrain.java_rce.copied";
    private static final String KEY_INFO = "gui.dungeontrain.java_rce.info";
    private static final String KEY_OK = "gui.dungeontrain.java_rce.ok";
    private static final String KEY_DONT_SHOW = "gui.dungeontrain.java_rce.dont_show";

    private static final int CARD_W = 320;
    private static final int PAD = 14;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;

    private static final int GAP_TITLE = 9;
    private static final int GAP_SECTION = 8;
    private static final int GAP_FOOTNOTE = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFF6B5B;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_ARG = 0xFFFFE066;
    private static final int COLOR_ARG_BG = 0xFF0E0E10;
    private static final int COLOR_FOOTNOTE = 0xFF808080;

    private final Screen previousScreen;
    private final String whereKey;
    private final String fixedVersion;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> whereLines = List.of();
    private List<FormattedCharSequence> updateLines = List.of();
    private List<FormattedCharSequence> footnoteLines = List.of();
    private int titleY;
    private int bodyY;
    private int argY;
    private int whereY;
    private int updateY;
    private int footnoteY;
    private Button copyButton;

    public JavaRcePromptScreen(Screen previousScreen, String whereKey, String fixedVersion) {
        super(Component.translatable(KEY_TITLE));
        this.previousScreen = previousScreen;
        this.whereKey = whereKey;
        this.fixedVersion = fixedVersion;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(Component.translatable(KEY_BODY), innerWidth);
        // Launcher paths keep the launchers' own (English) menu labels; the instruction around them is translated.
        whereLines = font.split(Component.translatable(whereKey).append(" ").append(Component.translatable(KEY_PASTE)), innerWidth);
        updateLines = font.split(Component.translatable(KEY_UPDATE, fixedVersion), innerWidth);
        footnoteLines = font.split(Component.translatable(KEY_FOOTNOTE), innerWidth);

        int argBoxH = font.lineHeight + 8;
        int contentH = font.lineHeight + GAP_TITLE
                + bodyLines.size() * LINE_STEP + GAP_SECTION
                + argBoxH + GAP_SECTION
                + whereLines.size() * LINE_STEP + GAP_SECTION
                + updateLines.size() * LINE_STEP + GAP_SECTION
                + footnoteLines.size() * LINE_STEP + GAP_FOOTNOTE
                + 3 * BUTTON_H + 2 * BUTTON_GAP;

        panelW = CARD_W;
        panelH = PAD + contentH + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(8, (height - panelH) / 2);
        centerX = panelX + panelW / 2;

        int cursor = panelY + PAD;
        titleY = cursor;
        cursor += font.lineHeight + GAP_TITLE;
        bodyY = cursor;
        cursor += bodyLines.size() * LINE_STEP + GAP_SECTION;
        argY = cursor;
        cursor += argBoxH + GAP_SECTION;
        whereY = cursor;
        cursor += whereLines.size() * LINE_STEP + GAP_SECTION;
        updateY = cursor;
        cursor += updateLines.size() * LINE_STEP + GAP_SECTION;
        footnoteY = cursor;
        cursor += footnoteLines.size() * LINE_STEP + GAP_FOOTNOTE;

        int innerLeft = panelX + PAD;
        copyButton = addRenderableWidget(Button.builder(Component.translatable(KEY_COPY), b -> copyArgument())
                .bounds(innerLeft, cursor, innerWidth, BUTTON_H)
                .build());
        cursor += BUTTON_H + BUTTON_GAP;
        int buttonW = (innerWidth - BUTTON_GAP) / 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY_INFO), b -> openInfo())
                .bounds(innerLeft, cursor, buttonW, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_OK), b -> dismiss(false))
                .bounds(innerLeft + buttonW + BUTTON_GAP, cursor, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
                .build());
        cursor += BUTTON_H + BUTTON_GAP;
        addRenderableWidget(Button.builder(Component.translatable(KEY_DONT_SHOW), b -> dismiss(true))
                .bounds(innerLeft, cursor, innerWidth, BUTTON_H)
                .build());
    }

    private void copyArgument() {
        this.minecraft.keyboardHandler.setClipboard(JavaRceCheck.JVM_ARGUMENT);
        copyButton.setMessage(Component.translatable(KEY_COPIED));
    }

    private void openInfo() {
        String url = JavaRceCheck.ADVISORY_URL;
        this.minecraft.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) Util.getPlatform().openUri(URI.create(url));
            this.minecraft.setScreen(this);
        }, url, true));
    }

    /** Close; "don't show again" remembers this Java, so another Java is warned afresh. */
    private void dismiss(boolean optOut) {
        if (optOut) ClientDisplayConfig.setJavaRceWarningDismissedFor(JavaRceNotice.runtimeVersion());
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

        int argW = font.width(JavaRceCheck.JVM_ARGUMENT) + 16;
        int argBoxH = font.lineHeight + 8;
        graphics.fill(centerX - argW / 2, argY, centerX + argW / 2, argY + argBoxH, COLOR_ARG_BG);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, Component.translatable(KEY_TITLE), centerX, titleY, COLOR_TITLE);
        drawLines(graphics, bodyLines, bodyY, COLOR_BODY);
        graphics.drawCenteredString(font, Component.literal(JavaRceCheck.JVM_ARGUMENT), centerX, argY + 4, COLOR_ARG);
        drawLines(graphics, whereLines, whereY, COLOR_BODY);
        drawLines(graphics, updateLines, updateY, COLOR_BODY);
        drawLines(graphics, footnoteLines, footnoteY, COLOR_FOOTNOTE);
    }

    private void drawLines(GuiGraphics graphics, List<FormattedCharSequence> lines, int y, int color) {
        for (FormattedCharSequence line : lines) {
            graphics.drawCenteredString(font, line, centerX, y, color);
            y += LINE_STEP;
        }
    }

    /** Esc is "Continue": an unread warning stays unread. */
    @Override
    public void onClose() {
        dismiss(false);
    }
}
