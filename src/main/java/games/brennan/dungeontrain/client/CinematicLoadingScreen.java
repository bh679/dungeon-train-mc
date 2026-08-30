package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Themed loading screen shown by {@link CinematicPreloadGate} between world-entry
 * and the spawn intro cinematic, while the terrain around the shot streams in.
 *
 * <p>Opaque (fully hides the popping-in world), non-pausing (so the integrated
 * server keeps generating/streaming chunks behind it — {@link #isPauseScreen}
 * returns {@code false}), and non-dismissible ({@link #shouldCloseOnEsc} returns
 * {@code false}; the gate closes it when the cinematic is ready).</p>
 *
 * <p>Visuals (train-fill progress, palette, tip line) are shared with the themed
 * first screen (world-load) via {@link LoadingScreenTheme} so the two read as one
 * continuous loading sequence — see {@code LevelLoadingScreenThemeMixin}. Progress
 * is driven by {@link CinematicPreloadGate#progress()}.</p>
 *
 * <p>The gate also holds the reveal until {@link LoadingStories} finishes its
 * story even once loading itself is ready — see
 * {@link CinematicPreloadGate#isWaitingForStory()}. In that state this screen
 * shows a "press Space to start" prompt instead of the ×3 skip-dots, and a
 * single Space press ({@link CinematicPreloadGate#confirmStart}) is enough.</p>
 */
public final class CinematicLoadingScreen extends Screen {

    // Space ×3 skips straight to the cinematic (before it's otherwise ready). A row
    // of dots at the bottom fills one per press (revealed on the first press) — no text.
    private static final int SKIP_PRESSES = 3;
    private static final int DOT_SIZE = 4;
    private static final int DOT_GAP = 6;
    private static final int DOT_BOTTOM_MARGIN = 18;
    private static final int DOT_FILLED = 0xFFFFFFFF;
    private static final int DOT_EMPTY = 0x40FFFFFF;

    private static final int TIP_MAX_WIDTH = 260;
    private static final int PROMPT_BOTTOM_MARGIN = 18;

    /** Dim left over the live world once the panel has faded — black at 70% opacity. */
    private static final int WORLD_DIM = 0xB3000000;
    /** How long the panel takes to fade away into that dim, in milliseconds — a slow reveal. */
    private static final float REVEAL_FADE_MS = 30_000.0f;

    /** Replaces the title once the bar reads 100% — loading is done, the train is boarding. */
    private static final Component READY_TITLE = Component.translatable("gui.dungeontrain.cinematic.loading.ready");

    /** Distinct Space presses so far (0..{@link #SKIP_PRESSES}). */
    private int spacePresses = 0;
    /** When the bar first read 100% — start of the fade to the world. -1 until then. */
    private long revealStartNanos = -1L;
    /** Guards against key-repeat: only the first frame of a held Space counts. */
    private boolean spaceHeld = false;

    public CinematicLoadingScreen() {
        super(Component.translatable("gui.dungeontrain.cinematic.loading"));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_SPACE) {
            if (!spaceHeld) {
                if (CinematicPreloadGate.isWaitingForStory()) {
                    // Loading itself is already done — one press is enough.
                    CinematicPreloadGate.confirmStart();
                } else if (CinematicPreloadGate.canSkip()) {
                    // Only count presses once skipping is allowed (player is on the
                    // train, chunk-wait phase) — you can't skip out of the pre-placement hold.
                    spacePresses++;
                    if (spacePresses >= SKIP_PRESSES) {
                        CinematicPreloadGate.skip();
                    }
                }
            }
            spaceHeld = true;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_SPACE) {
            spaceHeld = false;
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /**
     * Opaque while loading, so the popping-in terrain never shows — then, once the bar reads
     * 100% and {@link CinematicPreloadGate} has parked the camera at the shot's opening pose,
     * the panel fades away over {@link #REVEAL_FADE_MS} to leave the live world under
     * {@link #WORLD_DIM}. Vanilla's world blur is replaced either way.
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float panel = 1.0f - revealProgress();
        if (panel < 1.0f) {
            g.fill(0, 0, this.width, this.height, WORLD_DIM);
        }
        LoadingScreenTheme.fillBackground(g, this.width, this.height, panel);
    }

    /** 0 until the bar reads 100%, then eases to 1 across {@link #REVEAL_FADE_MS}. */
    private float revealProgress() {
        if (!LoadingScreenTheme.readsComplete(CinematicPreloadGate.progress())) {
            return 0.0f;
        }
        long now = System.nanoTime();
        if (revealStartNanos < 0) {
            revealStartNanos = now;
        }
        float elapsedMs = (now - revealStartNanos) / 1.0e6f;
        return Math.min(1.0f, elapsedMs / REVEAL_FADE_MS);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int cy = this.height / 2;
        double progress = CinematicPreloadGate.progress();
        long animNanos = LoadingSequenceProgress.animNanos();

        int railW = Math.min(LoadingScreenTheme.MAX_RAIL_W, this.width - 80);
        int railLeft = cx - railW / 2;
        int railY = cy + 8;

        // Once the bar reads 100% the train is ready and only the story (or a Space press) is
        // left — say so, instead of still claiming to be preparing.
        Component heading = LoadingScreenTheme.readsComplete(progress) ? READY_TITLE : this.title;
        LoadingScreenTheme.drawTitle(g, this.font, heading, cx, cy - 30);
        LoadingScreenTheme.drawFillingTrain(g, this.font, railLeft, railW, railY, progress, animNanos);
        LoadingScreenTheme.drawPercent(g, this.font, progress, cx, cy + 34);
        LoadingScreenTheme.drawTip(g, this.font, LoadingStories.currentLine(), cx, cy + 52, TIP_MAX_WIDTH);

        if (CinematicPreloadGate.isWaitingForStory()) {
            LoadingScreenTheme.drawTip(g, this.font,
                Component.translatable("gui.dungeontrain.cinematic.press_space_to_start"),
                cx, this.height - PROMPT_BOTTOM_MARGIN, TIP_MAX_WIDTH);
        } else {
            drawSkipDots(g, cx);
        }
    }

    /**
     * Space-to-skip indicator: a centred row of dots at the bottom, revealed on
     * the first Space press, one filled per press. No text.
     */
    private void drawSkipDots(GuiGraphics g, int cx) {
        if (spacePresses <= 0) return;
        int total = SKIP_PRESSES * DOT_SIZE + (SKIP_PRESSES - 1) * DOT_GAP;
        int x0 = cx - total / 2;
        int y = this.height - DOT_BOTTOM_MARGIN;
        for (int i = 0; i < SKIP_PRESSES; i++) {
            int x = x0 + i * (DOT_SIZE + DOT_GAP);
            g.fill(x, y, x + DOT_SIZE, y + DOT_SIZE, i < spacePresses ? DOT_FILLED : DOT_EMPTY);
        }
    }
}
