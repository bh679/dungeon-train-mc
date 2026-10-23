package games.brennan.dungeontrain.cheat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Another player's Free Play is retold in the third person, naming who and, where known, what. */
class PlayerFreePlayIntegrityTest {

    private static TranslatableContents contents(Component c) {
        return (TranslatableContents) c.getContents();
    }

    @Test
    void commandCauseNamesThePlayerAndTheCommand() {
        Component own = Component.translatable("chat.dungeontrain.free_play.cause.command", "/give");
        TranslatableContents tc = contents(PlayerFreePlayIntegrity.thirdPerson("Alex", own));
        assertEquals("chat.dungeontrain.free_play.cause.player_used", tc.getKey());
        assertArrayEquals(new Object[] {"Alex", "/give"}, tc.getArgs());
    }

    @Test
    void creativeModCauseReadsAsUsed() {
        Component own = Component.translatable("chat.dungeontrain.free_play.cause.creative_mod", "Effortless Building");
        assertEquals("chat.dungeontrain.free_play.cause.player_used",
            contents(PlayerFreePlayIntegrity.thirdPerson("Alex", own)).getKey());
    }

    @Test
    void gameModeCauseReadsAsSwitched() {
        Component mode = Component.literal("Creative Mode");
        Component own = Component.translatable("chat.dungeontrain.free_play.cause.gamemode", mode);
        TranslatableContents tc = contents(PlayerFreePlayIntegrity.thirdPerson("Alex", own));
        assertEquals("chat.dungeontrain.free_play.cause.player_switched", tc.getKey());
        assertArrayEquals(new Object[] {"Alex", mode}, tc.getArgs());
    }

    @Test
    void otherOrMissingCausesNameOnlyWho() {
        Component operator = Component.translatable("chat.dungeontrain.free_play.cause.operator");
        for (Component own : new Component[] {operator, null, Component.literal("odd")}) {
            TranslatableContents tc = contents(PlayerFreePlayIntegrity.thirdPerson("Alex", own));
            assertEquals("chat.dungeontrain.free_play.cause.player", tc.getKey());
            assertArrayEquals(new Object[] {"Alex"}, tc.getArgs());
        }
    }
}
