package games.brennan.dungeontrain.block.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The engine screen's "From <name>'s world" name arrives in a carriage blob another world wrote, so only a real
 * Minecraft player name is ever shown; anything else reads as an unknown placer.
 */
class EngineMenuPlacerNameTest {

    @Test
    void keepsPlayerNames() {
        assertEquals("Steve", EngineMenu.clampName("Steve"));
        assertEquals("brennan_h", EngineMenu.clampName("brennan_h"));
        assertEquals("A234567890123456", EngineMenu.clampName("A234567890123456"));
    }

    @Test
    void dropsAnythingElse() {
        assertEquals("", EngineMenu.clampName(null));
        assertEquals("", EngineMenu.clampName(""));
        assertEquals("", EngineMenu.clampName("A2345678901234567"));
        assertEquals("", EngineMenu.clampName("two words"));
        assertEquals("", EngineMenu.clampName("§cRed"));
        assertEquals("", EngineMenu.clampName("Stéve"));
    }
}
