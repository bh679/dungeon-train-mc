package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins {@link TunnelTrainEnvelope} — the cells of a tunnel template the train drives through. */
final class TunnelTrainEnvelopeTest {

    @Test
    @DisplayName("Default 7×7 train: x0..9, y2..8 (above bed + rails), z3..9 (centred on z6)")
    void defaultTrain() {
        assertEquals(new BoundingBox(0, 2, 3, 9, 8, 9), TunnelTrainEnvelope.localBox(CarriageDims.DEFAULT));
    }

    @Test
    @DisplayName("Envelope is centred on the template's Z mirror centre for the default width")
    void centredOnMirrorCentre() {
        BoundingBox box = TunnelTrainEnvelope.localBox(CarriageDims.DEFAULT);
        assertEquals((TunnelPlacer.WIDTH - 1) / 2, (box.minZ() + box.maxZ()) / 2);
    }

    @Test
    @DisplayName("An oversize train is clipped to the template box")
    void clippedToPlot() {
        BoundingBox box = TunnelTrainEnvelope.localBox(new CarriageDims(9, 20, 20));
        assertEquals(TunnelPlacer.WIDTH - 1, box.maxZ());
        assertEquals(TunnelPlacer.HEIGHT - 1, box.maxY());
    }

    @Test
    @DisplayName("World box is the local box moved to the plot origin")
    void worldBox() {
        BoundingBox box = TunnelTrainEnvelope.worldBox(new BlockPos(9, 230, 18), CarriageDims.DEFAULT);
        assertEquals(new BoundingBox(9, 232, 21, 18, 238, 27), box);
    }
}
