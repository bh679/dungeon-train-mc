package games.brennan.dungeontrain.client.vivecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the gate that decides whether DT applies its own VR melee fix — no loader, no Vivecraft,
 * no headset needed.
 *
 * <p>What is being protected is a live regression window: DT removed its copy of the fix in #1261,
 * so for a VR player who has not installed the Vivecraft Sable Compat addon, this gate is the only
 * thing between them and melee that hits nothing on the train.</p>
 */
class VivecraftFixGateTest {

    @Test
    void appliesWhenVivecraftIsPresentAndTheAddonIsNot() {
        // The regression window this fallback exists for: updated DT, hasn't installed the addon.
        assertTrue(VivecraftFixGate.shouldApply(true, false));
    }

    @Test
    void standsDownWhenTheAddonOwnsTheFix() {
        // The addon fixes melee AND teleport; DT must not also own the same call.
        assertFalse(VivecraftFixGate.shouldApply(true, true));
    }

    @Test
    void skippedEntirelyWithoutVivecraft() {
        // The overwhelmingly common case: no Vivecraft, so the mixin's target class isn't present.
        assertFalse(VivecraftFixGate.shouldApply(false, false));
    }

    @Test
    void addonWithoutVivecraftIsStillSkipped() {
        // A nonsensical install (the addon hard-requires Vivecraft), but the gate must never apply a
        // mixin whose target class cannot exist.
        assertFalse(VivecraftFixGate.shouldApply(false, true));
    }

    /**
     * The addon is identified by {@code modId}, not by its Modrinth/CurseForge slug. The slug is
     * hyphenated ({@code vivecraft-sable-compat}); the modId is not. Using the slug would fail open —
     * the addon would never be detected and DT would keep applying its copy alongside it — which is
     * silent, and exactly the drift a bare string constant invites.
     */
    @Test
    void addonIsIdentifiedByModIdNotSlug() {
        assertEquals("vivecraft_sable_compat", VivecraftFixGate.COMPAT_MODID);
        assertEquals("vivecraft", VivecraftFixGate.VIVECRAFT_MODID);
    }
}
