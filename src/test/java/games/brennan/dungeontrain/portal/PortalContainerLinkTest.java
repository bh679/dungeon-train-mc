package games.brennan.dungeontrain.portal;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item movement behind {@link PortalContainerLink#consolidate}.
 *
 * <p>Only the arithmetic is tested here — the pairing and the block-entity lookups need a live
 * level and are checked in game. This half is where a mistake silently eats a shulker's contents:
 * the whole point of gathering before a break is that the copy the mirror is about to clear has
 * nothing left in it, and that is only true if <b>everything</b> either moved or came back as a
 * leftover for the caller to drop.</p>
 *
 * <p>Needs a headless Minecraft bootstrap so item registries and {@code ItemStack} resolve.</p>
 */
final class PortalContainerLinkTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int totalOf(SimpleContainer container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            total += container.getItem(slot).getCount();
        }
        return total;
    }

    @Test
    @DisplayName("an empty target takes everything, and the source is left empty")
    void emptyTargetTakesEverything() {
        SimpleContainer from = new SimpleContainer(27);
        from.setItem(0, new ItemStack(Items.DIAMOND, 12));
        from.setItem(5, new ItemStack(Items.OAK_LOG, 30));
        SimpleContainer to = new SimpleContainer(27);

        List<ItemStack> leftovers = PortalContainerLink.consolidate(from, to);

        assertTrue(leftovers.isEmpty(), "nothing should have been left over");
        assertTrue(PortalContainerLink.isEmpty(from), "source must end empty");
        assertEquals(42, totalOf(to));
    }

    @Test
    @DisplayName("matching stacks are topped up before empty slots are used")
    void mergesIntoMatchingStacks() {
        SimpleContainer from = new SimpleContainer(27);
        from.setItem(0, new ItemStack(Items.DIAMOND, 20));
        SimpleContainer to = new SimpleContainer(27);
        to.setItem(3, new ItemStack(Items.DIAMOND, 50));

        List<ItemStack> leftovers = PortalContainerLink.consolidate(from, to);

        assertTrue(leftovers.isEmpty());
        assertTrue(PortalContainerLink.isEmpty(from));
        assertEquals(64, to.getItem(3).getCount(), "the existing stack fills to its cap first");
        assertEquals(6, totalOf(to) - 64, "the remaining 6 spill into a free slot");
        assertEquals(70, totalOf(to));
    }

    @Test
    @DisplayName("a full target returns the remainder rather than deleting it")
    void fullTargetReturnsLeftovers() {
        SimpleContainer from = new SimpleContainer(27);
        from.setItem(0, new ItemStack(Items.DIAMOND, 10));
        SimpleContainer to = new SimpleContainer(1);
        to.setItem(0, new ItemStack(Items.OAK_LOG, 64));

        List<ItemStack> leftovers = PortalContainerLink.consolidate(from, to);

        assertEquals(1, leftovers.size(), "the diamonds must come back to the caller");
        assertEquals(Items.DIAMOND, leftovers.get(0).getItem());
        assertEquals(10, leftovers.get(0).getCount(), "all ten, none silently dropped");
        assertTrue(PortalContainerLink.isEmpty(from), "source is emptied either way");
        assertEquals(64, totalOf(to), "target is untouched");
    }

    @Test
    @DisplayName("a partly-full target takes what fits and returns the rest")
    void partialFitSplits() {
        SimpleContainer from = new SimpleContainer(27);
        from.setItem(0, new ItemStack(Items.DIAMOND, 40));
        SimpleContainer to = new SimpleContainer(1);
        to.setItem(0, new ItemStack(Items.DIAMOND, 60));

        List<ItemStack> leftovers = PortalContainerLink.consolidate(from, to);

        assertEquals(64, totalOf(to), "the one slot tops up to its cap");
        assertEquals(1, leftovers.size());
        assertEquals(36, leftovers.get(0).getCount(), "the other 36 come back");
        assertTrue(PortalContainerLink.isEmpty(from));
    }

    @Test
    @DisplayName("an empty source is a no-op")
    void emptySourceChangesNothing() {
        SimpleContainer from = new SimpleContainer(27);
        SimpleContainer to = new SimpleContainer(27);
        to.setItem(0, new ItemStack(Items.DIAMOND, 3));

        assertTrue(PortalContainerLink.consolidate(from, to).isEmpty());
        assertEquals(3, totalOf(to));
    }
}
