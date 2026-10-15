package games.brennan.dungeontrain.editor.pool;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.ContentsSize;
import games.brennan.dungeontrain.train.ShellPool;
import games.brennan.dungeontrain.train.WholeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PoolTierTest {

    @Test
    @DisplayName("each tier names the same size in all three pools' vocabularies")
    void tiersLineUpAcrossPools() {
        assertEquals(PoolTier.ROOM, PoolTier.of(ContentsSize.ROOM));
        assertEquals(PoolTier.HALF, PoolTier.of(ContentsSize.HALF));
        assertEquals(PoolTier.GROUP, PoolTier.of(ContentsSize.FULL));
        assertEquals(PoolTier.ROOM, PoolTier.of(WholeKind.ROOM));
        assertEquals(PoolTier.HALF, PoolTier.of(WholeKind.HALF));
        assertEquals(PoolTier.GROUP, PoolTier.of(WholeKind.GROUP));
        assertEquals(Optional.of(PoolTier.ROOM), PoolTier.of(ShellPool.ROOM));
        assertEquals(Optional.of(PoolTier.HALF), PoolTier.of(ShellPool.HALF));
        assertEquals(Optional.of(PoolTier.GROUP), PoolTier.of(ShellPool.GROUP));
        for (PoolTier t : PoolTier.values()) {
            assertEquals(t, PoolTier.of(t.contentsSize()));
            assertEquals(t, PoolTier.of(t.wholeKind()));
            assertEquals(Optional.of(t), PoolTier.of(t.shellPool()));
        }
    }

    @Test
    @DisplayName("the flatbed pool has no tier — its shells never move")
    void flatbedHasNoTier() {
        assertTrue(PoolTier.of(ShellPool.FLATBED).isEmpty());
    }

    @Test
    @DisplayName("the group box is the room box times this world's group size")
    void groupBoxIsRoomTimesGroupSize() {
        CarriageDims dims = new CarriageDims(9, 7, 7);
        assertEquals(Optional.of(dims), PoolTier.ROOM.box(dims, 3));
        assertEquals(Optional.of(new CarriageDims(27, 7, 7)), PoolTier.GROUP.box(dims, 3));
        assertEquals(ContentsSize.HALF.shellDims(dims, 3), PoolTier.HALF.box(dims, 3));
    }

    @Test
    @DisplayName("Pool.parse reads the command tokens, case-insensitively")
    void poolParse() {
        assertEquals(Optional.of(Pool.CARRIAGE), Pool.parse("carriage"));
        assertEquals(Optional.of(Pool.WHOLE), Pool.parse(" Whole "));
        assertEquals(Optional.of(Pool.CONTENTS), Pool.parse("CONTENTS"));
        assertTrue(Pool.parse("flatbed").isEmpty());
        assertTrue(Pool.parse(null).isEmpty());
        assertEquals(1, Pool.CONTENTS.frameOffset());
        assertEquals(0, Pool.CARRIAGE.frameOffset());
        assertEquals(0, Pool.WHOLE.frameOffset());
    }
}
