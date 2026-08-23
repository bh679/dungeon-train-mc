package games.brennan.dungeontrain.narrative;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two author-action markers a book carries between openings. */
class BookOwnActionTagsTest {

    @Test
    @DisplayName("Private round-trips BOTH ways — withdrawing is reversible")
    void privateRoundTripsBothWays() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        assertFalse(BookPrivateTag.isPrivate(stack), "an unstamped book is in circulation");

        BookPrivateTag.stamp(stack, true);
        assertTrue(BookPrivateTag.isPrivate(stack));

        // The half that separates this from BookReportTag: "put back" must be distinguishable from
        // "never touched" on a stack the server has already stamped.
        BookPrivateTag.stamp(stack, false);
        assertFalse(BookPrivateTag.isPrivate(stack));
    }

    @Test
    @DisplayName("Protest is one-way and idempotent")
    void protestIsOneWay() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        assertFalse(BookProtestTag.isProtested(stack));
        BookProtestTag.stamp(stack);
        assertTrue(BookProtestTag.isProtested(stack));
        BookProtestTag.stamp(stack);
        assertTrue(BookProtestTag.isProtested(stack));
    }

    @Test
    @DisplayName("The two markers are independent of each other and of the moderation state")
    void markersDoNotCollide() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        BookPrivateTag.stamp(stack, true);
        BookProtestTag.stamp(stack);
        BookModerationTag.stamp(stack, BookModerationState.DISLIKED);
        assertTrue(BookPrivateTag.isPrivate(stack));
        assertTrue(BookProtestTag.isProtested(stack));
        org.junit.jupiter.api.Assertions.assertEquals(
            BookModerationState.DISLIKED, BookModerationTag.read(stack));
    }

    @Test
    @DisplayName("An empty stack is never stamped and never reads as acted on")
    void emptyStackIsInert() {
        ItemStack empty = ItemStack.EMPTY;
        BookPrivateTag.stamp(empty, true);
        BookProtestTag.stamp(empty);
        assertFalse(BookPrivateTag.isPrivate(empty));
        assertFalse(BookProtestTag.isProtested(empty));
    }

    @Test
    @DisplayName("An ordinary community book carries no moderation tag at all")
    void publicBooksStayUnstamped() {
        // PUBLIC stamps nothing, so a stranger's book is byte-identical to what it was before any of
        // this existed.
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        BookModerationTag.stamp(stack, BookModerationState.PUBLIC);
        org.junit.jupiter.api.Assertions.assertEquals(
            BookModerationState.PUBLIC, BookModerationTag.read(stack));
        assertTrue(stack.getComponents().isEmpty() || stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA) == null,
            "nothing was written to the stack");
    }
}
