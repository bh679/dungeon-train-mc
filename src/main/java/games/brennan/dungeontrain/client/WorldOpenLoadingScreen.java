package games.brennan.dungeontrain.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * First screen of the world-join sequence — DT's stand-in for vanilla's
 * {@code GenericMessageScreen} while a world is being opened ("Reading world data", then
 * "Loading resources"), swapped in by {@link WorldOpenScreenSwap}.
 *
 * <p>Vanilla draws those phases over the <em>main-menu panorama</em>, so the join opened on
 * vanilla chrome before the themed sequence began. This screen carries the same
 * {@link LoadingScreenTheme} panel as the rest of the sequence — themed world-load screen
 * ({@code LevelLoadingScreenThemeMixin}), {@code ReceivingLevelScreen}
 * ({@code ReceivingLevelScreenThemeMixin}) and {@link CinematicLoadingScreen} — so the display is
 * identical from the very first frame.</p>
 *
 * <p>It has no progress model of its own: it renders the shared {@link LoadingSequenceProgress}
 * timeline's current value, opening that timeline (and its animation clock) at zero for the
 * world-load screen to drive upward. Purely presentational — the world-open flow drives itself,
 * exactly as it did behind the vanilla screen.</p>
 */
public final class WorldOpenLoadingScreen extends Screen {

    private static final int TIP_MAX_WIDTH = 260;

    public WorldOpenLoadingScreen() {
        super(Component.translatable("gui.dungeontrain.loading.title"));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected boolean shouldNarrateNavigation() {
        return false;
    }

    /** Opaque fill — never the panorama the vanilla screen would have shown here. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        LoadingScreenTheme.fillBackground(g, this.width, this.height);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int cy = this.height / 2;
        double progress = LoadingSequenceProgress.displayed();
        long animNanos = LoadingSequenceProgress.animNanos();

        int railW = Math.min(LoadingScreenTheme.MAX_RAIL_W, this.width - 80);
        int railLeft = cx - railW / 2;
        int railY = cy + 8;

        LoadingScreenTheme.drawTitle(g, this.font, this.title, cx, cy - 30);
        LoadingScreenTheme.drawFillingTrain(g, this.font, railLeft, railW, railY, progress, animNanos);
        LoadingScreenTheme.drawPercent(g, this.font, progress, cx, cy + 34);
        LoadingScreenTheme.drawTip(g, this.font, LoadingStories.currentLine(), cx, cy + 52, TIP_MAX_WIDTH);
    }
}
