package games.brennan.dungeontrain.compat;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one piece of the Effortless Building gate that is pure logic: deciding whether a modifier
 * update switches a modifier <b>on</b> (Free Play prompt) or merely off / unchanged (free).
 *
 * <p>The JSON shapes below are the ones Effortless Building 4.2's {@code ModifierSerializer} emits
 * (keys {@code type}, {@code enabled}, {@code mirrorX}, {@code radial_mirror}, …). The rest of the
 * gate — the mixin injections and the confirmation round-trip — is verified in-game.</p>
 */
class EffortlessBuildingGateTest {

    private static boolean enabled(String json) {
        return EffortlessBuildingGate.anyEnabled(JsonParser.parseString(json));
    }

    @Test
    @DisplayName("A modifier switched on prompts")
    void enabledModifierPrompts() {
        assertTrue(enabled("""
            [{"type":"mirror","enabled":true,"mirrorX":true,"originX":0.0}]"""));
    }

    @Test
    @DisplayName("Turning every modifier off is free — disabling a mirror must not trip Free Play")
    void allDisabledIsFree() {
        assertFalse(enabled("""
            [{"type":"mirror","enabled":false,"mirrorX":true},
             {"type":"array","enabled":false,"count":3}]"""));
    }

    @Test
    @DisplayName("One enabled modifier among disabled ones still prompts")
    void mixedPrompts() {
        assertTrue(enabled("""
            [{"type":"mirror","enabled":false},
             {"type":"array","enabled":true,"count":3}]"""));
    }

    @Test
    @DisplayName("Nested structures are walked (radial mirror slices)")
    void nestedEnabledPrompts() {
        assertTrue(enabled("""
            {"modifiers":{"radial_mirror":{"enabled":true,"slices":4}}}"""));
    }

    @Test
    @DisplayName("An empty or modifier-less payload is free")
    void emptyIsFree() {
        assertFalse(enabled("[]"));
        assertFalse(enabled("{}"));
        assertFalse(enabled("null"));
    }

    @Test
    @DisplayName("A non-boolean 'enabled' is ignored rather than treated as on")
    void nonBooleanEnabledIsFree() {
        assertFalse(enabled("""
            [{"type":"mirror","enabled":"yes"}]"""));
        assertFalse(enabled("""
            [{"type":"mirror","enabled":1}]"""));
    }

    @Test
    @DisplayName("Unreadable packets fail open — a missing json() accessor never prompts")
    void unreadablePacketFailsOpen() {
        // A future Effortless Building build could rename the record component; gating on a
        // guess would falsely taint honest runs, so enablesModifier must return false.
        assertFalse(EffortlessBuildingGate.enablesModifier(new Object()));
        assertFalse(EffortlessBuildingGate.enablesModifier(new Object() {
            public String json() { return "not json at all {{{"; }
        }));
    }

    @Test
    @DisplayName("A blank json payload is free")
    void blankJsonIsFree() {
        assertFalse(EffortlessBuildingGate.enablesModifier(new Object() {
            public String json() { return "  "; }
        }));
    }
}
