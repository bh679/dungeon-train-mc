package games.brennan.dungeontrain.client.version;

import games.brennan.dungeontrain.client.version.compare.FullSemver;
import games.brennan.dungeontrain.client.version.compare.Platform;
import games.brennan.dungeontrain.client.version.compare.UpdatePage;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * "A new version is out" — shown once at boot over the title screen when a newer real release is on
 * any launcher ({@link UpdatePromptHandler}). Deliberately minimal and launcher-agnostic: it names the
 * two versions and nothing else. "See what's new" opens DT's update page, which lists each launcher
 * and what changed ({@link UpdatePage}); the full Versions page stays on the title-screen version line.
 *
 * <p>Same flat card as {@link games.brennan.dungeontrain.client.LowMemoryPromptScreen}, so every
 * one-time title-screen message looks like it came from the same place.</p>
 *
 * <p>Client-only — never class-loaded on a dedicated server.</p>
 */
public final class UpdatePromptScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.update_prompt.";

    // Flat card geometry — mirrors LowMemoryPromptScreen so the cards read as one family.
    private static final int CARD_W = 260;
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
    private final FullSemver newest;
    private final FullSemver installed;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private List<FormattedCharSequence> bodyLines = List.of();
    private int titleY;
    private int bodyY;

    public UpdatePromptScreen(Screen previousScreen, FullSemver newest, FullSemver installed) {
        super(Component.translatable(KEY + "title")); // narration title
        this.previousScreen = previousScreen;
        this.newest = newest;
        this.installed = installed;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(Component.translatable(KEY + "body", newest.toString(), installed.toString()), innerWidth);

        int contentH = font.lineHeight + GAP_TITLE
                + bodyLines.size() * LINE_STEP + GAP_BODY
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

        int innerLeft = panelX + PAD;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "see"), b -> openPage())
                .bounds(innerLeft, cursor, innerWidth, BUTTON_H)
                .build());
        cursor += BUTTON_H + BUTTON_GAP;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "later"), b -> onClose())
                .bounds(innerLeft, cursor, innerWidth, BUTTON_H)
                .build());
    }

    /** DT's update page, behind vanilla's "open this link?" confirmation; back to this card either way. */
    private void openPage() {
        String url = UpdatePage.url(Optional.of(installed), Platform.current());
        this.minecraft.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) Util.getPlatform().openUri(URI.create(url));
            this.minecraft.setScreen(this);
        }, url, true));
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

        graphics.drawCenteredString(font, this.title, centerX, titleY, COLOR_TITLE);
        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }
    }

    /** "Later" and Esc both go back to the title screen. */
    @Override
    public void onClose() {
        this.minecraft.setScreen(previousScreen);
    }
}
