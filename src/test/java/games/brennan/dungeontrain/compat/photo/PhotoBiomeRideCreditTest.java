package games.brennan.dungeontrain.compat.photo;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link PhotoBiomeRideCredit#toCredit}: which photographed biomes count towards Terra Omnia. */
class PhotoBiomeRideCreditTest {

    private static final ResourceLocation PLAINS = ResourceLocation.withDefaultNamespace("plains");
    private static final ResourceLocation DESERT = ResourceLocation.withDefaultNamespace("desert");

    @Test
    @DisplayName("only biomes Terra Omnia asks for are credited; excluded, unknown and malformed ids are dropped")
    void keepsOnlyTerraOmniaBiomes() {
        Set<ResourceLocation> credit = PhotoBiomeRideCredit.toCredit(
            List.of("minecraft:plains", "minecraft:desert", "minecraft:the_void", "gone:biome", "Not A Biome!"),
            Set.of(PLAINS, DESERT));
        assertEquals(Set.of(PLAINS, DESERT), credit);
    }

    @Test
    @DisplayName("no photos, no credit")
    void emptyCreditsNothing() {
        assertEquals(Set.of(), PhotoBiomeRideCredit.toCredit(List.of(), Set.of(PLAINS)));
    }
}
