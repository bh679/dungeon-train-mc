package games.brennan.dungeontrain.compat.vista;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TvBlocksTest {

    @Test
    void onlyTheVistaTelevisionIsATv() {
        assertTrue(TvBlocks.isTelevision(ResourceLocation.fromNamespaceAndPath("vista", "television")));
        assertFalse(TvBlocks.isTelevision(ResourceLocation.fromNamespaceAndPath("vista", "mirror")));
        assertFalse(TvBlocks.isTelevision(ResourceLocation.fromNamespaceAndPath("minecraft", "chest")));
        assertFalse(TvBlocks.isTelevision((ResourceLocation) null));
    }
}
