package games.brennan.dungeontrain.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.narrative.NoteKind;
import games.brennan.dungeontrain.world.PendingDeathNotes.PendingDeathNote;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Save-compat for the spoken script a pending note now carries: a note signed this life must still
 * be holding the author's words when they die (which is when it is uploaded), and a save written
 * before echoes could speak must still load.
 *
 * <p>Round-trips a {@link CompoundTag} through {@link PendingDeathNotes#load} / {@code save} rather
 * than touching a live {@code ServerLevel}, mirroring {@code StairsLocationDataTest}. The
 * {@code HolderLookup.Provider} is unused by this store, so {@code null} is safe.</p>
 */
final class PendingDeathNoteLinesTest {

    private static final UUID AUTHOR = UUID.nameUUIDFromBytes("author".getBytes());

    private static PendingDeathNotes storeWith(PendingDeathNote... notes) {
        PendingDeathNotes store = PendingDeathNotes.load(new CompoundTag());
        for (PendingDeathNote n : notes) store.add(n);
        return store;
    }

    private static PendingDeathNotes roundTrip(PendingDeathNotes store) {
        return PendingDeathNotes.load(store.save(new CompoundTag(), null));
    }

    @Test
    @DisplayName("a note keeps its spoken lines, in order, across a save")
    void linesSurviveTheRoundTrip() {
        PendingDeathNotes back = roundTrip(storeWith(new PendingDeathNote(
                AUTHOR, "Author", "Victim", "", NoteKind.DEATH,
                List.of("I remember.", "Now I take yours."))));
        List<PendingDeathNote> taken = back.takeForAuthor(AUTHOR);
        assertEquals(1, taken.size());
        assertEquals(List.of("I remember.", "Now I take yours."), taken.get(0).lines());
        assertEquals(NoteKind.DEATH, taken.get(0).kind());
        assertEquals("Victim", taken.get(0).targetName());
    }

    @Test
    @DisplayName("a note that is only a name round-trips with nothing to say")
    void aNoteWithNoLinesIsStillANote() {
        PendingDeathNotes back = roundTrip(storeWith(new PendingDeathNote(
                AUTHOR, "Author", "Beloved", "", NoteKind.LOVE, List.of())));
        List<PendingDeathNote> taken = back.takeForAuthor(AUTHOR);
        assertEquals(1, taken.size());
        assertTrue(taken.get(0).lines().isEmpty());
        assertEquals(NoteKind.LOVE, taken.get(0).kind());
    }

    @Test
    @DisplayName("a save written before echoes could speak loads with an empty script")
    void legacyNotesLoadWithoutLines() {
        // Exactly the tag shape the old save() wrote: no lines list at all.
        CompoundTag note = new CompoundTag();
        note.putString("authorUuid", AUTHOR.toString());
        note.putString("authorName", "Author");
        note.putString("targetName", "Victim");
        note.putString("targetUuid", "");
        note.putString("kind", "death");
        net.minecraft.nbt.ListTag notes = new net.minecraft.nbt.ListTag();
        notes.add(note);
        CompoundTag tag = new CompoundTag();
        tag.put("notes", notes);

        List<PendingDeathNote> taken = PendingDeathNotes.load(tag).takeForAuthor(AUTHOR);
        assertEquals(1, taken.size());
        assertTrue(taken.get(0).lines().isEmpty());
    }

    @Test
    @DisplayName("the record never hands back a null or mutable script")
    void linesAreAlwaysASafeList() {
        PendingDeathNote n = new PendingDeathNote(AUTHOR, "Author", "Victim", "", NoteKind.DEATH, null);
        assertTrue(n.lines().isEmpty());
    }
}
