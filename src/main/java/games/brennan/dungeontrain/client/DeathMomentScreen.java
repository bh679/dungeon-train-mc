package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.client.perks.PerksScreen;
import games.brennan.dungeontrain.client.snapshot.DeathPhotoUploads;
import games.brennan.dungeontrain.client.snapshot.RideSnapshot;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotGallery;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The beat between dying and the death recap: the live world keeps drawing, the train still
 * rolling on. First the player's own view under a red wash, then {@link DeathCameraCycle}'s shots
 * (riding the carriage, then watching it leave), each cut fading through black, then a tilt up to
 * the sky that fades into the perks menu on its own (Space skips there sooner). Left and Right step
 * through the shots.
 *
 * <p>It also keeps the frame of death smooth. {@link NarrativeDeathScreen} needs this run's
 * ride photos decoded and uploaded to the GPU, and opening it straight away did that work on the
 * frame the player died, a visible hitch players read as "the lag killed me". This screen starts
 * those decodes on the background executor and uploads at most one finished texture per tick;
 * Space hands over to the recap once they are ready.</p>
 *
 * <p>The train survives the death on the server side ({@code ship.sable.DeadRiderAnchors}), so
 * there is no longer a deadline to leave this screen by. Not a pause screen: the integrated
 * server keeps ticking behind it, exactly as it does behind the recap.</p>
 */
public final class DeathMomentScreen extends Screen {

    /** Ticks for the wash to rise to full. */
    private static final int FADE_TICKS = 10;
    /** Ignore Space and clicks this early so one already in flight at death can't skip the moment. */
    private static final int MIN_SKIP_TICKS = 10;
    /** Vanilla DeathScreen's wash, top and bottom (ARGB), at half strength so the train reads through it. POV only. */
    private static final int WASH_TOP = 0x30500000;
    private static final int WASH_BOTTOM = 0x50803030;

    private static final Component CONTINUE = Component.translatable("gui.dungeontrain.death_moment.continue");
    private static final Component CHANGE_VIEW = Component.translatable("gui.dungeontrain.death_moment.change_view");
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFC8C8C8;
    private static final int BACKDROP_COLOR = 0x80000000;
    private static final int PAD = 4;
    private static final int BOTTOM_MARGIN = 48;
    /** The prompt fades in after the wash, so the first frames are just the moment. */
    private static final int PROMPT_DELAY_TICKS = 20;
    private static final int PROMPT_FADE_TICKS = 10;

    private RideSnapshot[] prewarm = new RideSnapshot[0];
    private boolean started;
    private boolean handedOver;
    private int ticks;
    private int uploadCursor;

    public DeathMomentScreen() {
        super(Component.translatable("deathScreen.title"));
    }

    @Override
    protected void init() {
        // init() re-runs on a window resize; the prewarm is started once per death.
        if (started) return;
        started = true;
        // Freeze now, not when the recap opens: no capture, flush or eviction may touch the
        // photos being decoded for it.
        RideSnapshotGallery.freeze();
        prewarm = NarrativeDeathScreen.provisionalBackgrounds();
        for (RideSnapshot s : prewarm) {
            if (s != null) s.preloadAsync(Util.backgroundExecutor());
        }
        // Page 0's photo is already settled, so the death report's photo can start encoding now.
        DeathPhotoUploads.sendFallPhoto(prewarm.length > 0 ? prewarm[0] : null);
    }

    @Override
    public void tick() {
        ticks++;
        uploadOneReadyTexture();
        // The camera starts a tick or two in, once the client knows the player is dead and the
        // body is pinned to its carriage (see DeathCameraCycle.ensureStarted).
        if (minecraft != null) DeathCameraCycle.ensureStarted(minecraft.player);
        DeathCameraCycle.tick();
        // The camera has looked up to the sky and faded to the menu's blue: open it.
        if (DeathCameraCycle.skyDone() && photosReady()) handOver();
    }

    @Override
    public void removed() {
        DeathCameraCycle.stop();
        super.removed();
    }

