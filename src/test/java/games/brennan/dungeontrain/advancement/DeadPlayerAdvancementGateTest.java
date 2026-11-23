package games.brennan.dungeontrain.advancement;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DeadPlayerAdvancementGate}: nothing earned on the death screen bar The Great Beyond and capstones. */
final class DeadPlayerAdvancementGateTest {

    private static final ResourceLocation ECHO_ENCOUNTER =
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/echo_encounter");
    private static final ResourceLocation VANILLA_KILL =
        ResourceLocation.withDefaultNamespace("adventure/kill_a_mob");
    private static final ResourceLocation CAPSTONE =
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/completionist");

    private static final Predicate<ResourceLocation> NONE_DERIVED = id -> false;
    private static final Predicate<ResourceLocation> CAPSTONE_DERIVED = CAPSTONE::equals;

    @Test
    @DisplayName("alive (or in the tick of death): every award passes")
    void aliveAllowsEverything() {
        assertFalse(DeadPlayerAdvancementGate.refuses(false, ECHO_ENCOUNTER, NONE_DERIVED));
        assertFalse(DeadPlayerAdvancementGate.refuses(false, VANILLA_KILL, NONE_DERIVED));
    }

    @Test
    @DisplayName("on the death screen: ordinary advancements of any namespace are refused")
    void deadRefusesOrdinary() {
        assertTrue(DeadPlayerAdvancementGate.refuses(true, ECHO_ENCOUNTER, NONE_DERIVED));
        assertTrue(DeadPlayerAdvancementGate.refuses(true, VANILLA_KILL, NONE_DERIVED));
    }

    @Test
    @DisplayName("on the death screen: The Great Beyond still passes")
    void deadAllowsGreatBeyond() {
        assertFalse(DeadPlayerAdvancementGate.refuses(true, SurveyAdvancement.ID, NONE_DERIVED));
    }

    @Test
    @DisplayName("on the death screen: advancements granted by other advancements still pass")
    void deadAllowsGrantedByOthers() {
        assertFalse(DeadPlayerAdvancementGate.refuses(true, CAPSTONE, CAPSTONE_DERIVED));
        assertTrue(DeadPlayerAdvancementGate.refuses(true, ECHO_ENCOUNTER, CAPSTONE_DERIVED));
    }

    @Test
    @DisplayName("a null id is never refused")
    void nullIdPasses() {
        assertFalse(DeadPlayerAdvancementGate.refuses(true, null, NONE_DERIVED));
    }
}
