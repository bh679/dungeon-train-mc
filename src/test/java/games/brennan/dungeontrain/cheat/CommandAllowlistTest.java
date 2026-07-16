package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allowlist classification for {@link CommandAllowlist#taints(String)}: anything
 * not explicitly allowed taints a run. Covers the DT exempt set, the vanilla
 * social/info exempt set, aliases, namespaced ids, and the
 * cinematic-vs-cinematographer split that the feature hinges on.
 */
class CommandAllowlistTest {

    // ---- Cheating commands taint ---------------------------------------

    @Test
    @DisplayName("Vanilla cheat commands taint (allowlist auto-covers them)")
    void vanillaCheatsTaint() {
        assertTrue(CommandAllowlist.taints("gamemode creative"));
        assertTrue(CommandAllowlist.taints("give @s minecraft:diamond 64"));
        assertTrue(CommandAllowlist.taints("tp @s 0 100 0"));
        assertTrue(CommandAllowlist.taints("effect give @s strength"));
        assertTrue(CommandAllowlist.taints("summon zombie"));
        assertTrue(CommandAllowlist.taints("time set day"));
        assertTrue(CommandAllowlist.taints("execute as @p run kill"));
    }

    @Test
    @DisplayName("/dtp (teleport-onto-train) taints, same as vanilla /tp")
    void dtpTaints() {
        assertTrue(CommandAllowlist.taints("dtp 3000"));
        assertTrue(CommandAllowlist.taints("/dtp 3000"));
        assertTrue(CommandAllowlist.taints("dtp -1500.5"));
    }

    @Test
    @DisplayName("Leading slash and explicit minecraft: namespace still taint")
    void slashAndNamespace() {
        assertTrue(CommandAllowlist.taints("/give @s diamond"));
        assertTrue(CommandAllowlist.taints("minecraft:give @s diamond"));
        assertTrue(CommandAllowlist.taints("/minecraft:gamemode creative"));
    }

    @Test
    @DisplayName("Cheaty DT subcommands taint")
    void dtCheatSubsTaint() {
        assertTrue(CommandAllowlist.taints("dungeontrain spawn 5"));
        assertTrue(CommandAllowlist.taints("dungeontrain speed 12"));
        assertTrue(CommandAllowlist.taints("dungeontrain carriages 8"));
        assertTrue(CommandAllowlist.taints("dungeontrain tracks off"));
        assertTrue(CommandAllowlist.taints("dt spawn"));               // alias
        assertTrue(CommandAllowlist.taints("dungeontrain:spawn"));     // namespaced root alias
    }

    @Test
    @DisplayName("Cinematographer (free-fly spectator camera) taints — unlike the cinematic intro")
    void cinematographerTaints() {
        assertTrue(CommandAllowlist.taints("dungeontrain cinematographer"));
        assertTrue(CommandAllowlist.taints("dungeontrain cinematographer 50"));
        assertTrue(CommandAllowlist.taints("dt cinematographer clearview on"));
    }

    @Test
    @DisplayName("Cheaty narrative subcommands taint (give / reset / lectern)")
    void narrativeCheatsTaint() {
        assertTrue(CommandAllowlist.taints("dungeontrain narrative book"));
        assertTrue(CommandAllowlist.taints("dungeontrain narrative reset"));
        assertTrue(CommandAllowlist.taints("dungeontrain narrative randombook give"));
        assertTrue(CommandAllowlist.taints("dungeontrain narrative startingbook fire welcome"));
    }

    // ---- Allowed commands stay clean -----------------------------------

    @Test
    @DisplayName("Cinematic intro replay is allowed")
    void cinematicAllowed() {
        assertFalse(CommandAllowlist.taints("dungeontrain cinematic"));
        assertFalse(CommandAllowlist.taints("dungeontrain cinematic spawn"));
        assertFalse(CommandAllowlist.taints("dt cinematic current"));
    }

    @Test
    @DisplayName("Debug + editor-authoring DT commands are allowed")
    void debugAndEditorAllowed() {
        assertFalse(CommandAllowlist.taints("dungeontrain debug scan"));
        assertFalse(CommandAllowlist.taints("dungeontrain debug wireframes all on"));
        assertFalse(CommandAllowlist.taints("dt debug reroll foo"));
        assertFalse(CommandAllowlist.taints("dungeontrain editor"));
        assertFalse(CommandAllowlist.taints("dungeontrain editor enter cargo"));
        assertFalse(CommandAllowlist.taints("dungeontrain save default"));
        assertFalse(CommandAllowlist.taints("dungeontrain reset"));
        assertFalse(CommandAllowlist.taints("dungeontrain package list"));
        assertFalse(CommandAllowlist.taints("dungeontrain export"));
        assertFalse(CommandAllowlist.taints("dungeontrain import"));
    }

    @Test
    @DisplayName("Read-only narrative subcommands are allowed; bare DT root is allowed")
    void narrativeReadonlyAndBareRoot() {
        assertFalse(CommandAllowlist.taints("dungeontrain narrative list"));
        assertFalse(CommandAllowlist.taints("dungeontrain narrative progress"));
        assertFalse(CommandAllowlist.taints("dungeontrain"));   // bare root just prints usage
        assertFalse(CommandAllowlist.taints("dt"));
    }

    @Test
    @DisplayName("Vanilla social/info commands are allowed")
    void vanillaSocialAllowed() {
        assertFalse(CommandAllowlist.taints("help"));
        assertFalse(CommandAllowlist.taints("help give"));
        assertFalse(CommandAllowlist.taints("me waves"));
        assertFalse(CommandAllowlist.taints("msg Steve hi"));
        assertFalse(CommandAllowlist.taints("tell Steve hi"));
        assertFalse(CommandAllowlist.taints("w Steve hi"));
        assertFalse(CommandAllowlist.taints("trigger objective"));
        assertFalse(CommandAllowlist.taints("list"));
        assertFalse(CommandAllowlist.taints("feedback"));
        assertFalse(CommandAllowlist.taints("feedback some bug report text"));
    }

    @Test
    @DisplayName("/bug (jump-to-bug-question submission) is allowed")
    void bugCommandAllowed() {
        assertFalse(CommandAllowlist.taints("bug"));
        assertFalse(CommandAllowlist.taints("/bug"));
    }

    @Test
    @DisplayName("/playanimation (cosmetic entity animation) is allowed")
    void playAnimationAllowed() {
        assertFalse(CommandAllowlist.taints("playanimation @s minecraft:humanoid.emote sneeze"));
        assertFalse(CommandAllowlist.taints("/playanimation @s minecraft:humanoid.emote sneeze"));
        assertFalse(CommandAllowlist.taints("minecraft:playanimation @s minecraft:humanoid.emote sneeze"));
    }

    @Test
    @DisplayName("/stopsound (cosmetic, silences a sound) is allowed")
    void stopSoundAllowed() {
        assertFalse(CommandAllowlist.taints("stopsound @s"));
        assertFalse(CommandAllowlist.taints("/stopsound @s master"));
        assertFalse(CommandAllowlist.taints("minecraft:stopsound @s"));
    }

    @Test
    @DisplayName("/weather still taints (has real gameplay effects, unlike playanimation/stopsound)")
    void weatherTaints() {
        assertTrue(CommandAllowlist.taints("weather clear"));
        assertTrue(CommandAllowlist.taints("/weather thunder 300"));
        assertTrue(CommandAllowlist.taints("minecraft:weather rain"));
    }

    @Test
    @DisplayName("/kill (bare, self-only) and /new-world (end / reset the run) are allowed")
    void runControlAllowed() {
        assertFalse(CommandAllowlist.taints("kill"));
        assertFalse(CommandAllowlist.taints("/kill"));
        assertFalse(CommandAllowlist.taints("new-world"));
        assertFalse(CommandAllowlist.taints("new-world fresh"));
    }

    @Test
    @DisplayName("/kill with a target selector or player name taints")
    void killWithTargetTaints() {
        assertTrue(CommandAllowlist.taints("kill @e"));
        assertTrue(CommandAllowlist.taints("kill SomePlayer"));
        assertTrue(CommandAllowlist.taints("/kill @e[type=zombie]"));
    }

    @Test
    @DisplayName("Empty / blank input never taints")
    void emptyNeverTaints() {
        assertFalse(CommandAllowlist.taints(""));
        assertFalse(CommandAllowlist.taints("   "));
        assertFalse(CommandAllowlist.taints("/"));
    }
}
