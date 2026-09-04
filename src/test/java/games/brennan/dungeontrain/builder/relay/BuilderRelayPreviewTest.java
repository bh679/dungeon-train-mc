package games.brennan.dungeontrain.builder.relay;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("an ordinary build is small enough to send")
    void ordinaryBuildFits() {
        // A 9x7x7 carriage is a few hundred blocks; even a dense one is nowhere near the cap.
        assertFalse(BuilderRelayPreview.oversized(structureWith(441)));
        assertFalse(BuilderRelayPreview.oversized(structureWith(12_000)));
    }

    @Test
    @DisplayName("a build past the cap is not sent at all")
    void hugeBuildIsRefused() {
        assertTrue(BuilderRelayPreview.oversized(structureWith(12_001)));
    }

    @Test
    @DisplayName("a structure with no blocks list is not treated as huge")
    void emptyStructureIsNotOversized() {
        assertFalse(BuilderRelayPreview.oversized(new CompoundTag()));
    }
}
