package games.brennan.dungeontrain.narrative;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A crafted copy of a community book is told apart from the original, which is what keeps the
 * train's vote page off it.
 *
 * <p>The clone is made the way vanilla's book-cloning recipe makes one — the original's whole stack,
 * with a {@link WrittenBookContent} one generation on — because the point being pinned is that the
 * copy carries the original's community-book id and is <em>still</em> recognised as a copy.</p>
 */
class BookCopyTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final int POOL_ID = 42;

    private static ItemStack communityBook() {
        ItemStack stack = BookFactory.buildPlainBook("A Title", "Someone", List.of("page one"));
        SharedBookReadTag.stampId(stack, POOL_ID);
        return stack;
    }

    /** What {@code BookCloningRecipe#assemble} produces from {@code original}. */
    private static ItemStack cloneOf(ItemStack original) {
        WrittenBookContent next = original.get(DataComponents.WRITTEN_BOOK_CONTENT).tryCraftCopy();
        assertNotNull(next, "the book must still be copyable");
        ItemStack copy = original.copyWithCount(1);
        copy.set(DataComponents.WRITTEN_BOOK_CONTENT, next);
        return copy;
    }

    @Test
    @DisplayName("A community book as the train hands it out is an original")
    void lootBookIsOriginal() {
        assertFalse(BookCopy.isCopy(communityBook()));
    }

    @Test
    @DisplayName("A crafted clone is a copy — and still carries the original's community id")
    void cloneIsCopy() {
        ItemStack copy = cloneOf(communityBook());
        assertEquals(POOL_ID, SharedBookReadTag.readId(copy).orElse(-1),
            "the clone inherits the id — which is exactly why the generation has to be checked");
        assertTrue(BookCopy.isCopy(copy));
    }

    @Test
    @DisplayName("A copy of a copy is a copy too")
    void copyOfCopyIsCopy() {
        assertTrue(BookCopy.isCopy(cloneOf(cloneOf(communityBook()))));
    }

    @Test
    @DisplayName("Only generations past the original count")
    void generationRule() {
        assertFalse(BookCopy.isCopyGeneration(0));
        assertTrue(BookCopy.isCopyGeneration(1));
        assertTrue(BookCopy.isCopyGeneration(2));
        assertTrue(BookCopy.isCopyGeneration(3));
    }

    @Test
    @DisplayName("Nothing that is not a written book is a copy")
    void nonBooks() {
        assertFalse(BookCopy.isCopy(null));
        assertFalse(BookCopy.isCopy(ItemStack.EMPTY));
        assertFalse(BookCopy.isCopy(new ItemStack(Items.WRITABLE_BOOK)));
        assertFalse(BookCopy.isCopy(new ItemStack(Items.WRITTEN_BOOK)), "no content component at all");
    }
}
