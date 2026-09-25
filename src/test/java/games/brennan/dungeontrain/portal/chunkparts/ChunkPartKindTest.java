package games.brennan.dungeontrain.portal.chunkparts;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPartKindTest {

    private static final int L = ChunkPartKind.LENGTH;
    private static final int H = ChunkPartKind.HEIGHT;
    private static final int W = ChunkPartKind.WIDTH;

    @Test
    @DisplayName("The four kinds tile the two-thick hull exactly, no cell twice and none outside it")
    void kinds_tileTheHull() {
        Map<BlockPos, ChunkPartKind> owner = new HashMap<>();
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            Vec3i size = kind.size();
            for (ChunkPartKind.Placement p : kind.placements()) {
                for (int x = 0; x < size.getX(); x++) {
                    for (int y = 0; y < size.getY(); y++) {
                        for (int z = 0; z < size.getZ(); z++) {
                            BlockPos at = kind.roomLocal(p, x, y, z);
                            ChunkPartKind before = owner.put(at, kind);
                            assertNull(before, at + " written by both " + before + " and " + kind);
                        }
                    }
                }
            }
        }
        int hull = 0;
        for (int x = -1; x <= L; x++) {
            for (int y = -1; y <= H; y++) {
                for (int z = -1; z <= W; z++) {
                    boolean inHull = x <= 0 || x >= L - 1 || y <= 0 || y >= H - 1 || z <= 0 || z >= W - 1;
                    if (inHull) hull++;
                    assertEquals(inHull, owner.containsKey(new BlockPos(x, y, z)), "cell " + x + "," + y + "," + z);
                }
            }
        }
        assertEquals(hull, owner.size());
    }

    @Test
    @DisplayName("Local x/z = 0 of a door or wall is the outer layer on both sides of the room")
    void mirroredPlacements_keepOuterAtLocalZero() {
        for (ChunkPartKind kind : new ChunkPartKind[] {ChunkPartKind.DOORS, ChunkPartKind.WALLS}) {
            for (ChunkPartKind.Placement p : kind.placements()) {
                BlockPos outer = kind.roomLocal(p, 0, 5, 0);
                Vec3i size = kind.size();
                BlockPos inner = kind == ChunkPartKind.DOORS
                    ? kind.roomLocal(p, 1, 5, 5) : kind.roomLocal(p, 5, 5, 1);
                assertTrue(ChunkPartKind.isOuter(outer.getX(), outer.getY(), outer.getZ()), kind + " " + p);
                assertFalse(ChunkPartKind.isOuter(inner.getX(), inner.getY(), inner.getZ()), kind + " " + p);
                assertEquals(2, kind == ChunkPartKind.DOORS ? size.getX() : size.getZ());
            }
        }
        assertSame(Mirror.FRONT_BACK, ChunkPartKind.DOORS.placements().get(1).mirror());
        assertSame(Mirror.LEFT_RIGHT, ChunkPartKind.WALLS.placements().get(1).mirror());
    }

    @Test
    @DisplayName("Sizes are the hull's, and ids read back")
    void sizesAndIds() {
        assertEquals(new Vec3i(2, 34, 18), ChunkPartKind.DOORS.size());
        assertEquals(new Vec3i(14, 34, 2), ChunkPartKind.WALLS.size());
        assertEquals(new Vec3i(14, 2, 14), ChunkPartKind.FLOOR.size());
        assertEquals(new Vec3i(14, 2, 14), ChunkPartKind.ROOF.size());
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            assertSame(kind, ChunkPartKind.fromId(kind.id()));
        }
        assertSame(ChunkPartKind.WALLS, ChunkPartKind.fromId("Walls"));
        assertNull(ChunkPartKind.fromId("pillar"));
    }
}
