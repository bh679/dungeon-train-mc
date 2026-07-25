package games.brennan.dungeontrain.train;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extraction tests for {@link CarriageTextScan} at the item level (custom names + book pages). Sign /
 * lectern / container dispatch is exercised in-game (Gate 2). Needs a headless Minecraft bootstrap so
 * {@link ItemStack} + data components resolve.
 */
class CarriageTextScanTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static String scan(ItemStack stack) {
        StringBuilder sb = new StringBuilder();
        CarriageTextScan.appendItem(stack, sb);
        return sb.toString();
    }

    @Test
    void extractsCustomNameButNotDefaultItemName() {
        ItemStack named = new ItemStack(Items.STONE);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("naughty word"));
        String out = scan(named);
        assertTrue(out.contains("naughty word"), "custom name should be scanned");

        // A plain item with no custom name contributes nothing (its default name is not player content).
        assertTrue(scan(new ItemStack(Items.STONE)).isEmpty(), "default item name must be skipped");
    }

    @Test
    void extractsWrittenBookTitleAndPages() {
        WrittenBookContent content = new WrittenBookContent(
                Filterable.passThrough("Bad Title"),
                "author",
                0,
                List.of(Filterable.passThrough(Component.literal("offensive page one")),
                        Filterable.passThrough(Component.literal("page two"))),
                true);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, content);

        String out = scan(book);
        assertTrue(out.contains("Bad Title"), "written book title should be scanned");
        assertTrue(out.contains("offensive page one"), "written book pages should be scanned");
        assertTrue(out.contains("page two"));
    }

    @Test
    void extractsBookAndQuillPages() {
        WritableBookContent content = new WritableBookContent(
                List.of(Filterable.passThrough("draft line")));
        ItemStack quill = new ItemStack(Items.WRITABLE_BOOK);
        quill.set(DataComponents.WRITABLE_BOOK_CONTENT, content);

        assertTrue(scan(quill).contains("draft line"), "book & quill pages should be scanned");
    }

    @Test
    void emptyStackYieldsNoText() {
        assertTrue(scan(ItemStack.EMPTY).isEmpty());
        assertFalse(scan(ItemStack.EMPTY).contains(" "));
    }
}
