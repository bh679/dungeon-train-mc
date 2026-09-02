package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the picker tile → editor category mapping. The title screen's Train Editor button opens the
 * builder picker, so this is what decides where a click lands; getting it wrong sends an author to
 * the wrong plots with nothing to say it happened.
 */
final class BuilderModeCategoryTest {

    @Test
    @DisplayName("Each tile maps to the category its builder-facing name describes")
    void mapsEachMode() {
        assertEquals(EditorCategory.CARRIAGES, BuilderModeCategory.of(BuilderMode.TRAIN_OUTSIDE));
        assertEquals(EditorCategory.CONTENTS, BuilderModeCategory.of(BuilderMode.INSIDE_CARRIAGE));
        assertEquals(EditorCategory.TRACKS, BuilderModeCategory.of(BuilderMode.TRACKS_TUNNELS));
        assertEquals(EditorCategory.PORTALS, BuilderModeCategory.of(BuilderMode.TRAIN_DIMENSIONS));
    }

    @Test
    @DisplayName("Every mode maps, and no two modes land in the same category")
    void mappingIsTotalAndDistinct() {
        Set<EditorCategory> seen = EnumSet.noneOf(EditorCategory.class);
        for (BuilderMode mode : BuilderMode.values()) {
            EditorCategory category = BuilderModeCategory.of(mode);
            assertNotNull(category, "unmapped mode: " + mode.id());
            assertTrue(seen.add(category), "two modes map to " + category.id());
        }
        assertEquals(BuilderMode.values().length, seen.size());
    }

    @Test
    @DisplayName("Every mapped category is a command literal the auto-open can actually send")
    void categoriesRoundTripThroughTheCommandToken() {
        for (BuilderMode mode : BuilderMode.values()) {
            EditorCategory category = BuilderModeCategory.of(mode);
            assertEquals(category, EditorCategory.fromId(category.id()).orElseThrow());
        }
    }
}
