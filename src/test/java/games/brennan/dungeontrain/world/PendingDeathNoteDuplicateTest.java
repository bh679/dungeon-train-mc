package games.brennan.dungeontrain.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.narrative.NoteKind;
import games.brennan.dungeontrain.world.PendingDeathNotes.PendingDeathNote;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the "one note per target per life" rule — {@link PendingDeathNotes#isDuplicate}.
 *
 * <p>Signing two Death Notes naming the same player used to record two pending notes, so the
 * author's death armed two relay curses and two echoes hunted one target. The pending list is
 * drained on the author's death, so "already in the list" is exactly "already noted this life",
 * which is the rule tested here.</p>
 *
 * <p>Tests the pure predicate rather than the {@link PendingDeathNotes} instance so no
 * {@code ServerLevel} / saved-data storage is needed.</p>
 */
class PendingDeathNoteDuplicateTest {

    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_AUTHOR = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static PendingDeathNote note(UUID author, String target, NoteKind kind) {
        return new PendingDeathNote(author, "Author", target, "", kind);
    }

    @Test
    void sameAuthorSameTargetSameKindIsADuplicate() {
        List<PendingDeathNote> notes = List.of(note(AUTHOR, "Steve", NoteKind.DEATH));
        assertTrue(PendingDeathNotes.isDuplicate(notes, AUTHOR, "Steve", NoteKind.DEATH));
    }

    /** Names are matched the way the relay matches them — case-insensitively. */
    @Test
    void targetNameMatchIgnoresCase() {
        List<PendingDeathNote> notes = List.of(note(AUTHOR, "Steve", NoteKind.DEATH));
        assertTrue(PendingDeathNotes.isDuplicate(notes, AUTHOR, "sTeVe", NoteKind.DEATH));
    }

    @Test
    void aDifferentTargetIsNotADuplicate() {
        List<PendingDeathNote> notes = List.of(note(AUTHOR, "Steve", NoteKind.DEATH));
        assertFalse(PendingDeathNotes.isDuplicate(notes, AUTHOR, "Alex", NoteKind.DEATH));
    }

    /**
     * A Death Note and a Love Note may both name one player in a single life: they are opposite
     * stories about the same person, not a repeat of one.
     */
    @Test
    void theOtherKindOnTheSameTargetIsNotADuplicate() {
        List<PendingDeathNote> notes = List.of(note(AUTHOR, "Steve", NoteKind.DEATH));
        assertFalse(PendingDeathNotes.isDuplicate(notes, AUTHOR, "Steve", NoteKind.LOVE));
    }

    /** Two players may each note the same target — the rule is per author, not per target. */
    @Test
    void anotherAuthorNamingTheSameTargetIsNotADuplicate() {
        List<PendingDeathNote> notes = List.of(note(AUTHOR, "Steve", NoteKind.DEATH));
        assertFalse(PendingDeathNotes.isDuplicate(notes, OTHER_AUTHOR, "Steve", NoteKind.DEATH));
    }

    @Test
    void anEmptyListNeverMatches() {
        assertFalse(PendingDeathNotes.isDuplicate(List.of(), AUTHOR, "Steve", NoteKind.DEATH));
    }

    /** A blank or absent target is the "finds no name" path, handled before this check. */
    @Test
    void blankAndNullInputsNeverMatch() {
        List<PendingDeathNote> notes = List.of(note(AUTHOR, "Steve", NoteKind.DEATH));
        assertFalse(PendingDeathNotes.isDuplicate(notes, AUTHOR, "  ", NoteKind.DEATH));
        assertFalse(PendingDeathNotes.isDuplicate(notes, AUTHOR, null, NoteKind.DEATH));
        assertFalse(PendingDeathNotes.isDuplicate(notes, null, "Steve", NoteKind.DEATH));
        assertFalse(PendingDeathNotes.isDuplicate(null, AUTHOR, "Steve", NoteKind.DEATH));
    }

    /**
     * The chat line the guard sends must exist in every shipped locale — including en_us, which
     * {@code check-provenance.py} does not validate (it is the reference the others align against).
     */
    @Test
    void everyLocaleShipsTheAlreadyNotedLines() throws IOException {
        Path langDir = RepoPaths.langFile("en_us").getParent();
        try (Stream<Path> files = Files.list(langDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject lang = JsonParser
                    .parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                for (NoteKind kind : NoteKind.values()) {
                    String key = "chat.dungeontrain." + kind.englishTitle() + ".already";
                    assertTrue(lang.has(key), file.getFileName() + " is missing " + key);
                    assertTrue(lang.get(key).getAsString().contains("%s"),
                        file.getFileName() + " → " + key + " must name the target with %s");
                }
            }
        }
    }
}
