package games.brennan.dungeontrain.client;

import org.jetbrains.annotations.Nullable;

/**
 * One-shot holder for vanilla's initial-screen task — the {@link Runnable} that
 * {@code Minecraft#buildInitialScreens} returns and {@code Minecraft#onGameLoadFinished} runs once the
 * loading overlay is gone. It opens the title screen, or with {@code --quickPlayMultiplayer} /
 * {@code --quickPlaySingleplayer} joins straight into a world, and it carries NeoForge's mod-loading
 * warnings screen in front of either.
 *
 * <p>Distant Horizons' self-updater redirects that {@code Runnable.run()} call: when it has an update to
 * offer it runs its own prompt instead and the vanilla task is never executed. {@code
 * MinecraftInitialScreensMixin} stashes the task here first, so when DT suppresses the prompt
 * ({@link DistantHorizonsUpdatePromptSuppression}) it can run what vanilla would have run rather than
 * guessing at a title screen — which is what used to cancel a quick-play join.</p>
 *
 * <p>Render-thread only, and only meaningful between {@code buildInitialScreens} returning and
 * {@code onGameLoadFinished} returning; the mixin clears it at the latter so a prompt opened later in the
 * session can never re-run the join.</p>
 */
public final class InitialScreensCapture {

    @Nullable
    private static Runnable pending;

    private InitialScreensCapture() {}

    /** Stash the task vanilla is about to run. A {@code null} task clears the holder. */
    public static void capture(@Nullable Runnable task) {
        pending = task;
    }

    /** True while a captured task is waiting to be consumed. */
    public static boolean hasPending() {
        return pending != null;
    }

    /** Hand over the captured task (or {@code null}) and forget it, so it runs at most once. */
    @Nullable
    public static Runnable consume() {
        Runnable task = pending;
        pending = null;
        return task;
    }

    /** Forget any captured task without running it. */
    public static void clear() {
        pending = null;
    }
}
