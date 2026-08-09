package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link NoteKind}: the title → kind resolution that decides which mechanic a signed book
 * triggers, and the wire/NBT id round-trip that must read every pre-Love-Note record as a curse.
 */
class NoteKindTest {

    private static final List<String> NO_LOCALIZED = List.of();

    @Test
    void englishTitlesResolveRegardlessOfCaseOrSpacing() {
        for (String title : List.of("Death Note", "deathnote", "DEATHNOTE", "  death  note ")) {
            assertEquals(NoteKind.DEATH, NoteKind.of(title, NO_LOCALIZED, NO_LOCALIZED), title);
        }
        for (String title : List.of("Love Note", "lovenote", "LOVENOTE", "  love  note ")) {
            assertEquals(NoteKind.LOVE, NoteKind.of(title, NO_LOCALIZED, NO_LOCALIZED), title);
        }
    }

    @Test
    void anOrdinaryTitleIsNeitherKind() {
        assertNull(NoteKind.of("My Diary", NO_LOCALIZED, NO_LOCALIZED));
        assertNull(NoteKind.of("", NO_LOCALIZED, NO_LOCALIZED));
        assertNull(NoteKind.of(null, NO_LOCALIZED, NO_LOCALIZED));
        // Near-misses must not trigger: the trigger is the whole title, not a substring.
        assertNull(NoteKind.of("Death Notes", NO_LOCALIZED, NO_LOCALIZED));
        assertNull(NoteKind.of("A Love Note", NO_LOCALIZED, NO_LOCALIZED));
    }

    @Test
    void localizedTitlesResolveToTheirOwnKind() {
        Set<String> death = Set.of("死亡笔记", "notademuerte");
        Set<String> love = Set.of("恋爱笔记", "notadeamor");
        assertEquals(NoteKind.DEATH, NoteKind.of("死亡笔记", death, love));
        assertEquals(NoteKind.DEATH, NoteKind.of("Nota de Muerte", death, love));
        assertEquals(NoteKind.LOVE, NoteKind.of("恋爱笔记", death, love));
        assertEquals(NoteKind.LOVE, NoteKind.of("Nota de Amor", death, love));
        // Each kind's English name still works for a player whose locale ships translations.
        assertEquals(NoteKind.DEATH, NoteKind.of("Death Note", death, love));
        assertEquals(NoteKind.LOVE, NoteKind.of("Love Note", death, love));
    }

    @Test
    void aCollidingLocalizedTitleGoesToTheOlderMechanic() {
        // Should a translation ever title both books the same, the curse keeps the title — the older
        // mechanic does not silently change meaning under players who already use it.
        Set<String> same = Set.of("cuaderno");
        assertEquals(NoteKind.DEATH, NoteKind.of("Cuaderno", same, same));
    }

    @Test
    void idRoundTripsAndAnythingUnknownReadsAsTheCurse() {
        for (NoteKind kind : NoteKind.values()) {
            assertEquals(kind, NoteKind.fromId(kind.id()));
        }
        assertEquals(NoteKind.DEATH, NoteKind.fromId("DEATH"));
        assertEquals(NoteKind.LOVE, NoteKind.fromId("  Love "));
        // The compatibility contract: a record written before this field existed is a curse.
        assertEquals(NoteKind.DEATH, NoteKind.fromId(null));
        assertEquals(NoteKind.DEATH, NoteKind.fromId(""));
        assertEquals(NoteKind.DEATH, NoteKind.fromId("friendship"));
    }

    @Test
    void theTwoKindsNeverShareAnIdentifyingConstant() {
        // Every per-kind constant has to differ, or the two books would be indistinguishable
        // somewhere downstream (the same model, the same relay value, the same trigger word).
        assertNotEquals(NoteKind.DEATH.id(), NoteKind.LOVE.id());
        assertNotEquals(NoteKind.DEATH.englishTitle(), NoteKind.LOVE.englishTitle());
        assertNotEquals(NoteKind.DEATH.trophyTitle(), NoteKind.LOVE.trophyTitle());
        assertNotEquals(NoteKind.DEATH.modelData(), NoteKind.LOVE.modelData());
        assertNotEquals(NoteKind.DEATH.instructionBookSuffix(), NoteKind.LOVE.instructionBookSuffix());
    }
}
