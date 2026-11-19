package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link HeadAim}: a player head joining a variant cell looks into the room, with a little spread. */
final class HeadAimTest {

    /** Compass slots in mask order: s sw w nw n ne e se. */
    private static final int S = 0, SW = 1, W = 2, NW = 3, N = 4, NE = 5, E = 6, SE = 7;
    /** A square 5×5 room: centre at (2, 2). */
    private static final Vec3i ROOM = new Vec3i(5, 5, 5);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int mask(int... slots) {
        int m = 0;
        for (int s : slots) m |= 1 << s;
        return m;
    }

    private static void assertFan(int expectedMask, BlockPos local) {
        VariantRotation r = HeadAim.towardCentre(local, ROOM);
        assertEquals(VariantRotation.Mode.OPTIONS, r.mode());
        assertEquals(expectedMask, r.dirMask(), "fan at " + local.toShortString());
    }

    @Test
    @DisplayName("a head on each wall faces the centre, give or take 90 degrees")
    void wallsFaceIn() {
        assertFan(mask(W, SW, S, SE, E), new BlockPos(2, 1, 0)); // north wall looks south
        assertFan(mask(E, NE, N, NW, W), new BlockPos(2, 1, 4)); // south wall looks north
        assertFan(mask(N, NE, E, SE, S), new BlockPos(0, 1, 2)); // west wall looks east
        assertFan(mask(S, SW, W, NW, N), new BlockPos(4, 1, 2)); // east wall looks west
    }

    @Test
    @DisplayName("a corner head faces the diagonal")
    void cornerFacesDiagonal() {
        assertFan(mask(NE, E, SE, S, SW), new BlockPos(0, 1, 0)); // north-west corner looks south-east
    }

    @Test
    @DisplayName("a head at the very centre may face any way")
    void centreGetsAllEight() {
        assertFan(0xFF, new BlockPos(2, 1, 2));
    }

    @Test
    @DisplayName("aim turns a player head to face in and leaves other blocks alone")
    void aimOnlyTouchesPlayerHeads() {
        VariantState head = VariantState.of(Blocks.PLAYER_HEAD.defaultBlockState());
        VariantState aimed = HeadAim.aim(head, new BlockPos(2, 1, 4), ROOM);
        assertEquals(mask(E, NE, N, NW, W), aimed.rotation().dirMask());
        assertEquals(N * 2, aimed.state().getValue(BlockStateProperties.ROTATION_16), "state turned to face north");

        VariantState stone = VariantState.of(Blocks.STONE.defaultBlockState());
        assertSame(stone, HeadAim.aim(stone, new BlockPos(2, 1, 4), ROOM));
        VariantState creeper = VariantState.of(Blocks.CREEPER_HEAD.defaultBlockState());
        assertSame(creeper, HeadAim.aim(creeper, new BlockPos(2, 1, 4), ROOM));
    }
}
