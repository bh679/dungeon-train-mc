package games.brennan.dungeontrain.editor;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional stored signed book on a {@link ContainerContentsEntry}: absent by default, carried
 * through every {@code with*} copy, and round-tripped intact through the JSON (store files) and NBT
 * (clipboard) forms — so a signed book added to a loot table spawns as that book.
 */
final class ContainerContentsEntryBookTest {

    private static final ResourceLocation WRITTEN_BOOK =
        ResourceLocation.fromNamespaceAndPath("minecraft", "written_book");
    private static final StoredBook BOOK = new StoredBook("Rail Diary", "Ada", 0,
        List.of("{\"text\":\"Day one \\\"quoted\\\"\"}", "\"page two\""));

    @Test
    void legacyConstructors_haveNoBook() {
        assertNull(new ContainerContentsEntry(WRITTEN_BOOK, 1, 1).book());
        assertNull(new ContainerContentsEntry(WRITTEN_BOOK, 1, 1, true, 100, true, 25,
            ContainerContentsEntry.SLOT_AUTO, null, true, PotionForm.ANY).book());
        assertFalse(new ContainerContentsEntry(WRITTEN_BOOK, 1, 1).hasBook());
    }

    @Test
    void withBook_setsAndClears() {
        ContainerContentsEntry e = new ContainerContentsEntry(WRITTEN_BOOK, 1, 4).withBook(BOOK);
        assertEquals(BOOK, e.book());
        assertTrue(e.hasBook());
        assertEquals(4, e.weight());
        assertNull(e.withBook(null).book());
    }

    @Test
    void everyWithCopy_keepsTheBook() {
        ContainerContentsEntry e = new ContainerContentsEntry(WRITTEN_BOOK, 1, 1).withBook(BOOK);
        assertEquals(BOOK, e.withWeight(9).book());
        assertEquals(BOOK, e.withCount(4).book());
        assertEquals(BOOK, e.withRandomDurability(false).book());
        assertEquals(BOOK, e.withDurabilityChance(5).book());
        assertEquals(BOOK, e.withRandomEnchantment(false).book());
        assertEquals(BOOK, e.withEnchantmentChance(5).book());
        assertEquals(BOOK, e.withSlotOverride(ContainerContentsEntry.SLOT_FUEL).book());
        assertEquals(BOOK, e.cycleSlotOverride().book());
        assertEquals(BOOK, e.withPotion(null).book());
        assertEquals(BOOK, e.withScaleWithDistance(false).book());
        assertEquals(BOOK, e.cyclePotionForm().book());
    }

    @Test
    void json_roundTrips() {
        String text = ContainerContentsBooks.toJsonText(BOOK);
        assertEquals(BOOK, ContainerContentsBooks.fromJson(JsonParser.parseString(text)));
    }

    @Test
    void nbt_roundTrips() {
        assertEquals(BOOK, ContainerContentsBooks.fromNbt(ContainerContentsBooks.toNbt(BOOK)));
    }

    @Test
    void malformedInput_isNull() {
        assertNull(ContainerContentsBooks.fromJson(null));
        assertNull(ContainerContentsBooks.fromJson(JsonParser.parseString("\"nope\"")));
        assertNull(ContainerContentsBooks.fromJson(JsonParser.parseString("{\"title\":\"x\"}")));
        assertNull(ContainerContentsBooks.fromNbt(null));
        assertNull(ContainerContentsBooks.fromNbt(new net.minecraft.nbt.CompoundTag()));
    }

    @Test
    void storedBook_clampsToVanillaLimits() {
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < 150; i++) pages.add("\"p\"");
        StoredBook b = new StoredBook("x".repeat(50), null, 9, pages);
        assertEquals(StoredBook.MAX_TITLE_LENGTH, b.title().length());
        assertEquals("", b.author());
        assertEquals(StoredBook.MAX_GENERATION, b.generation());
        assertEquals(StoredBook.MAX_PAGES, b.pages().size());
    }
}
