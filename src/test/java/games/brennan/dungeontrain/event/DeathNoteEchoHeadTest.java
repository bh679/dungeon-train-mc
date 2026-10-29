package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.narrative.NoteKind;
import games.brennan.playermob.player.SourceProfileSkin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DeathNoteEchoHead}'s pure halves: who earns the head, and whose head it is.
 * Building the item and resolving its face is left to the in-game Gate 2 check, as with
 * {@code HeadProfilesTest}.
 */
final class DeathNoteEchoHeadTest {

    private static final UUID TARGET = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID AUTHOR = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    @DisplayName("only the cursed target killing a Death Note echo earns the head")
    void shouldDrop_targetKillsDeathNoteEcho() {
        assertTrue(DeathNoteEchoHead.shouldDrop(NoteKind.DEATH, TARGET, TARGET.toString()));
        assertFalse(DeathNoteEchoHead.shouldDrop(NoteKind.DEATH, AUTHOR, TARGET.toString()));
        assertFalse(DeathNoteEchoHead.shouldDrop(NoteKind.DEATH, null, TARGET.toString()));
        assertFalse(DeathNoteEchoHead.shouldDrop(NoteKind.LOVE, TARGET, TARGET.toString()));
    }

    @Test
    @DisplayName("reads the author's uuid and name off the echo's skin ref")
    void identityFor_skinRef() {
        String ref = SourceProfileSkin.encode(AUTHOR, "Ranboo");
        assertEquals(Optional.of(new DeathNoteEchoHead.Identity("Ranboo", Optional.of(AUTHOR), Optional.empty())),
                DeathNoteEchoHead.identityFor(ref, "Other"));
    }

    @Test
    @DisplayName("carries the author's captured skin URL so the head wears the echo's face")
    void identityFor_capturedSkin() {
        String url = "http://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef";
        String ref = SourceProfileSkin.encode(AUTHOR, "Ranboo", url);
        assertEquals(Optional.of(new DeathNoteEchoHead.Identity("Ranboo", Optional.of(AUTHOR), Optional.of(url))),
                DeathNoteEchoHead.identityFor(ref, "Other"));
    }

    @Test
    @DisplayName("a skin URL off Mojang's CDN is dropped, leaving the name-only head")
    void identityFor_foreignSkinUrl() {
        String ref = SourceProfileSkin.encode(AUTHOR, "Ranboo", "https://example.com/skin.png");
        assertEquals(Optional.of(new DeathNoteEchoHead.Identity("Ranboo", Optional.of(AUTHOR), Optional.empty())),
                DeathNoteEchoHead.identityFor(ref, "Other"));
    }

    @Test
    @DisplayName("falls back to the stored author name when the echo has no skin ref")
    void identityFor_nameFallback() {
        assertEquals(Optional.of(new DeathNoteEchoHead.Identity("Ranboo", Optional.empty(), Optional.empty())),
                DeathNoteEchoHead.identityFor("", "Ranboo"));
    }

    @Test
    @DisplayName("no usable username means no head")
    void identityFor_none() {
        assertTrue(DeathNoteEchoHead.identityFor(null, "Unknown player!").isEmpty());
        assertTrue(DeathNoteEchoHead.identityFor(SourceProfileSkin.encode(AUTHOR, "bad name"), null).isEmpty());
    }
}
