package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The chat line a shot gets for a camera advancement it moved along. */
final class PhotoProgressNoticeTest {

    private static final String P = EnchiridionAdvancements.PATH_PREFIX;

    @Test
    @DisplayName("count tiers come lowest target first")
    void countTiersOrdered() {
        assertEquals(List.of(P + "wildlife_photographer", P + "field_guide", P + "gational_neographic"),
                EnchiridionAdvancements.countTiersOf(P + "wildlife_photographer"));
        assertEquals(List.of(P + "danger_close", P + "creature_feature", P + "rogues_gallery"),
                EnchiridionAdvancements.countTiersOf(P + "danger_close"));
        assertEquals(List.of(P + "those_we_meet", P + "inhabitants", P + "passenger_log"),
                EnchiridionAdvancements.countTiersOf(P + "those_we_meet"));
    }

    @Test
    @DisplayName("Coffee Table Book is the last biome tier")
    void biomeTiersOrdered() {
        assertEquals(P + "coffee_table_book", EnchiridionAdvancements.BIOME_TIER_ORDER.get(2));
    }

    @Test
    @DisplayName("the next tier skips the earned ones; none once all are earned")
    void nextTier() {
        List<String> tiers = List.of("a", "b", "c");
        assertEquals("a", PhotoProgressNotice.nextTier(tiers, t -> false));
        assertEquals("b", PhotoProgressNotice.nextTier(tiers, Set.of("a")::contains));
        assertEquals("c", PhotoProgressNotice.nextTier(tiers, Set.of("a", "b")::contains));
        assertNull(PhotoProgressNotice.nextTier(tiers, t -> true));
    }

    @Test
    @DisplayName("only a count tier's line leaves out its target, as its album does")
    void showsMax() {
        assertFalse(PhotoProgressNotice.showsMax(P + "field_guide"));
        assertFalse(PhotoProgressNotice.showsMax(P + "rogues_gallery"));
        assertFalse(PhotoProgressNotice.showsMax(P + "those_we_meet"));
        assertTrue(PhotoProgressNotice.showsMax(P + "nature_documentary"));
        assertTrue(PhotoProgressNotice.showsMax(P + "most_wanted"));
        assertTrue(PhotoProgressNotice.showsMax(P + "scenic_route"));
        assertTrue(PhotoProgressNotice.showsMax(P + "coffee_table_book"));
    }

    @Test
    @DisplayName("a count tier's line gives just the count")
    void countLine() {
        TranslatableContents line = contents(PhotoProgressNotice.line(Component.literal("Field Guide"),
                new PhotoProgressNotice.Progress(12, 25), false));
        assertEquals(PhotoProgressNotice.KEY_NAMED_COUNT, line.getKey());
        assertEquals(2, line.getArgs().length);
        assertEquals(12, line.getArgs()[1]);
    }

    @Test
    @DisplayName("a collection's line gives its target")
    void namedLine() {
        TranslatableContents line = contents(PhotoProgressNotice.line(Component.literal("Nature Documentary"),
                new PhotoProgressNotice.Progress(12, 25), true));
        assertEquals(PhotoProgressNotice.KEY_NAMED, line.getKey());
        assertEquals(3, line.getArgs().length);
        assertEquals(12, line.getArgs()[1]);
        assertEquals(25, line.getArgs()[2]);
    }

    private static TranslatableContents contents(Component line) {
        return (TranslatableContents) line.getContents();
    }
}
