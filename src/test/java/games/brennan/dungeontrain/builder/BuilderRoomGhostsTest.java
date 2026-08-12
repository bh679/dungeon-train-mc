package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.portal.PortalRoomTiling;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the builder draws around an open portal room.
 *
 * <p>The ghosts are a claim about the room: "this one repeats", "this one is sealed", "this one has
 * no walls". Each mode's claim has to match what the world would actually build, because a builder
 * who trusts the wrong one authors a room for a space that doesn't exist.</p>
 */
final class BuilderRoomGhostsTest {

    /** The shipped default room. */
    private static final Vec3i DEFAULT_ROOM = new Vec3i(11, 7, 13);

    @Test
    @DisplayName("Bedrockless draws nothing — empty space is the whole mode")
    void bedrocklessDrawsNothing() {
        BuilderRoomGhosts.Ghosts ghosts =
                BuilderRoomGhosts.of(PortalRoomMode.BEDROCKLESS, DEFAULT_ROOM);
        assertTrue(ghosts.isEmpty(),
                "drawing a boundary for the mode that has none would contradict it");
    }

    @Test
    @DisplayName("Bedrock Lock is a skin, not a grid")
    void bedrockLockIsAShell() {
        BuilderRoomGhosts.Ghosts ghosts =
                BuilderRoomGhosts.of(PortalRoomMode.BEDROCK_LOCK, DEFAULT_ROOM);
        assertTrue(ghosts.shell());
        assertTrue(ghosts.tiles().isEmpty(), "a sealed room repeats nothing");
        assertFalse(ghosts.isEmpty());
    }

    @Test
    @DisplayName("Both endless modes tile, and neither includes the room itself")
    void endlessModesTileAroundTheRoom() {
        for (PortalRoomMode mode : new PortalRoomMode[]{
                PortalRoomMode.ENDLESS_REPETITION, PortalRoomMode.ENDLESS_OPEN}) {
            BuilderRoomGhosts.Ghosts ghosts = BuilderRoomGhosts.of(mode, DEFAULT_ROOM);
            assertFalse(ghosts.tiles().isEmpty(), mode + " should tile");
            assertFalse(ghosts.shell(), mode + " has no skin to draw");
            // The room is real blocks; a ghost on top of it would double every face.
            assertTrue(ghosts.tiles().stream().noneMatch(t -> t.tileX() == 0 && t.tileZ() == 0),
                    mode + " drew a ghost over the room itself");
        }
    }

    @Test
    @DisplayName("Only Endless Open drops the walls")
    void onlyEndlessOpenIsPlanesOnly() {
        assertTrue(BuilderRoomGhosts.of(PortalRoomMode.ENDLESS_OPEN, DEFAULT_ROOM).planesOnly());
        assertFalse(BuilderRoomGhosts.of(PortalRoomMode.ENDLESS_REPETITION, DEFAULT_ROOM).planesOnly());
    }

    @Test
    @DisplayName("Detail falls off after the first ring")
    void onlyTheTouchingRingIsDrawnInFull() {
        // The cost rule: a full window is 120 tiles and a room has ~1000 exposed faces, so copying
        // faces everywhere is far past the renderer's cap.
        BuilderRoomGhosts.Ghosts ghosts =
                BuilderRoomGhosts.of(PortalRoomMode.ENDLESS_REPETITION, DEFAULT_ROOM);
        for (BuilderRoomGhosts.Tile tile : ghosts.tiles()) {
            BuilderRoomGhosts.Detail expected = tile.ring() <= 1
                    ? BuilderRoomGhosts.Detail.FULL
                    : BuilderRoomGhosts.Detail.SHELL;
            assertEquals(expected, tile.detail(), "wrong detail at ring " + tile.ring());
        }
        assertEquals(8, ghosts.tiles().stream()
                        .filter(t -> t.detail() == BuilderRoomGhosts.Detail.FULL).count(),
                "the touching ring is the eight neighbours");
    }

    @Test
    @DisplayName("The window never claims to go further than the world would build")
    void radiusNeverExceedsTheWorldBudget() {
        // The whole point of deriving it: a ghost that tiled past the real window would be a
        // confident lie about how far the space goes.
        assertTrue(BuilderRoomGhosts.radiusFor(DEFAULT_ROOM) <= PortalRoomTiling.MAX_RADIUS);

        Vec3i biggest = new Vec3i(48, 11, 48);
        int radius = BuilderRoomGhosts.radiusFor(biggest);
        int side = 2 * radius + 1;
        assertTrue(side * side <= PortalRoomTiling.budgetTiles(48 * 11 * 48),
                "the largest room's window must fit the block budget");
        assertTrue(radius < BuilderRoomGhosts.radiusFor(DEFAULT_ROOM),
                "a bigger room must tile fewer times, not the same number");
    }

    @Test
    @DisplayName("The fade reaches nothing at the outer ring")
    void fadeRunsOutAtTheEdge() {
        // A window that stopped at full strength would announce a hard edge the real space, which
        // ends in fog, does not have.
        assertEquals(1.0f, BuilderRoomGhosts.fadeFor(1, 4), 0.0001f);
        assertTrue(BuilderRoomGhosts.fadeFor(4, 4) < BuilderRoomGhosts.fadeFor(2, 4));
        assertTrue(BuilderRoomGhosts.fadeFor(9, 4) >= 0.0f, "never negative, however far out");
    }

    @Test
    @DisplayName("A missing mode or a degenerate size draws nothing rather than throwing")
    void badInputsAreTolerated() {
        assertTrue(BuilderRoomGhosts.of(null, DEFAULT_ROOM).isEmpty());
        assertTrue(BuilderRoomGhosts.of(PortalRoomMode.ENDLESS_REPETITION, null).isEmpty());
        assertTrue(BuilderRoomGhosts.of(PortalRoomMode.ENDLESS_REPETITION, new Vec3i(0, 7, 13))
                .isEmpty());
    }
}
