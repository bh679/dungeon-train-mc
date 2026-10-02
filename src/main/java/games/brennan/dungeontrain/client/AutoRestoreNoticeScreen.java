package games.brennan.dungeontrain.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * "Your data was restored" — shown once on the title screen after
 * {@link games.brennan.dungeontrain.data.AutoRestore} put a player's data back (see
 * {@link AutoRestoreNotice} for when).
 *
 * <p>A notice, not a choice: the restore has already happened, and it only ever adds. Same flat card
 * as {@link DpiBypassPromptScreen}, so every one-time message at the title screen looks like it came
 * from the same place. One button, and Esc does the same thing.</p>
 *
 * <p>Client-only — never class-loaded on a dedicated server.</p>
 */
public final class AutoRestoreNoticeScreen extends Screen {

    private static final String KEY_TITLE = "gui.dungeontrain.auto_restore.title";
    private static final String KEY_BODY = "gui.dungeontrain.auto_restore.body";

    // Flat card geometry — mirrors DpiBypassPromptScreen so the cards read as one family.
    private static final int CARD_W = 300;
    private static final int PAD = 14;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_W = 120;

    private static final int GAP_TITLE = 9;
    private static final int GAP_BODY = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;

    private final Screen previousScreen;
    private final int files;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private List<FormattedCharSequence> bodyLines = List.of();
    private int titleY;
    private int bodyY;

    public AutoRestoreNoticeScreen(Screen previousScreen, int files) {
        super(Component.translatable(KEY_TITLE)); // narration title
        this.previousScreen = previousScreen;
        this.files = files;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(Component.translatable(KEY_BODY, files), innerWidth);

        int contentH = font.lineHeight + GAP_TITLE + bodyLines.size() * LINE_STEP + GAP_BODY + BUTTON_H;

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

        addRenderableWidget(Button.builder(CommonComponents.GUI_OK, b -> onClose())
                .bounds(centerX - BUTTON_W / 2, cursor, BUTTON_W, BUTTON_H)
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

        graphics.drawCenteredString(font, Component.translatable(KEY_TITLE), centerX, titleY, COLOR_TITLE);

        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(previousScreen);
    }
}
