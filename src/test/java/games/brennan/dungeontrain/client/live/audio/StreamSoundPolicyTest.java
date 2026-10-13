package games.brennan.dungeontrain.client.live.audio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamSoundPolicyTest {

    private static final Predicate<String> APPROVED = Set.of("dungeontrain", "sable", "minecraft", "neoforge")::contains;

    @Test
    void vanillaAndApprovedModFilesReachTheStream() {
        assertTrue(StreamSoundPolicy.allowed("vanilla", APPROVED));
        assertTrue(StreamSoundPolicy.allowed("mod/dungeontrain", APPROVED));
        assertTrue(StreamSoundPolicy.allowed("mod/sable", APPROVED));
    }

    @Test
    void packsAndUnapprovedModsNeverDo() {
        assertFalse(StreamSoundPolicy.allowed("file/MyMusic.zip", APPROVED), "a resource pack's override");
        assertFalse(StreamSoundPolicy.allowed("server/00000000/9b2f-uuid", APPROVED), "a server's resource pack");
        assertFalse(StreamSoundPolicy.allowed("programmer_art", APPROVED));
        assertFalse(StreamSoundPolicy.allowed("mod/somemusicmod", APPROVED), "a mod not on the list");
        assertFalse(StreamSoundPolicy.allowed("mod/dungeontrain,somemusicmod", APPROVED), "a multi-mod jar needs every id approved");
        assertFalse(StreamSoundPolicy.allowed("mod/", APPROVED));
        assertFalse(StreamSoundPolicy.allowed("wwoo:overworld", APPROVED), "anything unrecognised fails closed");
        assertFalse(StreamSoundPolicy.allowed(null, APPROVED), "unknown origin fails closed");
    }

    @Test
    void unapprovedListsOnlyTheModsOffTheList() {
        assertEquals(List.of(), StreamSoundPolicy.unapproved(List.of("minecraft", "dungeontrain"), APPROVED));
        assertEquals(List.of("aaa", "zzz"), StreamSoundPolicy.unapproved(List.of("zzz", "dungeontrain", "aaa"), APPROVED));
    }
}
