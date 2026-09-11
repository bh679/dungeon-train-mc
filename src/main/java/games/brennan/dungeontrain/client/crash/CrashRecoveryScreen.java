package games.brennan.dungeontrain.client.crash;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.CrashRunState;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;

import java.util.List;

/**
 * "Your last run crashed" — the offer to go back into that world for one purpose: reach an Ender
 * Chest and stash what you can, because that is the one thing that carries to the next run.
 *
 * <p>The card is honest about the trade: a world saved mid-crash is not one to keep playing. The
 * body says it will be buggy, says what to do (Ender Chest), and says what to do after (start
 * fresh). In the world itself {@link SalvageRunHudOverlay} keeps saying it and the pause menu
 * carries a "Start a Fresh Run" button.</p>
 *
 * <p>Same flat card as {@code DataRecoveryScreen} and {@code ConfigDeviationScreen}, so every
 * one-time question on the title screen looks like it came from the same place. Client-only.</p>
 */
public final class CrashRecoveryScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String KEY_TITLE = "gui.dungeontrain.crash_recovery.title";
    static final String KEY_WORLD = "gui.dungeontrain.crash_recovery.world";
    static final String KEY_BODY = "gui.dungeontrain.crash_recovery.body";
    static final String KEY_WARNING = "gui.dungeontrain.crash_recovery.warning";
    static final String KEY_SALVAGE = "gui.dungeontrain.crash_recovery.salvage";
    static final String KEY_SALVAGE_TOOLTIP = "gui.dungeontrain.crash_recovery.salvage.tooltip";
    static final String KEY_LATER = "gui.dungeontrain.crash_recovery.later";
    static final String KEY_DISCARD = "gui.dungeontrain.crash_recovery.discard";
    static final String KEY_DISCARD_TOOLTIP = "gui.dungeontrain.crash_recovery.discard.tooltip";
    static final String KEY_OPEN_FAILED = "gui.dungeontrain.crash_recovery.open_failed";

    private static final int CARD_W = 300;
    private static final int PAD = 14;
    private static final int MARGIN = 16;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int GAP_TITLE = 9;
    private static final int GAP_PARAGRAPH = 6;
    private static final int GAP_BUTTONS = 10;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_WORLD = 0xFF9A9AA2;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_WARNING = 0xFFF0B45A;

    private final Screen previousScreen;
    private final CrashRunState.RunState run;

    /** Set when the world failed to open, so the card re-lays itself out with that message. */
    private boolean openFailed = false;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private int titleY;
    private int worldY;
    private int bodyY;
    private int warningY;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> warningLines = List.of();

    public CrashRecoveryScreen(Screen previousScreen, CrashRunState.RunState run) {
        super(Component.translatable(KEY_TITLE)); // narration title
        this.previousScreen = previousScreen;
        this.run = run;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(Component.translatable(openFailed ? KEY_OPEN_FAILED : KEY_BODY), innerWidth);
        warningLines = openFailed ? List.of() : font.split(Component.translatable(KEY_WARNING), innerWidth);

        int textH = font.lineHeight + GAP_TITLE + font.lineHeight + GAP_PARAGRAPH
                + bodyLines.size() * LINE_STEP
                + (warningLines.isEmpty() ? 0 : GAP_PARAGRAPH + warningLines.size() * LINE_STEP);
        panelW = CARD_W;
        panelH = Math.min(PAD + textH + GAP_BUTTONS + BUTTON_H + PAD, height - 2 * MARGIN);
        panelX = (width - panelW) / 2;
        panelY = Math.max(MARGIN, (height - panelH) / 2);
        centerX = panelX + panelW / 2;

        int cursor = panelY + PAD;
        titleY = cursor;
        cursor += font.lineHeight + GAP_TITLE;
        worldY = cursor;
        cursor += font.lineHeight + GAP_PARAGRAPH;
        bodyY = cursor;
        cursor += bodyLines.size() * LINE_STEP + GAP_PARAGRAPH;
        warningY = cursor;

        int buttonsTop = panelY + panelH - PAD - BUTTON_H;
        int innerLeft = panelX + PAD;
        if (openFailed) {
            addRenderableWidget(Button.builder(Component.translatable(KEY_LATER), b -> close())
                    .bounds(innerLeft, buttonsTop, innerWidth, BUTTON_H)
                    .build());
            return;
        }
        int third = (innerWidth - 2 * BUTTON_GAP) / 3;
        addRenderableWidget(Button.builder(Component.translatable(KEY_SALVAGE), b -> salvage())
                .bounds(innerLeft, buttonsTop, third, BUTTON_H)
                .tooltip(Tooltip.create(Component.translatable(KEY_SALVAGE_TOOLTIP)))
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_LATER), b -> close())
                .bounds(innerLeft + third + BUTTON_GAP, buttonsTop, third, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_DISCARD), b -> discard())
                .bounds(innerLeft + 2 * (third + BUTTON_GAP), buttonsTop,
                        innerWidth - 2 * (third + BUTTON_GAP), BUTTON_H)
                .tooltip(Tooltip.create(Component.translatable(KEY_DISCARD_TOOLTIP)))
                .build());
    }

    /**
     * Back into the world. The record is flipped to {@code SALVAGING} <em>before</em> the open so
     * {@link CrashRunTracker} recognises the session on login and doesn't re-mark it as a fresh run.
     */
    private void salvage() {
        CrashRunState.write(PlayerDataPaths.root(), run.salvaging());
        LOGGER.info("[DungeonTrain] Crash recovery: reopening '{}' for salvage.", run.levelId());
        WorldOpenFlows flows = this.minecraft.createWorldOpenFlows();
        flows.openWorld(run.levelId(), () -> {
            LOGGER.warn("[DungeonTrain] Crash recovery: world '{}' failed to open.", run.levelId());
            openFailed = true;
            this.minecraft.setScreen(this);
        });
    }

    /** Forget the run. The save is left alone — only the offer goes. */
    private void discard() {
        CrashRunState.clear(PlayerDataPaths.root());
        LOGGER.info("[DungeonTrain] Crash recovery: player discarded the crashed run '{}'.", run.levelId());
        close();
    }

    private void close() {
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
        graphics.drawCenteredString(font, Component.translatable(KEY_WORLD, run.worldName()), centerX, worldY, COLOR_WORLD);

        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }
        y = warningY;
        for (FormattedCharSequence line : warningLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_WARNING);
            y += LINE_STEP;
        }
    }

    /** Esc means "not now" — the offer returns next launch. Only Discard forgets the run. */
    @Override
    public void onClose() {
        close();
    }
}
