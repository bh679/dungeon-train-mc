package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.data.PlayerDataRecovery;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Offers a player whose Dungeon Train data went missing the chance to put it back.
 *
 * <p>Shown at the title screen when {@link PlayerDataRecovery} finds an install with no builds and
 * no progress, nothing waiting in {@code config/}, and at least one place the data might still
 * exist — a backup this install wrote, or another Minecraft instance on the same machine. The
 * usual cause is a modpack update: the launcher replaced {@code config/}, where all of it used to
 * live.</p>
 *
 * <p>Collapsed, the card is two lines and three buttons. "What happened?" reveals why it happened
 * and every place the data was found — detail most players don't need in order to press Restore.</p>
 *
 * <p><b>Layout is three fixed bands</b>: the title and body at the top, the buttons pinned to the
 * bottom, and the expanded detail scrolling in whatever space is left. The panel is clamped to the
 * window rather than growing with its content, because it grew straight off the bottom of the
 * screen the first time someone expanded it with four candidates — taking the buttons with it.</p>
 *
 * <p>Same flat card as {@link ConfigDeviationScreen}, so every one-time question on the title
 * screen looks like it came from the same place. Client-only.</p>
 */
public final class DataRecoveryScreen extends Screen {

    private static final String KEY_TITLE = "gui.dungeontrain.data_recovery.title";
    private static final String KEY_BODY = "gui.dungeontrain.data_recovery.body";
    private static final String KEY_WHY = "gui.dungeontrain.data_recovery.why";
    private static final String KEY_DETAILS = "gui.dungeontrain.data_recovery.details";
    private static final String KEY_DETAILS_HIDE = "gui.dungeontrain.data_recovery.details.hide";
    private static final String KEY_RESTORE = "gui.dungeontrain.data_recovery.restore";
    private static final String KEY_RESTORE_TOOLTIP = "gui.dungeontrain.data_recovery.restore.tooltip";
    private static final String KEY_LATER = "gui.dungeontrain.data_recovery.later";
    private static final String KEY_NEVER = "gui.dungeontrain.data_recovery.never";
    private static final String KEY_BACKUP = "gui.dungeontrain.data_recovery.candidate.backup";
    private static final String KEY_BACKUP_FOLDER = "gui.dungeontrain.data_recovery.candidate.folder";
    private static final String KEY_BACKUP_EXTERNAL = "gui.dungeontrain.data_recovery.candidate.external";
    private static final String KEY_SIBLING = "gui.dungeontrain.data_recovery.candidate.sibling";
    private static final String KEY_DONE_TITLE = "gui.dungeontrain.data_recovery.done.title";
    private static final String KEY_DONE_BODY = "gui.dungeontrain.data_recovery.done.body";
    private static final String KEY_DONE_NOTHING = "gui.dungeontrain.data_recovery.done.nothing";
    private static final String KEY_DONE_FAIL = "gui.dungeontrain.data_recovery.done.fail";
    private static final String KEY_CLOSE = "gui.dungeontrain.data_recovery.close";

    /**
     * Whether the player has opened the details. Static so it survives the screen being rebuilt by
     * the toggle, and so reopening the prompt in the same session remembers the choice.
     */
    private static boolean expanded = false;

    private static final int CARD_W = 300;
    private static final int PAD = 14;
    /** Minimum gap between the card and the window edge — the clamp that keeps buttons on screen. */
    private static final int MARGIN = 16;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;

    private static final int GAP_TITLE = 9;
    private static final int GAP_BODY = 10;
    private static final int GAP_LIST = 8;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_LIST = 0xFF9A9AA2;
    private static final int COLOR_SCROLLBAR = 0xFF55555E;

    private final Screen previousScreen;
    private final List<PlayerDataRecovery.Candidate> candidates;

    /** Null until the player restores; then the screen re-lays itself out as the "done" state. */
    private Integer restoredFiles = null;
    private boolean restoreFailed = false;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> detailLines = List.of();
    private int titleY;
    private int bodyY;

