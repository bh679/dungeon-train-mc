package games.brennan.dungeontrain.client;

import net.minecraft.util.Mth;

/**
 * The clock behind the menu → loading-screen hand-off, kept here rather than on a screen because
 * the hand-off outlives every individual screen it crosses: it starts on the menu
 * ({@link WorldOpenLoadingScreen} takes over mid-fade), continues through vanilla's
 * {@code ProgressScreen} ({@code ProgressScreenThemeMixin}) and is closed out by
 * {@code LevelLoadingScreenThemeMixin}.
 *
 * <p>Two stages, back to back:</p>
 * <ol>
 *   <li><b>{@link #menuAlpha()}</b> — 1 → 0 over {@value #MENU_FADE_MS} ms. The menu's chrome
 *       dissolves off the panorama it was sitting on.</li>
 *   <li><b>{@link #themeAlpha()}</b> — 0 → 1 over {@value #THEME_FADE_MS} ms, beginning where the
 *       first stage ends. The panorama cross-fades into the themed panel, across the stretch of
 *       the load where the bar is still sitting at 0%.</li>
 * </ol>
 *
 * <p>The second stage is what turns the vanilla frames that used to leak through here into the
 * first half of a deliberate fade — the panorama is <em>meant</em> to be visible for a moment, and
 * is fully covered before any real progress is on screen.</p>
 */
public final class JoinIntroFade {

    /** Menu chrome dissolve — deliberately brisk; this is a button press, not a scene change. */
    private static final long MENU_FADE_MS = 100L;
    /** Panorama → themed panel. Sized to the early-load window, before the bar leaves 0%. */
    private static final long THEME_FADE_MS = 1200L;

    /** Millisecond clock at {@link #begin()}; negative while no hand-off is under way. */
    private static long startMillis = -1L;
    /** Set by {@link #complete()} — the fade is over regardless of how little of it had run. */
    private static boolean forcedComplete = false;

    private JoinIntroFade() {}

    /** Armed by {@link LoadingSequenceProgress#beginJoin()}, i.e. the instant the join starts. */
    public static void begin() {
        startMillis = System.currentTimeMillis();
        forcedComplete = false;
    }

    /** True while a hand-off is running and the themed panel is not yet fully opaque. */
    public static boolean isFading() {
        return startMillis >= 0 && themeAlpha() < 1.0f;
    }

    /**
     * Opacity of the outgoing menu: 1 at the start, 0 after {@value #MENU_FADE_MS} ms.
     * Zero when no hand-off is under way, so a screen with nothing to fade draws nothing extra.
     */
    public static float menuAlpha() {
        if (startMillis < 0 || forcedComplete) return 0.0f;
        return 1.0f - Mth.clamp(elapsedMillis() / (float) MENU_FADE_MS, 0.0f, 1.0f);
    }

    /** Opacity of the themed panel: 0 until the menu is gone, then up to 1 over the fade window. */
    public static float themeAlpha() {
        if (startMillis < 0) return 1.0f; // nothing to fade from — the panel is simply itself
        if (forcedComplete) return 1.0f;
        float since = elapsedMillis() - MENU_FADE_MS;
        return Mth.clamp(since / (float) THEME_FADE_MS, 0.0f, 1.0f);
    }

    /**
     * Snap the fade to finished. Called by {@code LevelLoadingScreenThemeMixin}: once real
     * progress is being drawn the panel must already be solid, however fast the machine got here.
     */
    public static void complete() {
        if (startMillis >= 0) {
            forcedComplete = true;
        }
    }

    /** Cleared with the rest of the timeline by {@link LoadingSequenceProgress#reset()}. */
    public static void reset() {
        startMillis = -1L;
        forcedComplete = false;
    }

    private static float elapsedMillis() {
        return System.currentTimeMillis() - startMillis;
    }
}
