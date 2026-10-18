package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The AFK rule's exemption list: every entry is a real advancement, and only those are let through. */
final class AfkAdvancementGateTest {

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath("dungeontrain", path);
    }

    @Test
    @DisplayName("every exempt id is a shipped advancement, so a rename can't silently drop one")
    void everyExemptIdExists() {
        Path dir = RepoPaths.advancements();
        for (ResourceLocation id : AfkAdvancementGate.EXEMPT) {
            assertEquals("dungeontrain", id.getNamespace(), id.toString());
            assertTrue(Files.isRegularFile(dir.resolve(id.getPath() + ".json")),
                "no advancement file for exempt id " + id);
        }
    }

    @Test
    @DisplayName("Sound Check is exempt: its volume sliders all sit on pause screens")
    void soundCheckIsExempt() {
        assertTrue(AfkAdvancementGate.isExempt(rl("dungeon_train/changed_engine_volume")));
    }

    @Test
    @DisplayName("re-firing scan advancements stay gated")
    void scanAdvancementsAreNotExempt() {
        assertFalse(AfkAdvancementGate.isExempt(rl("dungeon_train/reached_nether")));
        assertFalse(AfkAdvancementGate.isExempt(rl("dungeon_train/root")));
    }
}
