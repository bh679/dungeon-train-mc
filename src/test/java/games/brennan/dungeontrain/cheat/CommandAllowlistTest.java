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
        // Retuning how often portals arrive re-shapes the run, so it has to route through the Free
        // Play prompt like any other. The world-level taint that PortalCommand adds on top only
        // fires once the command actually runs, which this gate is what allows.
        assertTrue(CommandAllowlist.taints("dungeontrain portal carriage 5"));
        assertTrue(CommandAllowlist.taints("dungeontrain portal carriage creative 5"));
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
    @DisplayName("Debug DT commands are allowed")
    void debugAllowed() {
        assertFalse(CommandAllowlist.taints("dungeontrain debug scan"));
        assertFalse(CommandAllowlist.taints("dungeontrain debug wireframes all on"));
        assertFalse(CommandAllowlist.taints("dt debug reroll foo"));
    }

    @Test
    @DisplayName("Editor/dev DT commands taint (editor, save, reset, package, editor export/import)")
    void editorDevSubsTaint() {
        assertTrue(CommandAllowlist.taints("dungeontrain editor"));
        assertTrue(CommandAllowlist.taints("dungeontrain editor enter cargo"));
        assertTrue(CommandAllowlist.taints("dungeontrain save default"));
        assertTrue(CommandAllowlist.taints("dungeontrain reset"));
        assertTrue(CommandAllowlist.taints("dungeontrain package list"));
        assertTrue(CommandAllowlist.taints("dungeontrain editor export"));
        assertTrue(CommandAllowlist.taints("dungeontrain editor import"));
        assertTrue(CommandAllowlist.taints("dt save all"));          // alias
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
    @DisplayName("/fixaisconfig (AIS-data Free Play fix action) is allowed")
    void fixAisConfigAllowed() {
        assertFalse(CommandAllowlist.taints("fixaisconfig"));
        assertFalse(CommandAllowlist.taints("/fixaisconfig"));
    }

    @Test
    @DisplayName("/fixconfig (config-reset Free Play fix action) is allowed")
    void fixConfigAllowed() {
        // Putting the config back the way it shipped must never taint the run it repairs.
        assertFalse(CommandAllowlist.taints("fixconfig"));
        assertFalse(CommandAllowlist.taints("/fixconfig"));
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
    @DisplayName("WorldEdit's // commands taint — the modpack's map editor needs no special case")
    void worldEditCommandsTaint() {
        // WorldEdit registers into the vanilla dispatcher (CommandWrapper -> Commands.literal),
        // so CommandEvent fires and the deny-by-default allowlist covers it with no WorldEdit
        // -specific code. These pin that down: an allowlist edit must not silently un-gate it.
        assertTrue(CommandAllowlist.taints("//set stone"));
        assertTrue(CommandAllowlist.taints("//replace dirt stone"));
        assertTrue(CommandAllowlist.taints("//brush sphere stone 5"));
        assertTrue(CommandAllowlist.taints("//paste"));
        assertTrue(CommandAllowlist.taints("//undo"));
        // Selection / info commands taint too — the prompt lands the moment WorldEdit is touched.
        assertTrue(CommandAllowlist.taints("//wand"));
        assertTrue(CommandAllowlist.taints("//pos1"));
        assertTrue(CommandAllowlist.taints("//size"));
        // …as do the non-slash-prefixed roots.
        assertTrue(CommandAllowlist.taints("worldedit reload"));
        assertTrue(CommandAllowlist.taints("we reload"));
    }

    @Test
    @DisplayName("/customcontent never taints — it is the way OUT of Free Play")
    void customContentNeverTaints() {
        // The Free Play notice links straight to this command. Tainting a player for clicking the
        // "turn my custom content off" line would be exactly backwards — same reasoning that keeps
        // /fixaisconfig allowlisted. It is a ROOT command, not a /dt subcommand, because /dt is
        // gated at permission 2 and the player it exists for is an ordinary survival player.
        assertFalse(CommandAllowlist.taints("/customcontent off"));
        assertFalse(CommandAllowlist.taints("/customcontent on"));
        assertFalse(CommandAllowlist.taints("/customcontent status"));
        // …while the DT authoring commands still do.
        assertTrue(CommandAllowlist.taints("/dt package disable my-pack"));
    }

    @Test
    @DisplayName("Only /advancement revoke @s everything is clean — every other form taints")
    void onlySelfRevokeEverythingIsClean() {
        // Wiping your OWN slate destroys progress and can never create it, so it is the opposite
        // of cheating — and it is how "It's Not That Simple" is earned, which cannot bank on a
        // tainted run. Namespace tolerated, since /minecraft:advancement is the same command.
        assertFalse(CommandAllowlist.taints("/advancement revoke @s everything"));
        assertFalse(CommandAllowlist.taints("advancement revoke @s everything"));
        assertFalse(CommandAllowlist.taints("/minecraft:advancement revoke @s everything"));
        assertFalse(CommandAllowlist.taints("  /advancement   revoke   @s   everything  "));

        // Any other target is reaching into someone else's profile — still cheating.
        assertTrue(CommandAllowlist.taints("/advancement revoke Brennan everything"));
        assertTrue(CommandAllowlist.taints("/advancement revoke @a everything"));
        assertTrue(CommandAllowlist.taints("/advancement revoke @p everything"));
        // A partial revoke isn't the wipe the advancement is about.
        assertTrue(CommandAllowlist.taints("/advancement revoke @s only minecraft:story/root"));
        assertTrue(CommandAllowlist.taints("/advancement revoke @s from minecraft:story/root"));
        // Handing progress out is straightforwardly cheating.
        assertTrue(CommandAllowlist.taints("/advancement grant @s everything"));
        assertTrue(CommandAllowlist.taints("advancement set @s everything"));
        assertTrue(CommandAllowlist.taints("/advancement"));
        assertTrue(CommandAllowlist.taints("/advancement revoke"));
    }

    @Test
    @DisplayName("The self-revoke rule is shared with the advancement that rewards it")
    void selfRevokeClassifierIsTheSameRule() {
        // StartAgainAdvancement arms off this exact predicate, so the command that is forgiven
        // and the command that is rewarded can never drift apart.
        assertTrue(CommandAllowlist.isSelfRevokeEverything("/advancement revoke @s everything"));
        assertFalse(CommandAllowlist.isSelfRevokeEverything("/advancement revoke @a everything"));
        assertFalse(CommandAllowlist.isSelfRevokeEverything("/advancement grant @s everything"));
        assertFalse(CommandAllowlist.isSelfRevokeEverything("/give @s everything"));
        assertFalse(CommandAllowlist.isSelfRevokeEverything(""));
        assertFalse(CommandAllowlist.isSelfRevokeEverything(null));
    }

    @Test
    @DisplayName("Empty / blank input never taints")
    void emptyNeverTaints() {
        assertFalse(CommandAllowlist.taints(""));
        assertFalse(CommandAllowlist.taints("   "));
        assertFalse(CommandAllowlist.taints("/"));
    }
}
