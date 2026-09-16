package games.brennan.dungeontrain.builder.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A blank owner on the wire means "whoever's row this is", and only a dev build can have linked a foreign one. */
final class BuilderRelayOwnersTest {

    @Test
    @DisplayName("a named owner is taken as given, whatever the record says")
    void namedOwnerWins() {
        assertEquals("them", BuilderRelayOwners.resolve("me", " them ", true, "other"));
        assertEquals("them", BuilderRelayOwners.resolve("me", "them", false, ""));
    }

    @Test
    @DisplayName("blank on a dev build resolves through the world's record of the row")
    void blankResolvesThroughRecordOnDevBuild() {
        assertEquals("owner", BuilderRelayOwners.resolve("me", "", true, "owner"));
        assertEquals("owner", BuilderRelayOwners.resolve("me", null, true, "owner"));
        assertEquals("me", BuilderRelayOwners.resolve("me", "", true, ""), "an own or unrecorded row is the player's");
    }

    @Test
    @DisplayName("a release build never asks for anybody else's row")
    void releaseBuildIsAlwaysThePlayer() {
        assertEquals("me", BuilderRelayOwners.resolve("me", "", false, "owner"));
        assertEquals("me", BuilderRelayOwners.resolve("me", null, false, null));
    }
}
