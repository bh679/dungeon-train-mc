package games.brennan.dungeontrain.editor.pool;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.WholeCarriage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PoolRefTest {

    @Test
    @DisplayName("whole templates map to the whole pool at their kind's tier")
    void wholeKindsMapToTiers() {
        assertEquals(Optional.of(new PoolRef(Pool.WHOLE, PoolTier.ROOM, "cargo")),
            PoolRef.of(new Template.WholeCarriage(new WholeCarriage("cargo"))));
        assertEquals(Optional.of(new PoolRef(Pool.WHOLE, PoolTier.HALF, "bunk")),
            PoolRef.of(new Template.WholeHalf(new WholeCarriage("bunk"))));
        assertEquals(Optional.of(new PoolRef(Pool.WHOLE, PoolTier.GROUP, "triple")),
            PoolRef.of(new Template.CarriageGroup(new CarriageGroup("triple"))));
    }

    @Test
    @DisplayName("flatbed shells and templates of other kinds have no address")
    void unmovableKindsHaveNoAddress() {
        assertTrue(PoolRef.of(new Template.Carriage(CarriageVariant.of(CarriagePlacer.CarriageType.FLATBED))).isEmpty());
        assertTrue(PoolRef.of(new Template.Track("plain")).isEmpty());
        assertTrue(PoolRef.of(null).isEmpty());
    }

    @Test
    @DisplayName("the X-menu category follows pool and tier; ids are lowercased")
    void categoryAndCase() {
        assertEquals(PlotCategory.WHOLE_HALF, new PoolRef(Pool.WHOLE, PoolTier.HALF, "Bunk").plotCategory());
        assertEquals(PlotCategory.WHOLE_GROUP, new PoolRef(Pool.WHOLE, PoolTier.GROUP, "x").plotCategory());
        assertEquals(PlotCategory.CARRIAGES, new PoolRef(Pool.CARRIAGE, PoolTier.HALF, "x").plotCategory());
        assertEquals(PlotCategory.CONTENTS, new PoolRef(Pool.CONTENTS, PoolTier.GROUP, "x").plotCategory());
        assertEquals("bunk", new PoolRef(Pool.WHOLE, PoolTier.HALF, "Bunk").id());
        PoolRef moved = new PoolRef(Pool.CARRIAGE, PoolTier.HALF, "a").in(Pool.CONTENTS).named("b");
        assertEquals(new PoolRef(Pool.CONTENTS, PoolTier.HALF, "b"), moved);
        assertEquals("whole_half", new PoolRef(Pool.WHOLE, PoolTier.HALF, "x").dirtyCategoryId());
        assertEquals("carriages", new PoolRef(Pool.CARRIAGE, PoolTier.ROOM, "x").dirtyCategoryId());
    }
}
