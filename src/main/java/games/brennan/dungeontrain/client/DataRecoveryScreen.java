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
 * <p>The top-ranked candidate is the one the button restores; the rest are listed with their full
 * paths so the player can see what was found and pick a different one with {@code /dtrestore}.
 * Paths are shown rather than summarised on purpose — a sibling instance folder might belong to a
 * different pack, and only the player can tell.</p>
 *
 * <p>Same flat card as {@link ConfigDeviationScreen}, so every one-time question on the title
 * screen looks like it came from the same place. Client-only.</p>
 */
public final class DataRecoveryScreen extends Screen {

    private static final String KEY_TITLE = "gui.dungeontrain.data_recovery.title";
    private static final String KEY_BODY = "gui.dungeontrain.data_recovery.body";
    private static final String KEY_RESTORE = "gui.dungeontrain.data_recovery.restore";
    private static final String KEY_RESTORE_TOOLTIP = "gui.dungeontrain.data_recovery.restore.tooltip";
    private static final String KEY_LATER = "gui.dungeontrain.data_recovery.later";
    private static final String KEY_NEVER = "gui.dungeontrain.data_recovery.never";
    private static final String KEY_BACKUP = "gui.dungeontrain.data_recovery.candidate.backup";
    private static final String KEY_SIBLING = "gui.dungeontrain.data_recovery.candidate.sibling";
    private static final String KEY_MORE = "gui.dungeontrain.data_recovery.more";
    private static final String KEY_DONE_TITLE = "gui.dungeontrain.data_recovery.done.title";
    private static final String KEY_DONE_BODY = "gui.dungeontrain.data_recovery.done.body";
    private static final String KEY_DONE_NOTHING = "gui.dungeontrain.data_recovery.done.nothing";
    private static final String KEY_DONE_FAIL = "gui.dungeontrain.data_recovery.done.fail";
    private static final String KEY_CLOSE = "gui.dungeontrain.data_recovery.close";

    /** Candidates listed in full; beyond this the rest are summarised as "+N more". */
    private static final int MAX_LISTED = 5;

    private static final int CARD_W = 300;
    private static final int PAD = 14;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;

    private static final int GAP_TITLE = 9;
    private static final int GAP_BODY = 10;
    private static final int GAP_LIST = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_LIST = 0xFF9A9AA2;

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
    private List<FormattedCharSequence> listLines = List.of();
    private int titleY;
    private int bodyY;
    private int listY;

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
        for (Component line : listText()) {
            lines.addAll(font.split(line, innerWidth));
        }
        listLines = List.copyOf(lines);

        int contentH = font.lineHeight + GAP_TITLE
                + bodyLines.size() * LINE_STEP + GAP_BODY
                + listLines.size() * LINE_STEP + GAP_LIST
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
        listY = cursor;
        cursor += listLines.size() * LINE_STEP + GAP_LIST;

        int innerLeft = panelX + PAD;
        if (restoredFiles != null) {
            addRenderableWidget(Button.builder(Component.translatable(KEY_CLOSE), b -> close())
                    .bounds(innerLeft, cursor, innerWidth, BUTTON_H)
                    .build());
            return;
        }
        int third = (innerWidth - 2 * BUTTON_GAP) / 3;
        addRenderableWidget(Button.builder(Component.translatable(KEY_RESTORE), b -> restore())
                .bounds(innerLeft, cursor, third, BUTTON_H)
                // Say up front that restoring can only add — the word "restore" reads as
                // "overwrite what I have now", and it never does.
                .tooltip(Tooltip.create(Component.translatable(KEY_RESTORE_TOOLTIP)))
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_LATER), b -> close())
                .bounds(innerLeft + third + BUTTON_GAP, cursor, third, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_NEVER), b -> never())
                .bounds(innerLeft + 2 * (third + BUTTON_GAP), cursor,
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

    /** The middle block: what was found and where, so the player can judge it for themselves. */
    private List<Component> listText() {
        if (restoredFiles != null) return List.of();
        List<Component> lines = new ArrayList<>();
        int listed = Math.min(candidates.size(), MAX_LISTED);
        for (int i = 0; i < listed; i++) {
            PlayerDataRecovery.Candidate candidate = candidates.get(i);
            String key = candidate.kind() == PlayerDataRecovery.Kind.BACKUP ? KEY_BACKUP : KEY_SIBLING;
            lines.add(Component.translatable(key,
                candidate.description(), candidate.path().toString()));
        }
        if (candidates.size() > listed) {
            lines.add(Component.translatable(KEY_MORE, candidates.size() - listed));
        }
        return lines;
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

        // Left-aligned: these are file paths the player may want to go and look at.
        int ly = listY;
        for (FormattedCharSequence line : listLines) {
            graphics.drawString(font, line, panelX + PAD, ly, COLOR_LIST, false);
            ly += LINE_STEP;
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