    /**
     * Spread the GPU uploads one per tick so no single frame carries them all. Walks the photos in
     * page order; a shot that is already live costs nothing and doesn't use up the tick.
     */
    private void uploadOneReadyTexture() {
        while (uploadCursor < prewarm.length) {
            RideSnapshot s = prewarm[uploadCursor];
            if (s == null || s.isTextureLive()) {
                uploadCursor++;
                continue;
            }
            if (!s.isTextureReady()) return; // still decoding: keep page order, try next tick
            uploadCursor++;
            s.texture();
            return;
        }
    }

    /** Every photo the recap is expected to open with is on the GPU. */
    private boolean photosReady() {
        return uploadCursor >= prewarm.length;
    }

    private void handOver() {
        if (handedOver || minecraft == null) return;
        handedOver = true;
        // the perks menu (unlocking only) comes first; moving on from it opens the recap
        PerksScreen.openForDeath(NarrativeDeathScreen::new);
    }

    private boolean maySkip() {
        return ticks >= MIN_SKIP_TICKS && photosReady();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (RefocusClickGuard.swallow()) return true;
        if (maySkip()) handOver();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_LEFT || keyCode == InputConstants.KEY_A) {
            DeathCameraCycle.previous();
            return true;
        }
        if (keyCode == InputConstants.KEY_RIGHT || keyCode == InputConstants.KEY_D) {
            DeathCameraCycle.next();
            return true;
        }
        boolean continueKey = keyCode == InputConstants.KEY_SPACE || keyCode == InputConstants.KEY_RETURN;
        if (continueKey && maySkip()) {
            handOver();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // The red wash belongs to the player's own view only; it rises over the moment of death and
        // goes out with the first cut to black. Every later cut fades through black and back.
        boolean pov = !DeathCameraCycle.isActive() || DeathCameraCycle.isPov();
        if (pov) {
            float fade = Mth.clamp((ticks + partialTick) / FADE_TICKS, 0.0f, 1.0f);
            g.fillGradient(0, 0, this.width, this.height, scaleAlpha(WASH_TOP, fade), scaleAlpha(WASH_BOTTOM, fade));
        }
        float black = DeathCameraCycle.blackAlpha(partialTick);
        if (black > 0.0f) g.fill(0, 0, this.width, this.height, scaleAlpha(0xFF000000, black));
        renderPrompt(g, partialTick);
        // Over the prompt too: the scene ends on the perks menu's night blue, and the menu opens on it.
        float sky = DeathCameraCycle.skyAlpha(partialTick);
        if (sky > 0.0f) g.fill(0, 0, this.width, this.height, scaleAlpha(PerksScreen.SKY_BG, sky));
        String label = DeathCameraCycle.reviewLabel();
        if (label != null && minecraft != null) {
            g.fill(4, 4, 8 + minecraft.font.width(label) * 2, 8 + minecraft.font.lineHeight * 2, BACKDROP_COLOR);
            g.pose().pushPose();
            g.pose().scale(2.0f, 2.0f, 1.0f);
            g.drawString(minecraft.font, label, 3, 3, TEXT_COLOR, true);
            g.pose().popPose();
        }
    }

    /** Bottom-centre prompt, in the intro cinematic's style, faded in after the wash. */
    private void renderPrompt(GuiGraphics g, float partialTick) {
        float t = Mth.clamp((ticks + partialTick - PROMPT_DELAY_TICKS) / PROMPT_FADE_TICKS, 0.0f, 1.0f);
        if (t <= 0.0f || minecraft == null) return;
        Font font = minecraft.font;
        int lineHeight = HudText.scaledLineHeight(font);
        int w1 = HudText.scaledWidth(font, CONTINUE);
        int w2 = HudText.scaledWidth(font, CHANGE_VIEW);
        int width = Math.max(w1, w2);
        int x = (this.width - width) / 2;
        int y = this.height - BOTTOM_MARGIN - lineHeight;
        g.fill(x - PAD, y - PAD, x + width + PAD, y + 2 * lineHeight + PAD, scaleAlpha(BACKDROP_COLOR, t));
        HudText.drawScaled(g, font, CONTINUE, x + (width - w1) / 2, y, scaleAlpha(TEXT_COLOR, t), true);
        HudText.drawScaled(g, font, CHANGE_VIEW, x + (width - w2) / 2, y + lineHeight, scaleAlpha(HINT_COLOR, t), true);
    }

    /** The world shows through untouched: no menu blur or dim. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.round(((argb >>> 24) & 0xFF) * f);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
