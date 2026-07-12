package games.brennan.dungeontrain.client;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The rotating story line shown on both loading screens — one of nine short
 * verses (a fellow passenger's uncertain, musing take on the train, in the
 * voice established by {@code Faulthurst} — see
 * {@code narratives/random_books/musings_of_faulthurst.json}), picked once
 * per login. Lines advance one at a time, each held on screen for a duration
 * proportional to its length — driven by the shared
 * {@link LoadingSequenceProgress} clock, not by load progress, so the story
 * keeps reading at a steady pace regardless of how fast the world loads, and
 * continues uninterrupted across the handoff between the two screens.
 */
public final class LoadingStories {

    private static final int[] STORY_LINE_COUNTS = { 11, 12, 16, 35, 30, 25, 29, 26, 47 };

    private static final long MIN_LINE_MS = 1200;
    private static final long MAX_LINE_MS = 6000;
    private static final long BASE_LINE_MS = 650;
    private static final long PER_CHAR_MS = 55;

    private static final List<List<String>> STORIES = IntStream.range(0, STORY_LINE_COUNTS.length)
        .mapToObj(i -> storyKeys(i + 1, STORY_LINE_COUNTS[i]))
        .toList();

    private static List<String> pickedKeys;
    private static long[] cumulativeMs;
    private static long totalMs;

    private LoadingStories() {}

    private static List<String> storyKeys(int story, int lineCount) {
        return IntStream.rangeClosed(1, lineCount)
            .mapToObj(line -> "gui.dungeontrain.loading.story." + story + ".line." + line)
            .collect(Collectors.toUnmodifiableList());
    }

    /** The line to show right now for this login's picked story. Loops once the story finishes. */
    public static Component currentLine() {
        ensurePicked();
        long elapsedMs = LoadingSequenceProgress.animNanos() / 1_000_000L;
        long t = totalMs <= 0 ? 0 : elapsedMs % totalMs;
        return Component.translatable(pickedKeys.get(indexForTime(t)));
    }

    /** True once every line of the picked story has had its full time on screen (does not reset when it loops). */
    public static boolean isFinished() {
        ensurePicked();
        long elapsedMs = LoadingSequenceProgress.animNanos() / 1_000_000L;
        return elapsedMs >= totalMs;
    }

    private static void ensurePicked() {
        if (pickedKeys != null) return;
        pickedKeys = STORIES.get(ThreadLocalRandom.current().nextInt(STORIES.size()));
        cumulativeMs = new long[pickedKeys.size()];
        long running = 0;
        for (int i = 0; i < pickedKeys.size(); i++) {
            int length = Component.translatable(pickedKeys.get(i)).getString().length();
            long duration = Math.max(MIN_LINE_MS, Math.min(MAX_LINE_MS, BASE_LINE_MS + PER_CHAR_MS * length));
            running += duration;
            cumulativeMs[i] = running;
        }
        totalMs = running;
    }

    private static int indexForTime(long t) {
        for (int i = 0; i < cumulativeMs.length; i++) {
            if (t < cumulativeMs[i]) return i;
        }
        return cumulativeMs.length - 1;
    }

    /** Called on logout so the next login picks a new story and restarts from its first line. */
    public static void reset() {
        pickedKeys = null;
        cumulativeMs = null;
        totalMs = 0;
    }
}
