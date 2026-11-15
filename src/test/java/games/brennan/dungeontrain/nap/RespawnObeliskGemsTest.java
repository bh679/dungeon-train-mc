package games.brennan.dungeontrain.nap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link RespawnObeliskGems}: the 0..6 count, its cap, and the save round trip. */
final class RespawnObeliskGemsTest {

    private static final BlockPos OBELISK = new BlockPos(10, 64, -3);

    @Test
    @DisplayName("gems go in up to 6; the rest stay in the player's hand")
    void addCapsAtSix() {
        RespawnObeliskGems gems = new RespawnObeliskGems();
        assertEquals(0, gems.get(OBELISK), "an obelisk with no entry holds none");
        assertEquals(4, gems.add(OBELISK, 4));
        assertEquals(4, gems.get(OBELISK));
        assertEquals(2, gems.add(OBELISK, 64), "only the 2 missing gems are taken");
        assertEquals(6, gems.get(OBELISK));
        assertEquals(0, gems.add(OBELISK, 5), "a full obelisk takes none");
        assertEquals(0, gems.add(OBELISK.above(), 0));
    }

    @Test
    @DisplayName("accepts is pure: missing gems, never negative")
    void accepts() {
        assertEquals(6, RespawnObeliskGems.accepts(0, 64));
        assertEquals(1, RespawnObeliskGems.accepts(5, 1));
        assertEquals(0, RespawnObeliskGems.accepts(6, 3));
        assertEquals(0, RespawnObeliskGems.accepts(9, 3));
        assertEquals(0, RespawnObeliskGems.accepts(2, -1));
    }

    @Test
    @DisplayName("set clamps, 0 and forget drop the entry, and the save round trips")
    void setForgetAndSave() {
        RespawnObeliskGems gems = new RespawnObeliskGems();
        gems.set(OBELISK, 9);
        assertEquals(6, gems.get(OBELISK));
        gems.set(OBELISK.east(), 3);
        gems.set(OBELISK.west(), 2);
        gems.set(OBELISK.west(), 0);
        assertEquals(0, gems.get(OBELISK.west()));

        RespawnObeliskGems back = RespawnObeliskGems.load(gems.save(new CompoundTag(), null));
        assertEquals(6, back.get(OBELISK));
        assertEquals(3, back.get(OBELISK.east()));
        assertEquals(0, back.get(OBELISK.west()));

        back.forget(OBELISK);
        assertEquals(0, back.get(OBELISK));
    }
}
