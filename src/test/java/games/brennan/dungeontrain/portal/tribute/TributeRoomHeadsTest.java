package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.world.item.component.ResolvableProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whose face a tribute room's heads wear. */
class TributeRoomHeadsTest {

    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    @DisplayName("A dashless owner id reads back as the uuid the room was saved under")
    void dashlessParses() {
        assertEquals(ID, TributeRoomHeads.parseDashless("123e4567e89b12d3a456426614174000"));
        assertEquals(ID, TributeRoomHeads.parseDashless(ID.toString()));
        assertNull(TributeRoomHeads.parseDashless("not-an-id"));
        assertNull(TributeRoomHeads.parseDashless(null));
    }

    @Test
    @DisplayName("An offline owner's head carries their name and uuid for the skull to resolve")
    void lazyProfileCarriesIdentity() {
        ResolvableProfile p = TributeRoomHeads.lazy(ID, "Wren");
        assertEquals(Optional.of("Wren"), p.name());
        assertEquals(Optional.of(ID), p.id());
        assertTrue(p.properties().isEmpty());
    }

    @Test
    @DisplayName("A name that is not a username leaves the head untitled, never un-faced")
    void badNameStaysUntitled() {
        ResolvableProfile p = TributeRoomHeads.lazy(ID, "not a name!");
        assertTrue(p.name().isEmpty());
        assertEquals(Optional.of(ID), p.id());
    }
}
