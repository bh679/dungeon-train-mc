package games.brennan.dungeontrain.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link NetherPortalLinks}: pairs are symmetric, unlinking clears both ends, and the NBT round-trips. */
final class NetherPortalLinksTest {

    @Test
    @DisplayName("link is symmetric and unlink clears both directions")
    void symmetricLinks() {
        NetherPortalLinks links = new NetherPortalLinks();
        BlockPos a = new BlockPos(540, 120, 40);
        BlockPos b = new BlockPos(4100, 70, 48);
        links.link(a, b);
        assertEquals(b, links.partner(a));
        assertEquals(a, links.partner(b));
        links.unlink(b);
        assertNull(links.partner(a));
        assertNull(links.partner(b));
        assertEquals(0, links.size());
    }

    @Test
    @DisplayName("relinking a frame replaces its old partner")
    void relink() {
        NetherPortalLinks links = new NetherPortalLinks();
        BlockPos a = new BlockPos(1, 2, 3);
        BlockPos b = new BlockPos(4, 5, 6);
        BlockPos c = new BlockPos(7, 8, 9);
        links.link(a, b);
        links.link(a, c);
        assertEquals(c, links.partner(a));
        assertEquals(a, links.partner(c));
    }

    @Test
    @DisplayName("save and load round-trip the pairs")
    void nbtRoundTrip() {
        NetherPortalLinks links = new NetherPortalLinks();
        links.link(new BlockPos(-10, 64, 7), new BlockPos(26829, 114, 28));
        CompoundTag tag = links.save(new CompoundTag(), null);
        NetherPortalLinks loaded = NetherPortalLinks.load(tag);
        assertEquals(new BlockPos(26829, 114, 28), loaded.partner(new BlockPos(-10, 64, 7)));
        assertEquals(new BlockPos(-10, 64, 7), loaded.partner(new BlockPos(26829, 114, 28)));
        assertEquals(2, loaded.size());
    }
}
