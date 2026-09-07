package games.brennan.dungeontrain.builder.relay;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The size gate on a preview.
 *
 * <p>Worth pinning because the failure it prevents is not a wrong picture but a refused packet: a
 * custom payload is capped at a megabyte, and a build big enough to blow through that would take
 * the send down rather than draw a slate.</p>
 */
final class BuilderRelayPreviewTest {

    private static CompoundTag structureWith(int blocks) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (int i = 0; i < blocks; i++) {
            list.add(new CompoundTag());
        }
        tag.put("blocks", list);
        return tag;
    }

    @Test
    @DisplayName("an ordinary build encodes and reads back")
    void ordinaryBuildFits() {
        // A 9x7x7 carriage is a few hundred blocks; even a dense one is nowhere near the cap.
        byte[] bytes = BuilderRelayPreview.encode(structureWith(441));
        assertNotNull(bytes);
        assertNotNull(BuilderRelayPreview.decode(bytes));
    }

    @Test
    @DisplayName("a build past the block cap is not sent at all")
    void hugeBuildIsRefused() {
        assertNull(BuilderRelayPreview.encode(structureWith(12_001)));
    }

    @Test
    @DisplayName("a build under the block cap but too heavy to send is refused too")
    void fatBuildIsRefused() {
        // The block count is the cheap check and it is not the one that matters: what dropped the
        // player out of their world was a tag the client's NBT accounter would not spend the budget
        // to read, which is a different quantity from either the block count or the byte count.
        CompoundTag tag = structureWith(0);
        tag.putByteArray("sidecar", new byte[600 * 1024]);
        assertNull(BuilderRelayPreview.encode(tag));
    }

    @Test
    @DisplayName("bytes that are not NBT come back as no picture, not as an exception")
    void rubbishBytesAreJustNoPicture() {
        assertNull(BuilderRelayPreview.decode(new byte[] {1, 2, 3, 4}));
        assertNull(BuilderRelayPreview.decode(new byte[0]));
        assertNull(BuilderRelayPreview.decode(null));
    }

    @Test
    @DisplayName("a structure with no blocks list is not treated as huge")
    void emptyStructureIsNotRefused() {
        assertNotNull(BuilderRelayPreview.encode(new CompoundTag()));
    }
}
