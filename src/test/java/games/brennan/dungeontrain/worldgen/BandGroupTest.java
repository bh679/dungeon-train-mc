package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The editor's band families partition every {@link TrainPhase}. */
final class BandGroupTest {

    @Test
    @DisplayName("group masks are disjoint and together cover ALL_MASK")
    void groupsPartitionAllBands() {
        int union = 0;
        for (BandGroup g : BandGroup.values()) {
            assertEquals(0, union & g.mask(), g + " overlaps an earlier group");
            union |= g.mask();
        }
        assertEquals(TrainPhase.ALL_MASK, union);
    }

    @Test
    @DisplayName("members() lists exactly the bands whose group() is that family")
    void membersMatchGroup() {
        assertEquals(List.of(TrainPhase.CHUNCKS, TrainPhase.SPHERES, TrainPhase.STACKS),
            BandGroup.FRAGMENTS.members());
        assertEquals(List.of(TrainPhase.LARGE_BIOMES, TrainPhase.AMPLIFIED), BandGroup.PRESETS.members());
        for (BandGroup g : BandGroup.values()) {
            for (TrainPhase p : g.members()) assertEquals(g, p.group());
        }
    }

    @Test
    @DisplayName("state() reads all / some / none of a group")
    void triState() {
        assertEquals(BandGroup.State.ALL, BandGroup.CORE.state(TrainPhase.ALL_MASK));
        assertEquals(BandGroup.State.SOME, BandGroup.CORE.state(TrainPhase.NETHER.bit()));
        assertEquals(BandGroup.State.NONE, BandGroup.LEGACY.state(TrainPhase.NETHER.bit()));
    }
}
