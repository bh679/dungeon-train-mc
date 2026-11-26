package games.brennan.dungeontrain.client.farcopies;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomBoxModelTest {

    private static final int STONE = 0x707070;
    private static final int WOOD = 0x8F7748;

    /** An Endless Open copy: a floor at y 0 and a ceiling at the top, nothing between. */
    private static TileVoxels openTile(int sx, int sy, int sz) {
        TileVoxels.Builder b = new TileVoxels.Builder(sx, sy, sz);
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                b.setRgb(x, 0, z, STONE);
                b.setRgb(x, sy - 1, z, WOOD);
            }
        }
        return b.build();
    }

    @Test
    void uniformLayersMergeToOneBoxEach() {
        RoomBoxModel m = RoomBoxModel.of(openTile(12, 6, 10));
        assertEquals(List.of(
            new FarBox(0, 0, 0, 12, 1, 10, STONE),
            new FarBox(0, 5, 0, 12, 6, 10, WOOD)), m.detail());
    }

    @Test
    void openTileStripsAreTwoPlanesAndNoLines() {
        RoomBoxModel.Strips s = RoomBoxModel.of(openTile(12, 6, 10)).strips();
        assertEquals(2, s.planes().size());
        assertEquals(new RoomBoxModel.Span(0, 1, 0, 0, STONE), s.planes().get(0));
        assertTrue(s.xStrips().isEmpty());
        assertTrue(s.zStrips().isEmpty());
    }

    @Test
    void aLongWallBecomesAnXStrip() {
        TileVoxels.Builder b = new TileVoxels.Builder(8, 4, 8);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++) b.setRgb(x, y, 0, STONE);
        RoomBoxModel.Strips s = RoomBoxModel.of(b.build()).strips();
        assertTrue(s.planes().isEmpty());
        assertEquals(List.of(new RoomBoxModel.Span(0, 4, 0, 1, STONE)), s.xStrips());
        assertTrue(s.zStrips().isEmpty());
    }

    @Test
    void nearlyEqualColoursMergeAndAverage() {
        TileVoxels.Builder b = new TileVoxels.Builder(2, 1, 1);
        b.setRgb(0, 0, 0, 0x707070).setRgb(1, 0, 0, 0x727272);
        List<FarBox> detail = RoomBoxModel.of(b.build()).detail();
        assertEquals(1, detail.size());
        assertEquals(0x717171, detail.get(0).rgb());
    }

    @Test
    void aNoisyRoomCoarsensUnderTheCap() {
        TileVoxels.Builder b = new TileVoxels.Builder(16, 8, 16);
        for (int y = 0; y < 8; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if ((x + y + z) % 2 == 0) b.setRgb(x, y, z, (x * 37 + z * 91 + y * 13) % 2 == 0 ? STONE : WOOD);
        }
        RoomBoxModel m = RoomBoxModel.of(b.build(), 64);
        assertTrue(m.detail().size() <= 64, "detail " + m.detail().size());
        for (FarBox box : m.detail()) {
            assertTrue(box.maxX() <= 16 && box.maxY() <= 8 && box.maxZ() <= 16, box.toString());
        }
    }

    @Test
    void downsampleKeepsMajorityCells() {
        TileVoxels.Builder b = new TileVoxels.Builder(2, 2, 2);
        b.setRgb(0, 0, 0, STONE).setRgb(1, 0, 0, STONE).setRgb(0, 1, 0, STONE).setRgb(1, 1, 0, STONE);
        TileVoxels half = b.build().downsample(2);
        assertEquals(1, half.sizeX());
        assertTrue(half.solid(0, 0, 0));
        TileVoxels.Builder sparse = new TileVoxels.Builder(2, 2, 2);
        sparse.setRgb(0, 0, 0, STONE);
        assertEquals(0, sparse.build().downsample(2).solidCount());
    }
}
