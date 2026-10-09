package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.client.snapshot.DeathPhotoUploads;
import games.brennan.dungeontrain.client.snapshot.RideSnapshot;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotGallery;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The beat between dying and the death recap: the live world keeps drawing — the train still
 * rolling past — while a red wash rises and "You Died!" fades in over it.
 *
 * <p>It exists to keep the frame of death smooth. {@link NarrativeDeathScreen} needs this run's
 * ride photos decoded and uploaded to the GPU, and opening it straight away did that work on the
 * frame the player died — a visible hitch players read as "the lag killed me". This screen starts
 * those decodes on the background executor, uploads at most one finished texture per tick, and
 * hands over to the recap once the moment has played out and the photos are ready (or a hard cap
 * passes, so it can never strand the player here).</p>
 *
 * <p>Not a pause screen: the integrated server keeps ticking behind it, exactly as it does behind
 * the recap.</p>
 */
public final class DeathMomentScreen extends Screen {

    /** How long the moment plays before the recap may open (1.5 s). */
    static final int MOMENT_TICKS = 30;
    /** Hand over regardless after this long; the recap waits out any decode still in flight. */
    static final int MAX_TICKS = 100;
    /** Ticks for the wash and title to rise to full. */
    private static final int FADE_TICKS = 20;
    /** Ignore clicks this early so one already in flight at death can't skip the moment. */
    private static final int MIN_SKIP_TICKS = 10;
    /** Below this the font renderer would draw text fully opaque — skip it instead. */
    private static final float TEXT_EPS = 0.02f;
    /** Vanilla DeathScreen's wash, top and bottom (ARGB); alpha scales with the fade. */
    private static final int WASH_TOP = 0x60500000;
    private static final int WASH_BOTTOM = 0xA0803030;
    private static final int CAUSE_COLOR = 0xD0D0D0;

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
        if ((ticks >= MOMENT_TICKS && photosReady()) || ticks >= MAX_TICKS) handOver();
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
            if (!s.isTextureReady()) return; // still decoding — keep page order, try next tick
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
        minecraft.setScreen(new NarrativeDeathScreen());
    }

    private boolean maySkip() {
        return ticks >= MIN_SKIP_TICKS && photosReady();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (maySkip()) handOver();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean skipKey = keyCode == InputConstants.KEY_SPACE || keyCode == InputConstants.KEY_RETURN;
        if (skipKey && maySkip()) {
            handOver();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float fade = Mth.clamp((ticks + partialTick) / FADE_TICKS, 0.0f, 1.0f);
        g.fillGradient(0, 0, this.width, this.height, scaleAlpha(WASH_TOP, fade), scaleAlpha(WASH_BOTTOM, fade));
        if (fade < TEXT_EPS) return;
        int textAlpha = Math.round(fade * 255.0f) << 24;

        g.pose().pushPose();
        g.pose().scale(2.0f, 2.0f, 2.0f);
        g.drawCenteredString(this.font, title(), this.width / 4, this.height / 8, textAlpha | 0xFFFFFF);
        g.pose().popPose();

        String cause = deathCause();
        if (cause != null) {
            g.drawCenteredString(this.font, Component.literal(cause), this.width / 2,
                    this.height / 4 + 28, textAlpha | CAUSE_COLOR);
        }
    }

    /** The world shows through untouched — no menu blur or dim. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    private Component title() {
        boolean hardcore = minecraft != null && minecraft.level != null
                && minecraft.level.getLevelData().isHardcore();
        return Component.translatable(hardcore ? "deathScreen.title.hardcore" : "deathScreen.title");
    }

    private static String deathCause() {
        DeathStatsPacket s = DeathStatsCache.get();
        return s != null && s.deathCause() != null && !s.deathCause().isEmpty() ? s.deathCause() : null;
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
