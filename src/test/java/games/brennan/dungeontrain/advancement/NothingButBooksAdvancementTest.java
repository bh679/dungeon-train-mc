package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.narrative.DeathNoteBookTag;
import games.brennan.dungeontrain.narrative.EditorAuthoredBookTag;
import games.brennan.dungeontrain.narrative.LeaderboardBookTag;
import games.brennan.dungeontrain.narrative.LetterBookTag;
import games.brennan.dungeontrain.narrative.LoveNoteBookTag;
import games.brennan.dungeontrain.narrative.NarrativeBookTag;
import games.brennan.dungeontrain.narrative.PlayerWrittenBookTag;
import games.brennan.dungeontrain.narrative.RandomBookTag;
import games.brennan.dungeontrain.narrative.RunStatBookTag;
import games.brennan.dungeontrain.narrative.SharedBookFoundTag;
import games.brennan.dungeontrain.narrative.SharedBookTag;
import games.brennan.dungeontrain.narrative.StartingBookTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link NothingButBooksAdvancement#isStoryBook}, which now delegates to
 * {@link games.brennan.dungeontrain.narrative.BurnableBookTag#isBurnable} so "story book" tracks
 * the mod's full burn-after-reading roster instead of a hand-copied subset of it.
 */
final class NothingButBooksAdvancementTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ItemStack book() {
        return new ItemStack(Items.WRITTEN_BOOK);
    }

    @Test
    @DisplayName("Previously-recognized book kinds still count")
    void previouslyRecognizedKindsCount() {
        ItemStack narrative = book();
        NarrativeBookTag.stamp(narrative, "story", 0, 0);
        assertTrue(NothingButBooksAdvancement.isStoryBook(narrative));

        ItemStack starting = book();
        StartingBookTag.stamp(starting, "starting", 0);
        assertTrue(NothingButBooksAdvancement.isStoryBook(starting));

        ItemStack random = book();
        RandomBookTag.stamp(random, "random", 0);
        assertFalse(NothingButBooksAdvancement.isStoryBook(random), "not held yet");
        RandomBookTag.markHeld(random);
        assertTrue(NothingButBooksAdvancement.isStoryBook(random));

        ItemStack sharedFound = book();
        SharedBookFoundTag.stamp(sharedFound);
        assertFalse(NothingButBooksAdvancement.isStoryBook(sharedFound), "not held yet");
        SharedBookFoundTag.markHeld(sharedFound);
        assertTrue(NothingButBooksAdvancement.isStoryBook(sharedFound));
    }

    @Test
    @DisplayName("Death Notes and Love Notes now count")
    void curseBooksCount() {
        ItemStack deathNote = book();
        DeathNoteBookTag.stamp(deathNote);
        assertTrue(NothingButBooksAdvancement.isStoryBook(deathNote));

        ItemStack loveNote = book();
        LoveNoteBookTag.stamp(loveNote);
        assertTrue(NothingButBooksAdvancement.isStoryBook(loveNote));
    }

    @Test
    @DisplayName("Signed/shared/lectern-letter/editor-authored books now count")
    void signedAndSharedBooksCount() {
        ItemStack shared = book();
        SharedBookTag.stamp(shared);
        assertTrue(NothingButBooksAdvancement.isStoryBook(shared));

        ItemStack letter = book();
        LetterBookTag.stamp(letter);
        assertTrue(NothingButBooksAdvancement.isStoryBook(letter));

        ItemStack playerWritten = book();
        PlayerWrittenBookTag.stamp(playerWritten);
        assertTrue(NothingButBooksAdvancement.isStoryBook(playerWritten));

        ItemStack editorAuthored = book();
        EditorAuthoredBookTag.stamp(editorAuthored);
        assertFalse(NothingButBooksAdvancement.isStoryBook(editorAuthored), "not held yet");
        EditorAuthoredBookTag.markHeld(editorAuthored);
        assertTrue(NothingButBooksAdvancement.isStoryBook(editorAuthored));
    }

    @Test
    @DisplayName("Leaderboard books count only once held")
    void leaderboardBooksCountOnceHeld() {
        ItemStack board = book();
        LeaderboardBookTag.stamp(board);
        assertFalse(NothingButBooksAdvancement.isStoryBook(board), "shelved / spilled, never picked up");

        LeaderboardBookTag.markHeld(board);
        assertTrue(NothingButBooksAdvancement.isStoryBook(board));
    }

    @Test
    @DisplayName("Faulthurst stat notes count only once held")
    void statNotesCountOnceHeld() {
        ItemStack note = book();
        RunStatBookTag.stamp(note, 0x5EEDL);
        assertFalse(NothingButBooksAdvancement.isStoryBook(note), "baked at the container, never picked up");

        RunStatBookTag.markHeld(note);
        assertTrue(NothingButBooksAdvancement.isStoryBook(note));
    }

    @Test
    @DisplayName("Unstamped, empty and non-written-book stacks never count")
    void nonStoryStacksNeverCount() {
        assertFalse(NothingButBooksAdvancement.isStoryBook(book()), "unstamped written book");
        assertFalse(NothingButBooksAdvancement.isStoryBook(new ItemStack(Items.WRITABLE_BOOK)), "book & quill");
        assertFalse(NothingButBooksAdvancement.isStoryBook(ItemStack.EMPTY));
        assertFalse(NothingButBooksAdvancement.isStoryBook(null));
    }
}
