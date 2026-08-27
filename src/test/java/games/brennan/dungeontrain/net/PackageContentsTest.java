package games.brennan.dungeontrain.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins {@link PackageContents#SECTIONS}, and above all the {@code subdir} column.
 *
 * <p>Those slugs are real directory names under a package's working folder
 * ({@code dtpacks/&lt;pkg&gt;/templates/}, …) and are mirrored by
 * {@code UserContentMigration}. They read like the lowercase editor-category
 * vocabulary and sit in the same record as an actual {@code category} column,
 * so a sweep that normalises category strings can trivially catch them too —
 * renaming one orphans every user template on disk. This test is the tripwire:
 * {@code subdir} is a filesystem path segment, not a category id.</p>
 */
final class PackageContentsTest {

    @Test
    @DisplayName("subdir slugs are on-disk directory names and must never be renamed")
    void subdirs_areFrozen() {
        List<String> actual = new ArrayList<>();
        for (PackageContents.Section s : PackageContents.SECTIONS) actual.add(s.subdir());
        assertEquals(List.of(
            "templates",
            "contents",
            "parts",
            "containers",
            "tracks",
            "pillars",
            "tunnels",
            "portals/room",
            "prefabs/loot",
            "prefabs/block_variants"
        ), actual, "subdir values are on-disk paths — changing one orphans user content");
    }

    @Test
    @DisplayName("every non-null category is one the teleport builder understands")
    void categories_areTeleportable() {
        // "track" is a valid model id for the TRACKS arm (which resolves the id to a plot) and is
        // ignored by the others, so any failure here is the category failing to route, not the model.
        for (PackageContents.Section s : PackageContents.SECTIONS) {
            if (s.category() == null) continue;
            assertNotNull(
                games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport
                    .commandFor(s.category(), "track", "someName"),
                "section '" + s.label() + "' carries category '" + s.category()
                    + "' which commandFor does not route");
        }
    }

    @Test
    @DisplayName("the four sections with no editor plot carry a null category")
    void nonPlotSections_haveNoCategory() {
        List<String> labelsWithoutCategory = new ArrayList<>();
        for (PackageContents.Section s : PackageContents.SECTIONS) {
            if (s.category() == null) labelsWithoutCategory.add(s.label());
        }
        assertEquals(
            List.of("Containers", "Loot Prefabs", "Block-Variant Prefabs"),
            labelsWithoutCategory);
    }
}
