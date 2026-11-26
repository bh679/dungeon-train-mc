package games.brennan.dungeontrain.client.crash;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * "Your last run crashed", after the run was saved as a nap ({@link CrashNapRecovery}): it says the
 * inventory and the place on the train were kept, and that Reboard carries on. One button, which only
 * closes the card; the player comes back in through the title screen's usual Reboard.
 *
 * <p>Same flat card as {@link CrashRecoveryScreen}. Client-only.</p>
 */
public final class CrashRecoveredScreen extends Screen {

    static final String KEY_BODY = "gui.dungeontrain.crash_recovery.recovered.body";
    static final String KEY_OK = "gui.dungeontrain.crash_recovery.recovered.ok";

    private static final int CARD_W = 300;
    private static final int PAD = 14;
    private static final int MARGIN = 16;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int GAP_TITLE = 9;
    private static final int GAP_PARAGRAPH = 6;
    private static final int GAP_BUTTONS = 10;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_WORLD = 0xFF9A9AA2;
    private static final int COLOR_BODY = 0xFFE0E0E0;

    private final Screen previousScreen;
    private final String worldName;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private int titleY;
    private int worldY;
    private int bodyY;
    private List<FormattedCharSequence> bodyLines = List.of();

    public CrashRecoveredScreen(Screen previousScreen, String worldName) {
        super(Component.translatable(CrashRecoveryScreen.KEY_TITLE)); // narration title
        this.previousScreen = previousScreen;
        this.worldName = worldName;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(Component.translatable(KEY_BODY), innerWidth);
        int textH = font.lineHeight + GAP_TITLE + font.lineHeight + GAP_PARAGRAPH + bodyLines.size() * LINE_STEP;
        panelW = CARD_W;
        panelH = Math.min(PAD + textH + GAP_BUTTONS + BUTTON_H + PAD, height - 2 * MARGIN);
        panelX = (width - panelW) / 2;
        panelY = Math.max(MARGIN, (height - panelH) / 2);
        centerX = panelX + panelW / 2;
        titleY = panelY + PAD;
        worldY = titleY + font.lineHeight + GAP_TITLE;
        bodyY = worldY + font.lineHeight + GAP_PARAGRAPH;
        int buttonsTop = panelY + panelH - PAD - BUTTON_H;
        addRenderableWidget(Button.builder(Component.translatable(KEY_OK), b -> onClose())
                .bounds(panelX + PAD, buttonsTop, innerWidth, BUTTON_H)
                .build());
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

        graphics.drawCenteredString(font, Component.translatable(CrashRecoveryScreen.KEY_TITLE), centerX, titleY, COLOR_TITLE);
        graphics.drawCenteredString(font, Component.translatable(CrashRecoveryScreen.KEY_WORLD, worldName),
                centerX, worldY, COLOR_WORLD);
        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }
    }

    /**
     * Back to a fresh title screen rather than the one underneath: its New World button was built
     * before the nap existed, and a returning screen only repositions its widgets, so it would still
     * read "New World" instead of "Reboard".
     */
    @Override
    public void onClose() {
        this.minecraft.setScreen(previousScreen instanceof TitleScreen ? new TitleScreen(false) : previousScreen);
    }
}
