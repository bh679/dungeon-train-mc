package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ServerLevelTypePolicyTest {

    private static final String WOVER = "wover:normal";

    @Test
    @DisplayName("an explicit Dungeon Train preset survives WorldWeaver's forced default")
    void dtPresetKept() {
        assertEquals(Optional.of("dungeontrain:dungeon_train_y80"),
                ServerLevelTypePolicy.override("dungeontrain:dungeon_train_y80", WOVER));
        assertEquals(Optional.of("dungeontrain:dungeon_train_flat"),
                ServerLevelTypePolicy.override("DungeonTrain:Dungeon_Train_Flat", WOVER));
    }

    @Test
    @DisplayName("an unconfigured server gets the Dungeon Train preset")
    void unconfiguredDefaultsToDt() {
        for (String requested : new String[] {null, "", "  ", "minecraft:normal", "normal", "default", "wover:normal"}) {
            assertEquals(Optional.of(ServerLevelTypePolicy.DEFAULT_PRESET),
                    ServerLevelTypePolicy.override(requested, WOVER), "requested=" + requested);
        }
    }

    @Test
    @DisplayName("other explicit presets are left to the mods")
    void otherPresetsUntouched() {
        assertEquals(Optional.empty(), ServerLevelTypePolicy.override("minecraft:large_biomes", WOVER));
        assertEquals(Optional.empty(), ServerLevelTypePolicy.override("wover:betterx", WOVER));
        assertEquals(Optional.empty(), ServerLevelTypePolicy.override("minecraft:flat", "minecraft:flat"));
    }

    @Test
    @DisplayName("no rewrite when the requested preset already applies")
    void alreadyApplied() {
        assertEquals(Optional.empty(),
                ServerLevelTypePolicy.override("dungeontrain:dungeon_train_y80", "dungeontrain:dungeon_train_y80"));
        assertEquals(Optional.empty(),
                ServerLevelTypePolicy.override("minecraft:normal", ServerLevelTypePolicy.DEFAULT_PRESET));
    }
}
