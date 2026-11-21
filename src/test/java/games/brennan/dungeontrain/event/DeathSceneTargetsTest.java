package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathSceneTargetsTest {

    @Test
    void onlyRealTemplatesCount() {
        assertTrue(DeathSceneTargets.hasContents("zombieroom3"));
        assertFalse(DeathSceneTargets.hasContents(""));
        assertFalse(DeathSceneTargets.hasContents(null));
        assertFalse(DeathSceneTargets.hasContents("(relay build)"));
    }

    @Test
    void slotCentreStepsOneCarriageLengthPerIndex() {
        CarriageDims dims = CarriageDims.clamp(9, 7, 7);
        BlockPos origin = new BlockPos(20481020, 64, 20481030);
        Vector3d first = DeathSceneTargets.slotCentre(origin, dims, 18, 3, 18);
        Vector3d third = DeathSceneTargets.slotCentre(origin, dims, 18, 3, 20);
        assertEquals(dims.length() * 2, third.x - first.x, 1e-9);
        assertEquals(origin.getY() + dims.height() / 2.0, first.y, 1e-9);
        assertEquals(origin.getZ() + dims.width() / 2.0, first.z, 1e-9);
    }
}
