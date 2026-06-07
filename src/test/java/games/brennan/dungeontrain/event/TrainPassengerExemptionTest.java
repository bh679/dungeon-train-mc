package games.brennan.dungeontrain.event;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link TrainTickEvents#isTrainPassengerId} — the
 * kill-ahead exemption that spares train-passenger entities (e.g. PlayerMob)
 * from being discarded the instant they fall off a carriage.
 *
 * <p>Tests only the registry-free seam, so no Minecraft bootstrap is needed —
 * {@link ResourceLocation#fromNamespaceAndPath} is constructed directly, the
 * same pattern other pure tests use (e.g. {@code VariantClipboardItemPoolTest}).</p>
 */
final class TrainPassengerExemptionTest {

    @Test
    @DisplayName("playermob:player_mob is spared")
    void playerMob_isSpared() {
        assertTrue(TrainTickEvents.isTrainPassengerId(
            ResourceLocation.fromNamespaceAndPath("playermob", "player_mob")));
    }

    @Test
    @DisplayName("any playermob:* variant is spared — namespace match future-proofs new variants")
    void playerMobNamespaceVariant_isSpared() {
        assertTrue(TrainTickEvents.isTrainPassengerId(
            ResourceLocation.fromNamespaceAndPath("playermob", "some_future_variant")));
    }

    @Test
    @DisplayName("vanilla mobs are still culled")
    void vanillaMob_notSpared() {
        assertFalse(TrainTickEvents.isTrainPassengerId(
            ResourceLocation.fromNamespaceAndPath("minecraft", "zombie")));
    }

    @Test
    @DisplayName("DT's own non-passenger entities are still culled")
    void dungeonTrainEntity_notSpared() {
        assertFalse(TrainTickEvents.isTrainPassengerId(
            ResourceLocation.fromNamespaceAndPath("dungeontrain", "anything")));
    }

    @Test
    @DisplayName("null id (unregistered type) is not spared and does not NPE")
    void nullId_notSpared() {
        assertFalse(TrainTickEvents.isTrainPassengerId(null));
    }
}
