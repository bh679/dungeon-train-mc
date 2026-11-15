package games.brennan.dungeontrain.client.nap;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.NapResponsePacket;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * "You feel comfortable here." The nap prompt a natural bed offers ({@code nap/NapBedEvents}), in its
 * daytime or night copy. Main Menu asks once more (the feature is still being tested with players),
 * then saves and quits; Stay awake / Wake up. (or Escape) carries on.
 *
 * <p>The world fades out behind it over its first {@value #FADE_MS} ms, to 80% black, or fully black
 * when the player is already asleep. The final Main Menu hides the buttons and fades the rest of the
 * way to black over {@value #EXIT_FADE_MS} ms before the answer goes to the server.</p>
 */
public final class NapPromptScreen extends Screen {

    private static final int COLOUR_TITLE = 0xFFFFFFFF;
    private static final int COLOUR_BODY = 0xFFA0A0A0;
    private static final int COLOUR_WARNING = 0xFFFFC857;
    private static final int TEXT_WIDTH = 300;
    private static final int BUTTON_W = 150;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int LINE_GAP = 2;
    private static final int SECTION_GAP = 14;

    private static final long FADE_MS = 2000L;
    private static final float FADE_AWAKE = 0.8F;
    private static final float FADE_ASLEEP = 1.0F;
    private static final long EXIT_FADE_MS = 2000L;
    /** Black held after saying yes while the server saves; the screen gives up if no answer comes. */
    private static final long EXIT_HOLD_MS = 10000L;

    private final String prefix;
    private final long openedAt = Util.getMillis();
    private float fadeTarget = FADE_AWAKE;
    private boolean responded = false;
    private boolean warning = false;
    /** When the final Main Menu was pressed; -1 until then. */
    private long exitStartedAt = -1L;
    /** The overlay's alpha at that moment, the exit fade's start. */
    private float exitFrom = 0.0F;
    private List<FormattedCharSequence> titleLines = List.of();
    private List<FormattedCharSequence> bodyLines = List.of();
    private int titleColour = COLOUR_TITLE;
    private int textTop;

    public NapPromptScreen(boolean night) {
        super(Component.translatable(keyPrefix(night) + "title"));
        this.prefix = keyPrefix(night);
    }

    private static String keyPrefix(boolean night) {
        return "gui.dungeontrain.nap." + (night ? "night." : "day.");
    }

    @Override
    protected void init() {
        if (this.minecraft != null && this.minecraft.player != null && this.minecraft.player.isSleeping()) {
            fadeTarget = FADE_ASLEEP;
        }
        int width = Math.min(TEXT_WIDTH, this.width - 40);
        if (warning) {
            titleLines = this.font.split(Component.translatable("gui.dungeontrain.nap.warning"), width);
            bodyLines = List.of();
            titleColour = COLOUR_WARNING;
        } else {
            titleLines = this.font.split(Component.translatable(prefix + "title")
                .append("\n").append(Component.translatable(prefix + "question")), width);
            bodyLines = this.font.split(Component.translatable(prefix + "body"), width);
            titleColour = COLOUR_TITLE;
        }
        int lh = this.font.lineHeight + LINE_GAP;
        int blockH = titleLines.size() * lh + SECTION_GAP
            + (bodyLines.isEmpty() ? 0 : bodyLines.size() * lh + SECTION_GAP) + BUTTON_H;
        textTop = (this.height - blockH) / 2;
        int buttonY = textTop + blockH - BUTTON_H;
        int left = this.width / 2 - BUTTON_W - BUTTON_GAP / 2;
        Button mainMenu = warning
            ? Button.builder(Component.translatable("gui.dungeontrain.nap.main_menu"), b -> startExitFade()).build()
            : Button.builder(Component.translatable("gui.dungeontrain.nap.main_menu"), b -> showWarning(true)).build();
        Button other = warning
            ? Button.builder(CommonComponents.GUI_BACK, b -> showWarning(false)).build()
            : Button.builder(Component.translatable(prefix + "decline"), b -> respond(false)).build();
        mainMenu.setRectangle(BUTTON_W, BUTTON_H, left, buttonY);
        other.setRectangle(BUTTON_W, BUTTON_H, left + BUTTON_W + BUTTON_GAP, buttonY);
        if (exiting()) return;
        addRenderableWidget(mainMenu);
        addRenderableWidget(other);
    }

    private boolean exiting() {
        return exitStartedAt >= 0L;
    }

    /** The final Main Menu: buttons gone, then the fade to full black, then the answer ({@link #tick}). */
    private void startExitFade() {
        if (exiting() || responded) return;
        exitFrom = overlayAlpha();
        exitStartedAt = Util.getMillis();
        clearWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        if (!exiting()) return;
        long elapsed = Util.getMillis() - exitStartedAt;
        if (!responded && elapsed >= EXIT_FADE_MS) {
            // Stays up, black, until the server's NapSavedPacket leaves the world.
            responded = true;
            DungeonTrainNet.sendToServer(new NapResponsePacket(true));
        } else if (responded && elapsed >= EXIT_FADE_MS + EXIT_HOLD_MS && this.minecraft != null) {
            // The server refused (the bed broke, monsters came): back to the game.
            this.minecraft.setScreen(null);
        }
    }

    /** The overlay's current alpha, 0..1. */
    private float overlayAlpha() {
        if (exiting()) {
            float t = Mth.clamp((Util.getMillis() - exitStartedAt) / (float) EXIT_FADE_MS, 0.0F, 1.0F);
            return Mth.lerp(t, exitFrom, 1.0F);
        }
        float t = Mth.clamp((Util.getMillis() - openedAt) / (float) FADE_MS, 0.0F, 1.0F);
        return fadeTarget * t;
    }

    /** Main Menu first shows the in-development warning; its Back returns to the prompt. */
    private void showWarning(boolean on) {
        if (exiting()) return;
        warning = on;
        rebuildWidgets();
    }

    /** The world fades to black behind the prompt instead of vanilla's blur. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int alpha = Math.round(overlayAlpha() * 255.0F);
        g.fill(0, 0, this.width, this.height, alpha << 24);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (exiting()) return;
        int lh = this.font.lineHeight + LINE_GAP;
        int y = textTop;
        for (FormattedCharSequence line : titleLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, titleColour);
            y += lh;
        }
        y += SECTION_GAP;
        for (FormattedCharSequence line : bodyLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, COLOUR_BODY);
            y += lh;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Escape backs out of the warning first, then says no; it does nothing once the exit fade has begun. */
    @Override
    public void onClose() {
        if (exiting()) return;
        if (warning) {
            showWarning(false);
            return;
        }
        respond(false);
    }

    /**
     * Pushed aside by another screen without an answer, even mid exit fade: that is a no, so the server
     * isn't left waiting and nothing is deleted unless the fade ran to the end.
     */
    @Override
    public void removed() {
        if (!responded) {
            responded = true;
            DungeonTrainNet.sendToServer(new NapResponsePacket(false));
        }
    }

    private void respond(boolean accepted) {
        if (responded) return;
        responded = true;
        DungeonTrainNet.sendToServer(new NapResponsePacket(accepted));
        // Main Menu waits for the server's NapSavedPacket to leave; until then the screen just closes.
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }
}
