package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.NetherPortalAdvancements.Crossing;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NetherPortalAdvancements}: only trips that change Nether-ness count, and they name vanilla's pair. */
final class NetherPortalAdvancementsTest {

    @Test
    @DisplayName("overworld → Nether is the vanilla Nether entry")
    void intoNether() {
        Optional<Crossing> c = NetherPortalAdvancements.crossing(false, true);
        assertEquals(Optional.of(Crossing.INTO_NETHER), c);
        assertEquals(Level.OVERWORLD, c.get().from());
        assertEquals(Level.NETHER, c.get().to());
    }

    @Test
    @DisplayName("Nether → overworld is the vanilla Nether exit")
    void outOfNether() {
        Optional<Crossing> c = NetherPortalAdvancements.crossing(true, false);
        assertEquals(Optional.of(Crossing.OUT_OF_NETHER), c);
        assertEquals(Level.NETHER, c.get().from());
        assertEquals(Level.OVERWORLD, c.get().to());
    }

    @Test
    @DisplayName("same-kind trips are no crossing")
    void sameKind() {
        assertTrue(NetherPortalAdvancements.crossing(true, true).isEmpty());
        assertTrue(NetherPortalAdvancements.crossing(false, false).isEmpty());
    }
}
