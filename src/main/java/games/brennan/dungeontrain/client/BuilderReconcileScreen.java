package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.builder.BuilderReconcileRunner;
import games.brennan.dungeontrain.client.builder.BuilderReconcileScan;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Offers to put back builds the relay has lost.
 *
 * <p>Shown once a session at the title screen, when the relay turns out not to have builds this
 * install does. The usual cause is the pool's ring eviction: builds go up, the pool fills, and the
 * oldest rows are deleted with no notice to anyone.</p>
 *
 * <p>Two things the card has to be honest about, because both surprise people. A restored build comes
 * back <b>private</b> — it is a new row, and the old row's published state and reviews went with it.
 * And builds whose file is gone from disk as well can only come from a backup, which may equally mean
 * the player deleted them on purpose; that tier is a separate, off-by-default toggle rather than part
 * of the main button.</p>
 *
 * <p>Same flat card as {@link DataRecoveryScreen}, and beside it in the title screen's queue, so a
 * one-time question looks like a one-time question wherever it appears. Client-only.</p>
 */
public final class BuilderReconcileScreen extends Screen {

    private static final String KEY_TITLE = "gui.dungeontrain.builder.reconcile.title";
    private static final String KEY_BODY = "gui.dungeontrain.builder.reconcile.body";
    private static final String KEY_PRIVATE = "gui.dungeontrain.builder.reconcile.private";
    private static final String KEY_BACKUPS_ON = "gui.dungeontrain.builder.reconcile.backups.on";
    private static final String KEY_BACKUPS_OFF = "gui.dungeontrain.builder.reconcile.backups.off";
    private static final String KEY_BACKUPS_TOOLTIP = "gui.dungeontrain.builder.reconcile.backups.tooltip";
    private static final String KEY_RESTORE = "gui.dungeontrain.builder.reconcile.restore";
    private static final String KEY_LATER = "gui.dungeontrain.builder.reconcile.later";
    private static final String KEY_NEVER = "gui.dungeontrain.builder.reconcile.never";

    private static final int CARD_W = 300;
    private static final int PAD = 14;
    private static final int MARGIN = 16;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int GAP_TITLE = 9;
    private static final int GAP_LIST = 10;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_NOTE = 0xFF9A9AA2;

    private final Screen previousScreen;
    private final BuilderReconcileScan.Result scan;
    private final int onDisk;
    private final int backupOnly;

    /** Whether the second tier is included. Off unless the player says otherwise — see the class note. */
    private boolean includeBackups = false;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private int titleY;
    private int bodyY;
    private List<FormattedCharSequence> bodyLines = List.of();
    /** Index in {@link #bodyLines} where the greyed "comes back private" note starts. */
    private int noteFrom = 0;

    public BuilderReconcileScreen(Screen previousScreen, BuilderReconcileScan.Result scan) {
        super(Component.translatable(KEY_TITLE)); // narration title
        this.previousScreen = previousScreen;
        this.scan = scan;
        this.onDisk = scan.onDisk().size();
        this.backupOnly = scan.inBackups().size();
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.addAll(font.split(Component.translatable(KEY_BODY, restorable()), innerWidth));
        noteFrom = lines.size();
        lines.addAll(font.split(Component.translatable(KEY_PRIVATE), innerWidth));
        bodyLines = List.copyOf(lines);

        boolean offerBackups = backupOnly > 0;
        int buttonsH = (offerBackups ? BUTTON_H + BUTTON_GAP : 0) + BUTTON_H;
        int headerH = font.lineHeight + GAP_TITLE + bodyLines.size() * LINE_STEP;

        panelW = CARD_W;
        // Clamped to the window like the recovery card, for the same reason: the buttons must stay on
        // screen whatever the body's wrapped height turns out to be.
        panelH = Math.min(PAD + headerH + GAP_LIST + buttonsH + PAD, height - 2 * MARGIN);
        panelX = (width - panelW) / 2;
        panelY = Math.max(MARGIN, (height - panelH) / 2);
        centerX = panelX + panelW / 2;

        titleY = panelY + PAD;
        bodyY = titleY + font.lineHeight + GAP_TITLE;

        int innerLeft = panelX + PAD;
        int buttonsTop = panelY + panelH - PAD - buttonsH;
        if (offerBackups) {
            addRenderableWidget(Button.builder(backupsLabel(), b -> toggleBackups())
                    .bounds(innerLeft, buttonsTop, innerWidth, BUTTON_H)
                    .tooltip(Tooltip.create(Component.translatable(KEY_BACKUPS_TOOLTIP)))
                    .build());
            buttonsTop += BUTTON_H + BUTTON_GAP;
        }

        int third = (innerWidth - 2 * BUTTON_GAP) / 3;
        addRenderableWidget(Button.builder(Component.translatable(KEY_RESTORE), b -> restore())
                .bounds(innerLeft, buttonsTop, third, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_LATER), b -> close())
                .bounds(innerLeft + third + BUTTON_GAP, buttonsTop, third, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_NEVER), b -> never())
                .bounds(innerLeft + 2 * (third + BUTTON_GAP), buttonsTop,
                        innerWidth - 2 * (third + BUTTON_GAP), BUTTON_H)
                .build());
    }

    /** How many builds pressing Restore would put back, given the toggle's current state. */
    private int restorable() {
        return includeBackups ? onDisk + backupOnly : onDisk;
    }

    private Component backupsLabel() {
        return Component.translatable(includeBackups ? KEY_BACKUPS_ON : KEY_BACKUPS_OFF, backupOnly);
    }

    private void toggleBackups() {
        includeBackups = !includeBackups;
        rebuildWidgets();
    }

    /**
     * Send the builds back up, and get out of the way.
     *
     * <p>The uploads are paced over the following minutes on a worker thread, so there is nothing for
     * this card to wait for. The player goes on to pick a world; the log records what landed.</p>
     */
    private void restore() {
        BuilderReconcileRunner.restore(scan, includeBackups);
        BuilderReconcilePromptHandler.onAnswered(false);
        close();
    }

    private void never() {
        BuilderReconcilePromptHandler.onAnswered(true);
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

        int y = bodyY;
        for (int i = 0; i < bodyLines.size(); i++) {
            // The "comes back private" tail is a note, not the headline — greyed so the count reads first.
            int colour = i < noteFrom ? COLOR_BODY : COLOR_NOTE;
            graphics.drawCenteredString(font, bodyLines.get(i), centerX, y, colour);
            y += LINE_STEP;
        }
    }

    /** Esc is "not now": it does not record an answer, so the next join offers again. */
    @Override
    public void onClose() {
        close();
    }
}
