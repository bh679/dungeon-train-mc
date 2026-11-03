package games.brennan.dungeontrain.narrative;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link NarrativeProgressData#startTier(List, Predicate)} — which stories a lectern may
 * START. "Done" is supplied by the caller (world OR cross-world progress in production), so a story
 * finished in an earlier world stays out of rotation until every story is finished.
 */
final class StoryStartTierTest {

    private static final StoryFile PIP = story("pip", false, null);
    private static final StoryFile SOREN = story("soren", false, "pip");
    private static final StoryFile AUGUSTUS = story("augustus", false, null);
    private static final StoryFile EDDA = story("edda", true, null);
    private static final List<StoryFile> ALL = List.of(PIP, SOREN, AUGUSTUS, EDDA);

    @Test
    @DisplayName("Nothing done: ordinary startable stories only — chained and deferred wait")
    void freshStart() {
        assertEquals(List.of(PIP, AUGUSTUS), NarrativeProgressData.startTier(ALL, done()));
    }

    @Test
    @DisplayName("A done story is never offered, and its done-ness unlocks the story chained after it")
    void doneStoryLeavesRotationAndUnlocksChain() {
        assertEquals(List.of(SOREN, AUGUSTUS), NarrativeProgressData.startTier(ALL, done("pip")));
    }

    @Test
    @DisplayName("Deferred stories surface only once every ordinary story is done")
    void deferredAfterOrdinary() {
        assertEquals(List.of(EDDA), NarrativeProgressData.startTier(ALL, done("pip", "soren", "augustus")));
    }

    @Test
    @DisplayName("Everything done: empty tier, so the lectern falls back to re-reads")
    void allDoneIsEmpty() {
        assertTrue(NarrativeProgressData.startTier(ALL, done("pip", "soren", "augustus", "edda")).isEmpty());
    }

    @Test
    @DisplayName("A dangling 'after' (prerequisite not loaded) counts as met")
    void danglingPrerequisiteIsMet() {
        StoryFile orphan = story("orphan", false, "missing");
        assertEquals(List.of(orphan), NarrativeProgressData.startTier(List.of(orphan), done()));
    }

    @Test
    @DisplayName("Completion is per letter: one variant of every letter completes a story")
    void completionIgnoresVariants() {
        NarrativeProgressData data = NarrativeProgressData.load(new net.minecraft.nbt.CompoundTag());
        data.markRead("pip", 1);
        data.markStoryVariantSeen("pip", 1, 0);
        data.markRead("pip", 2);
        data.markStoryVariantSeen("pip", 2, 0);
        assertTrue(data.progressFor("pip").isComplete(PIP.letters().size()),
            "variant 1 of each letter was never seen, yet the story is complete");
    }

    @Test
    @DisplayName("Re-read: the story the world is part-way through is continued first")
    void rereadContinuesStartedStory() {
        assertEquals(java.util.Optional.of(AUGUSTUS),
            NarrativeProgressData.pickReread(ALL, done("pip"), done("pip", "augustus"), 0L));
    }

    @Test
    @DisplayName("Re-read: with nothing part-way, an unstarted, unfinished story is picked per seed")
    void rereadPicksUnstartedPerSeed() {
        Predicate<StoryFile> complete = done("pip", "soren");
        for (long seed = 0; seed < 8; seed++) {
            StoryFile pick = NarrativeProgressData.pickReread(ALL, complete, done(), seed).orElseThrow();
            assertTrue(pick == AUGUSTUS || pick == EDDA, "only unfinished stories are re-read");
        }
        assertEquals(NarrativeProgressData.pickReread(ALL, complete, done(), 5L),
            NarrativeProgressData.pickReread(ALL, complete, done(), 5L), "stable per seed");
    }

    @Test
    @DisplayName("Re-read: every story complete in the world → empty (caller starts a new round)")
    void rereadEmptyWhenWorldReadAll() {
        assertTrue(NarrativeProgressData.pickReread(ALL, done("pip", "soren", "augustus", "edda"),
            done("pip", "soren", "augustus", "edda"), 0L).isEmpty());
    }

    @Test
    @DisplayName("Re-read: a fresh round starts every story from letter 1")
    void rereadRoundStartsAtLetterOne() {
        NarrativeProgressData data = NarrativeProgressData.load(new net.minecraft.nbt.CompoundTag());
        data.markRead("pip", 1);
        data.markRead("pip", 2);
        data.resetAll();
        assertEquals(1, data.progressFor("pip").nextUnreadLetter(PIP.letters().size()));
    }

    private static Predicate<StoryFile> done(String... basenames) {
        Set<String> set = Set.of(basenames);
        return story -> set.contains(story.basename());
    }

    private static StoryFile story(String basename, boolean deferred, String after) {
        List<Letter> letters = List.of(
            new Letter(1, "Letter One", List.of("a", "b")),
            new Letter(2, "Letter Two", List.of("c", "d")));
        return new StoryFile(ResourceLocation.fromNamespaceAndPath("dungeontrain", "stories/" + basename),
            basename, basename, deferred, after, 1.0, letters);
    }
}