    /** Top of the scrolling detail band, and its height. Zero-height when collapsed. */
    private int detailY;
    private int detailH;
    /** Pixels scrolled within the detail band, clamped to {@link #maxScroll}. */
    private int scrollOffset = 0;
    private int maxScroll = 0;

    public DataRecoveryScreen(Screen previousScreen, List<PlayerDataRecovery.Candidate> candidates) {
        super(Component.translatable(KEY_TITLE)); // narration title
        this.previousScreen = previousScreen;
        this.candidates = List.copyOf(candidates);
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(bodyText(), innerWidth);

        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : detailText()) {
            lines.addAll(font.split(line, innerWidth));
        }
        detailLines = List.copyOf(lines);

        // Three bands: header, scrolling detail, buttons. Only the middle one flexes.
        int headerH = font.lineHeight + GAP_TITLE + bodyLines.size() * LINE_STEP;
        int buttonsH = restoredFiles == null ? BUTTON_H + BUTTON_GAP + BUTTON_H : BUTTON_H;
        int wantedDetailH = detailLines.isEmpty() ? 0 : GAP_BODY + detailLines.size() * LINE_STEP;

        panelW = CARD_W;
        int wantedH = PAD + headerH + wantedDetailH + GAP_LIST + buttonsH + PAD;
        // The clamp. Without it the card grows off the bottom of the window and takes the buttons
        // with it — which is exactly what four candidates' worth of detail did.
        panelH = Math.min(wantedH, height - 2 * MARGIN);
        panelX = (width - panelW) / 2;
        panelY = Math.max(MARGIN, (height - panelH) / 2);
        centerX = panelX + panelW / 2;

        int cursor = panelY + PAD;
        titleY = cursor;
        cursor += font.lineHeight + GAP_TITLE;
        bodyY = cursor;
        cursor += bodyLines.size() * LINE_STEP;

        int buttonsTop = panelY + panelH - PAD - buttonsH;
        detailY = detailLines.isEmpty() ? cursor : cursor + GAP_BODY;
        detailH = Math.max(0, buttonsTop - GAP_LIST - detailY);
        maxScroll = Math.max(0, detailLines.size() * LINE_STEP - detailH);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        int innerLeft = panelX + PAD;
        if (restoredFiles != null) {
            addRenderableWidget(Button.builder(Component.translatable(KEY_CLOSE), b -> close())
                    .bounds(innerLeft, buttonsTop, innerWidth, BUTTON_H)
                    .build());
            return;
        }
        addRenderableWidget(Button.builder(
                Component.translatable(expanded ? KEY_DETAILS_HIDE : KEY_DETAILS), b -> toggleDetails())
                .bounds(innerLeft, buttonsTop, innerWidth, BUTTON_H)
                .build());

