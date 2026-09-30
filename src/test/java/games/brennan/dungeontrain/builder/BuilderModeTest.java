package games.brennan.dungeontrain.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(6, ids.size(), "the picker screen lays out six tiles in a vertical list");
        assertEquals(BuilderMode.WHOLE_CARRIAGES, BuilderMode.values()[0], "Whole leads, as in the editor's row");
        // The picker's order: the two big tiles, then Buildings and the advanced three.
        assertEquals(java.util.List.of(BuilderMode.WHOLE_CARRIAGES, BuilderMode.TRAIN_DIMENSIONS, BuilderMode.BUILDINGS,
            BuilderMode.TRAIN_OUTSIDE, BuilderMode.INSIDE_CARRIAGE, BuilderMode.TRACKS_TUNNELS), BuilderMode.NAV_ORDER);
        assertEquals(java.util.Set.copyOf(java.util.Arrays.asList(BuilderMode.values())), java.util.Set.copyOf(BuilderMode.NAV_ORDER),
            "every mode has a tile");
        assertTrue(BuilderMode.WHOLE_CARRIAGES.primary() && BuilderMode.TRAIN_DIMENSIONS.primary());
        assertFalse(BuilderMode.BUILDINGS.primary() || BuilderMode.TRAIN_OUTSIDE.primary());
        // Buildings are editor-only: no builder world, never offered by a builder packet.
        assertFalse(BuilderMode.BUILDINGS.hasBuilderWorld());
        assertFalse(BuilderMode.BUILDER_MODES.contains(BuilderMode.BUILDINGS));
        assertTrue(BuilderMode.fromBuilderId("buildings").isEmpty());
        assertTrue(BuilderMode.fromId("buildings").isPresent());
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

    @Test
    void descriptionKeyHangsOffTheLabelKey() {
        for (BuilderMode mode : BuilderMode.values()) {
            assertEquals(mode.labelKey() + ".description", mode.descriptionKey());
        }
    }
}
