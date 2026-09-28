package games.brennan.dungeontrain.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateWeightOverlay#diff}: the per-install overlay holds only what differs from the
 * bundled catalogue. The regression this guards — an edit to one room snapshotting every other
 * room's bundled weight into the player's file, where it then outlived later bundled retunes.
 */
final class TemplateWeightOverlayTest {

    private static final Map<String, TemplateMeta> BUNDLED = Map.of(
        "outsidethebox", TemplateMeta.of(0).withMode("endless_open"),
        "parkour", TemplateMeta.of(2),
        "nostalgia", TemplateMeta.of(1).withBuilder(new BuilderCredit("abc", "Antonio")));

    @Test
    @DisplayName("an untouched merged view writes an empty overlay")
    void identical_writesNothing() {
        assertTrue(TemplateWeightOverlay.diff(BUNDLED, BUNDLED).isEmpty());
    }

    @Test
    @DisplayName("only the entry the player changed is written — the rest of the catalogue is not snapshotted")
    void oneEdit_writesOneEntry() {
        Map<String, TemplateMeta> merged = new java.util.HashMap<>(BUNDLED);
        merged.put("parkour", TemplateMeta.of(5));
        Map<String, TemplateMeta> overlay = TemplateWeightOverlay.diff(merged, BUNDLED);
        assertEquals(Map.of("parkour", TemplateMeta.of(5)), overlay);
        assertFalse(overlay.containsKey("outsidethebox"), "weight-0 room must not be frozen into the overlay");
    }

    @Test
    @DisplayName("gate, mode, label and builder changes all count as differences")
    void nonWeightFields_count() {
        Map<String, TemplateMeta> merged = new java.util.HashMap<>(BUNDLED);
        merged.put("outsidethebox", BUNDLED.get("outsidethebox").withMode("bedrockless"));
        merged.put("parkour", BUNDLED.get("parkour").withGate(new TemplateGate(3, TemplateGate.ALL, TemplateGate.DEFAULT.phases())));
        merged.put("nostalgia", BUNDLED.get("nostalgia").withName("Nostalgia!"));
        assertEquals(3, TemplateWeightOverlay.diff(merged, BUNDLED).size());
    }

    @Test
    @DisplayName("an entry with no bundled counterpart is always written")
    void userOnlyEntry_kept() {
        Map<String, TemplateMeta> merged = new java.util.HashMap<>(BUNDLED);
        merged.put("myroom", TemplateMeta.of(1));
        assertEquals(Map.of("myroom", TemplateMeta.of(1)), TemplateWeightOverlay.diff(merged, BUNDLED));
    }

    @Test
    @DisplayName("an entry removed from the merged view is simply absent — bundled stands again on reload")
    void removedEntry_omitted() {
        Map<String, TemplateMeta> merged = new java.util.HashMap<>(BUNDLED);
        merged.remove("parkour");
        assertTrue(TemplateWeightOverlay.diff(merged, BUNDLED).isEmpty());
    }

    @Test
    @DisplayName("a stale snapshot self-heals: redundant entries drop, a real override survives")
    void staleSnapshot_selfHeals() {
        // What an old player file looks like after being merged: everything as shipped that day,
        // with outsidethebox still at 1 from before it was retired.
        Map<String, TemplateMeta> merged = new java.util.HashMap<>(BUNDLED);
        merged.put("outsidethebox", TemplateMeta.of(1).withMode("endless_open"));
        Map<String, TemplateMeta> overlay = TemplateWeightOverlay.diff(merged, BUNDLED);
        assertEquals(1, overlay.size());
        assertEquals(1, overlay.get("outsidethebox").weight());
    }
}