        int actionsTop = buttonsTop + BUTTON_H + BUTTON_GAP;
        int third = (innerWidth - 2 * BUTTON_GAP) / 3;
        addRenderableWidget(Button.builder(Component.translatable(KEY_RESTORE), b -> restore())
                .bounds(innerLeft, actionsTop, third, BUTTON_H)
                // Say up front that restoring can only add — the word "restore" reads as
                // "overwrite what I have now", and it never does.
                .tooltip(Tooltip.create(Component.translatable(KEY_RESTORE_TOOLTIP)))
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_LATER), b -> close())
                .bounds(innerLeft + third + BUTTON_GAP, actionsTop, third, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_NEVER), b -> never())
                .bounds(innerLeft + 2 * (third + BUTTON_GAP), actionsTop,
                        innerWidth - 2 * (third + BUTTON_GAP), BUTTON_H)
                .build());
    }

    private Component bodyText() {
        if (restoredFiles == null) return Component.translatable(KEY_BODY);
        if (restoreFailed) return Component.translatable(KEY_DONE_FAIL);
        return restoredFiles == 0
            ? Component.translatable(KEY_DONE_NOTHING)
            : Component.translatable(KEY_DONE_BODY, restoredFiles);
    }

    /**
     * The expanded band: why it happened, then every place the data was found.
     *
     * <p>Backups are listed by filename with their folder named once at the end — they all live in
     * the same place, and repeating an absolute path per entry was most of what overflowed the
     * card. A sibling install keeps its full path: that is the identifying information, and it is
     * the case where the player has to judge whether the folder is really theirs.</p>
     */
    private List<Component> detailText() {
        if (restoredFiles != null || !expanded) return List.of();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(KEY_WHY));
        boolean anyBackup = false;
        for (PlayerDataRecovery.Candidate candidate : candidates) {
            if (candidate.kind() == PlayerDataRecovery.Kind.EXTERNAL_BACKUP) {
                lines.add(Component.translatable(KEY_BACKUP_EXTERNAL,
                    candidate.path().getFileName().toString(), candidate.path().getParent()));
            } else if (candidate.kind() == PlayerDataRecovery.Kind.BACKUP) {
                anyBackup = true;
                lines.add(Component.translatable(KEY_BACKUP, candidate.path().getFileName().toString()));
            } else {
                lines.add(Component.translatable(KEY_SIBLING,
                    candidate.description(), candidate.path().toString()));
            }
        }
        if (anyBackup) {
            lines.add(Component.translatable(KEY_BACKUP_FOLDER,
                PlayerDataPaths.backupsRoot().toString()));
        }
        return lines;
    }

    /** Show or hide the why-it-happened line and the list of places to restore from. */
    private void toggleDetails() {
        expanded = !expanded;
        scrollOffset = 0;
        rebuildWidgets();
    }

    /** Restore the top-ranked candidate. Additive — nothing already on disk is touched. */
    private void restore() {
        try {
            restoredFiles = PlayerDataRecovery.restore(candidates.get(0),
                PlayerDataPaths.root(), PlayerDataPaths.dtpacksRoot());
            restoreFailed = false;
        } catch (IOException e) {
            restoredFiles = 0;
            restoreFailed = true;
        }
        // Whatever happened, don't ask again this session.
        DataRecoveryPromptHandler.onAnswered(false);
        scrollOffset = 0;
        rebuildWidgets();
    }

    /** Stop offering this. Recorded on disk, so it survives a restart. */
    private void never() {
        DataRecoveryPromptHandler.onAnswered(true);
        close();
    }

    private void close() {
        this.minecraft.setScreen(previousScreen);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * LINE_STEP)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

        graphics.drawCenteredString(font, titleText(), centerX, titleY, COLOR_TITLE);

        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }

        if (detailLines.isEmpty() || detailH <= 0) return;

        // Clip to the band so a scrolled line can never paint over the body or the buttons.
        graphics.enableScissor(panelX + 1, detailY, panelX + panelW - 1, detailY + detailH);
        int ly = detailY - scrollOffset;
        for (FormattedCharSequence line : detailLines) {
            // Left-aligned: these are file names and paths the player may want to go and look at.
            graphics.drawString(font, line, panelX + PAD, ly, COLOR_LIST, false);
            ly += LINE_STEP;
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            int trackX = panelX + panelW - PAD / 2 - 2;
            int thumbH = Math.max(8, detailH * detailH / (detailLines.size() * LINE_STEP));
            int thumbY = detailY + (detailH - thumbH) * scrollOffset / maxScroll;
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, COLOR_SCROLLBAR);
        }
    }

    private Component titleText() {
        return Component.translatable(restoredFiles == null ? KEY_TITLE : KEY_DONE_TITLE);
    }

    /**
     * Esc means "not now" — it does NOT record the offer as declined, so it returns next launch.
     * Dismissing a question about your own missing save data shouldn't quietly answer it.
     */
    @Override
    public void onClose() {
        close();
    }
}
