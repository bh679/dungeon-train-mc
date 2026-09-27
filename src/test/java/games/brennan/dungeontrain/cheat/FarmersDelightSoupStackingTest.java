package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FarmersDelightSoupStacking}: the one-time switch-off must keep every other Farmers' Delight
 * setting, must happen only once, and a menu switch-on is the only thing that asks first.
 */
class FarmersDelightSoupStackingTest {

    private static final String FD_TOML = """
        [overrides]
        \t#If enabled, soups and stews from Minecraft will grant Nourishment when eaten.
        \tenableVanillaSoupExtraEffects = true
        \tenableRabbitStewBuff = true

        \t[overrides.stack_size]
        \t\t#If enabled, any BowlFoodItem in the following list will become stackable to 16.
        \t\tenableStackableSoupItems = true
        \t\tsoupItemList = ["minecraft:mushroom_stew", "minecraft:beetroot_soup", "minecraft:rabbit_stew"]
        """;

    @Test
    @DisplayName("First boot switches soups off and keeps every other setting")
    void firstBootSwitchesOffAndKeepsTheRest(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(FarmersDelightSoupStacking.FILE);
        Files.writeString(file, FD_TOML);

        assertTrue(FarmersDelightSoupStacking.applyDefaultOnce(dir));

        assertEquals(Optional.of(false), FarmersDelightSoupStacking.readFlag(file));
        String after = Files.readString(file);
        assertTrue(after.contains("enableRabbitStewBuff = true"), after);
        assertTrue(after.contains("minecraft:rabbit_stew"), after);
    }

    @Test
    @DisplayName("Only once: a later switch-on is the player's choice and is left alone")
    void onlyOnce(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(FarmersDelightSoupStacking.FILE);
        Files.writeString(file, FD_TOML);
        FarmersDelightSoupStacking.applyDefaultOnce(dir);

        FarmersDelightSoupStacking.writeFlag(file, true);

        assertFalse(FarmersDelightSoupStacking.applyDefaultOnce(dir));
        assertEquals(Optional.of(true), FarmersDelightSoupStacking.readFlag(file));
    }

    @Test
    @DisplayName("No Farmers' Delight config yet ⇒ one is created holding just the switch, off")
    void createsMissingFile(@TempDir Path dir) throws IOException {
        assertTrue(FarmersDelightSoupStacking.applyDefaultOnce(dir));
        assertEquals(Optional.of(false),
            FarmersDelightSoupStacking.readFlag(dir.resolve(FarmersDelightSoupStacking.FILE)));
    }

    @Test
    @DisplayName("readFlag: absent file or non-boolean value reads as unknown")
    void readFlagFailsOpen(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(FarmersDelightSoupStacking.FILE);
        assertEquals(Optional.empty(), FarmersDelightSoupStacking.readFlag(file));
        Files.writeString(file, "[overrides.stack_size]\nenableStackableSoupItems = \"yes\"\n");
        assertEquals(Optional.empty(), FarmersDelightSoupStacking.readFlag(file));
    }

    @Test
    @DisplayName("Asks only for an unconfirmed off→on switch made from a menu")
    void shouldAsk() {
        assertTrue(FarmersDelightSoupStacking.shouldAsk(false, true, false, true), "menu switch-on");
        assertFalse(FarmersDelightSoupStacking.shouldAsk(false, true, false, false), "file edit: straight to Free Play");
        assertFalse(FarmersDelightSoupStacking.shouldAsk(false, true, true, true), "already confirmed");
        assertFalse(FarmersDelightSoupStacking.shouldAsk(true, true, false, true), "was already on");
        assertFalse(FarmersDelightSoupStacking.shouldAsk(false, false, false, true), "still off (our own revert)");
        assertFalse(FarmersDelightSoupStacking.shouldAsk(true, false, false, true), "switched off");
    }
}
