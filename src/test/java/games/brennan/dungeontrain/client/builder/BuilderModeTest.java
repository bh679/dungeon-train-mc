package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the builder-mode identifiers. Their ids appear in world names, logs and lang keys, so a
 * rename is a user-visible change and should have to break a test first.
 */
final class BuilderModeTest {

    @Test
    @DisplayName("Every mode has a distinct id, label key and texture path")
    void identifiersAreDistinct() {
        Set<String> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        Set<String> textures = new HashSet<>();
        for (BuilderMode mode : BuilderMode.values()) {
            assertTrue(ids.add(mode.id()), "duplicate id: " + mode.id());
            assertTrue(keys.add(mode.labelKey()), "duplicate label key: " + mode.labelKey());
            assertTrue(textures.add(mode.texturePath()), "duplicate texture: " + mode.texturePath());
        }
        assertEquals(4, ids.size(), "the picker screen lays out exactly four tiles (2x2)");
    }

    @Test
    @DisplayName("fromId round-trips every mode")
    void fromIdRoundTrips() {
        for (BuilderMode mode : BuilderMode.values()) {
            assertEquals(mode, BuilderMode.fromId(mode.id()).orElseThrow());
        }
    }

    @Test
    @DisplayName("fromId is lenient about case and padding, and rejects junk")
    void fromIdIsLenientButNotPermissive() {
        assertEquals(BuilderMode.TRAIN_OUTSIDE, BuilderMode.fromId("  TRAIN_Outside ").orElseThrow());
        assertTrue(BuilderMode.fromId("carriages").isEmpty());
        assertTrue(BuilderMode.fromId("").isEmpty());
        assertTrue(BuilderMode.fromId(null).isEmpty());
    }

    @Test
    @DisplayName("Label keys sit under the builder namespace so the lang files stay greppable")
    void labelKeysAreNamespaced() {
        assertTrue(Arrays.stream(BuilderMode.values())
                .allMatch(m -> m.labelKey().startsWith("gui.dungeontrain.builder.")));
    }
}
