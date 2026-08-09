package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-math tests for {@link PortalFrames} — the carriage↔twin mapping that replaces the
 * free-standing portal's fixed {@code deltaY} when one copy is riding a moving train.
 *
 * <p>Test geometry: a default 9×7×7 carriage, its corridor origin at world {@code (100, 78, 0)},
 * and the static twin 96 blocks above at {@code (100, 174, 0)}. Midpoint is local X 4.5.</p>
 */
final class PortalFramesTest {

    private static final PortalCarriageLayout LAYOUT = new PortalCarriageLayout(9, 7, 7);

    private static final double CAR_X = 100, CAR_Y = 78, CAR_Z = 0;
    private static final double TWIN_X = 100, TWIN_Y = 174, TWIN_Z = 0;

    /** Local Y/Z of a player standing on the walkway, used for every position in these tests. */
    private static final double FEET_Y = 1;
    private static final double WALK_Z = 3;

    private static PortalFrames frames(double carriageX) {
        return new PortalFrames(LAYOUT,
            new PortalFrames.Origin(carriageX, CAR_Y, CAR_Z),
            new PortalFrames.Origin(TWIN_X, TWIN_Y, TWIN_Z),
            PortalCarriageRole.ENTRY);
    }

    /** The same pair with the mirrored rule: the train is the far half rather than the near one. */
    private static PortalFrames exitFrames() {
        return new PortalFrames(LAYOUT,
            new PortalFrames.Origin(CAR_X, CAR_Y, CAR_Z),
            new PortalFrames.Origin(TWIN_X, TWIN_Y, TWIN_Z),
            PortalCarriageRole.EXIT);
    }

    private static PortalFrames frames() {
        return frames(CAR_X);
    }

    // ---- frame identification -------------------------------------------------

    @Test
    @DisplayName("frameAt tells the two corridors apart and rejects positions in neither")
    void frameAt() {
        PortalFrames f = frames();
        assertEquals(PortalFrames.FRAME_CARRIAGE, f.frameAt(CAR_X + 3, CAR_Y + FEET_Y, CAR_Z + WALK_Z));
        assertEquals(PortalFrames.FRAME_TWIN, f.frameAt(TWIN_X + 6, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z));
        assertEquals(PortalFrames.FRAME_NONE, f.frameAt(CAR_X + 3, CAR_Y + 40, CAR_Z + WALK_Z));
        assertEquals(PortalFrames.FRAME_NONE, f.frameAt(CAR_X + 40, CAR_Y + FEET_Y, CAR_Z + WALK_Z));
    }

    // ---- the invariant --------------------------------------------------------

    @Test
    @DisplayName("a position already in the frame its side demands needs no move")
    void settledPositionsDoNotMove() {
        PortalFrames f = frames();
        assertNull(f.requiredMove(CAR_X + 2, CAR_Y + FEET_Y, CAR_Z + WALK_Z));    // before mid, on train
        assertNull(f.requiredMove(TWIN_X + 7, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z)); // past mid, in twin
    }

    @Test
    @DisplayName("crossing the midpoint on the train lands in the twin at the same corridor offset")
    void crossingIntoTwinPreservesLocalOffset() {
        PortalFrames f = frames();
        double localX = LAYOUT.midX() + PortalFrames.SWAP_HYSTERESIS + 0.5;   // clear of the band

        PortalFrames.Move move = f.requiredMove(CAR_X + localX, CAR_Y + FEET_Y, CAR_Z + WALK_Z);

        assertNotNull(move);
        assertEquals(PortalFrames.FRAME_TWIN, move.toFrame());
        assertEquals(TWIN_X + localX, move.x(), 1e-9);
        assertEquals(TWIN_Y + FEET_Y, move.y(), 1e-9);
        assertEquals(TWIN_Z + WALK_Z, move.z(), 1e-9);
    }

    /**
     * The live-return property, and the whole reason this is a frame mapping rather than an offset:
     * the train keeps moving while the player is away, so walking back must put them wherever the
     * carriage is <i>now</i>, not where it was when they left it.
     */
    @Test
    @DisplayName("walking back rejoins the train at its CURRENT position, not where it was")
    void returnFollowsTheMovingCarriage() {
        double localX = 2.5;                         // before the midpoint: belongs on the train
        double movedCarriageX = CAR_X + 640;         // the train has travelled while you were away

        PortalFrames.Move move = frames(movedCarriageX)
            .requiredMove(TWIN_X + localX, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z);

        assertNotNull(move);
        assertEquals(PortalFrames.FRAME_CARRIAGE, move.toFrame());
        assertEquals(movedCarriageX + localX, move.x(), 1e-9);
        assertEquals(CAR_Y + FEET_Y, move.y(), 1e-9);
    }

    @Test
    @DisplayName("applying a move twice is a no-op — the rule is idempotent, not an event")
    void idempotent() {
        PortalFrames f = frames();
        PortalFrames.Move move = f.requiredMove(CAR_X + LAYOUT.midX() + PortalFrames.SWAP_HYSTERESIS + 0.5, CAR_Y + FEET_Y, CAR_Z + WALK_Z);

        assertNotNull(move);
        assertNull(f.requiredMove(move.x(), move.y(), move.z()));
    }

    /**
     * The hysteresis band, and why it exists: preserving the local offset lands a player barely past
     * the line, and on a Sable carriage the client/server position disagreement is larger than that
     * margin — so a strict comparison oscillates. Observed live before this band: three swaps in
     * 0.13s at local X 4.64 → 4.47 → 4.64.
     */
    @Test
    @DisplayName("neither frame swaps while inside the hysteresis band around the midpoint")
    void hysteresisBandHoldsBothFrames() {
        PortalFrames f = frames();
        double justPast = LAYOUT.midX() + PortalFrames.SWAP_HYSTERESIS / 2;
        double justBefore = LAYOUT.midX() - PortalFrames.SWAP_HYSTERESIS / 2;

        // On the carriage, a hair past the line: stays put rather than swapping.
        assertNull(f.requiredMove(CAR_X + justPast, CAR_Y + FEET_Y, CAR_Z + WALK_Z));
        // In the twin, a hair before it: likewise. This is the pairing that used to ping-pong.
        assertNull(f.requiredMove(TWIN_X + justBefore, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z));
        // Exactly on the line, from either side: still nothing.
        assertNull(f.requiredMove(CAR_X + LAYOUT.midX(), CAR_Y + FEET_Y, CAR_Z + WALK_Z));
        assertNull(f.requiredMove(TWIN_X + LAYOUT.midX(), TWIN_Y + FEET_Y, TWIN_Z + WALK_Z));
    }

    @Test
    @DisplayName("clearing the band swaps, from either side")
    void clearingTheBandSwaps() {
        PortalFrames f = frames();
        double clearlyPast = LAYOUT.midX() + PortalFrames.SWAP_HYSTERESIS + 0.1;
        double clearlyBefore = LAYOUT.midX() - PortalFrames.SWAP_HYSTERESIS - 0.1;

        PortalFrames.Move out = f.requiredMove(CAR_X + clearlyPast, CAR_Y + FEET_Y, CAR_Z + WALK_Z);
        assertNotNull(out);
        assertEquals(PortalFrames.FRAME_TWIN, out.toFrame());

        PortalFrames.Move back = f.requiredMove(TWIN_X + clearlyBefore, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z);
        assertNotNull(back);
        assertEquals(PortalFrames.FRAME_CARRIAGE, back.toFrame());
    }

    /**
     * The landing height for a player who was standing. The two frames' block grids differ by the
     * ship's fractional pose, so carrying local Y across verbatim put a grounded player inside the
     * twin's floor — and because a twin hangs in open air, Minecraft resolved that by dropping them
     * through it. This is that bug, pinned.
     */
    @Test
    @DisplayName("floorSurfaceY lands a grounded player on the floor, not inside it")
    void floorSurfaceIgnoresFractionalPose() {
        // A carriage whose pose leaves its origin fractional, exactly as the live ship AABB reports.
        PortalFrames f = new PortalFrames(LAYOUT,
            new PortalFrames.Origin(CAR_X, 77.99, CAR_Z),
            new PortalFrames.Origin(TWIN_X, 173, TWIN_Z),
            PortalCarriageRole.ENTRY);

        // The twin's floor block sits at Y=173, so its walkable surface is 174 — not the 173.98 that
        // carrying a local Y of 0.98 across would have produced.
        assertEquals(174.0, f.floorSurfaceY(PortalFrames.FRAME_TWIN), 1e-9);
        assertEquals(78.99, f.floorSurfaceY(PortalFrames.FRAME_CARRIAGE), 1e-9);
    }

    // ---- the mirrored EXIT rule ------------------------------------------------

    /**
     * The EXIT half of a pair reverses which side is the train, and that reversal is the whole
     * reason a player can walk train → room → train without turning round. With the ENTRY rule on
     * both ends, stepping into the second corridor would teleport them straight back onto the train
     * before its midpoint, still walking forwards — two steps later they would re-cross it and be
     * back in the room. A revolving door.
     */
    @Test
    @DisplayName("EXIT mirrors the rule: the twin owns the near half, the carriage the far half")
    void exitRoleMirrorsTheRule() {
        PortalFrames f = exitFrames();
        double clearlyBefore = LAYOUT.midX() - PortalFrames.SWAP_HYSTERESIS - 0.1;
        double clearlyPast = LAYOUT.midX() + PortalFrames.SWAP_HYSTERESIS + 0.1;

        // Walking out of the room into the exit twin: before the line, so it stays put — under the
        // ENTRY rule this position would have been yanked onto the train immediately.
        assertNull(f.requiredMove(TWIN_X + clearlyBefore, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z));

        // Crossing its midpoint hands the player to the carriage, past ITS midpoint, so they carry
        // on forwards and leave through the carriage's far door onto the next carriage.
        PortalFrames.Move onward = f.requiredMove(TWIN_X + clearlyPast, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z);
        assertNotNull(onward);
        assertEquals(PortalFrames.FRAME_CARRIAGE, onward.toFrame());
        assertEquals(CAR_X + clearlyPast, onward.x(), 1e-9);

        // And the reverse: backing up past the carriage's midpoint returns them to the room.
        PortalFrames.Move back = f.requiredMove(CAR_X + clearlyBefore, CAR_Y + FEET_Y, CAR_Z + WALK_Z);
        assertNotNull(back);
        assertEquals(PortalFrames.FRAME_TWIN, back.toFrame());
    }

    @Test
    @DisplayName("ENTRY and EXIT disagree about the same position — they are true mirrors")
    void entryAndExitAreMirrors() {
        PortalFrames entry = frames();
        PortalFrames exit = exitFrames();
        double past = LAYOUT.midX() + PortalFrames.SWAP_HYSTERESIS + 0.1;

        // Same spot on the carriage, opposite verdicts: ENTRY sends it to the twin, EXIT keeps it.
        assertNotNull(entry.requiredMove(CAR_X + past, CAR_Y + FEET_Y, CAR_Z + WALK_Z));
        assertNull(exit.requiredMove(CAR_X + past, CAR_Y + FEET_Y, CAR_Z + WALK_Z));
    }

    // ---- simulated walk on a moving train -------------------------------------

    /**
     * Walk the corridor while the train advances underneath, applying the invariant each tick the
     * way {@code PortalCarriageEvents} does. The player's world X gains both their own pace and the
     * carriage's motion while they are aboard; once in the static twin, only their pace.
     */
    @Test
    @DisplayName("walking a moving carriage's corridor swaps exactly once")
    void walkOnMovingTrainSwapsOnce() {
        double carriageX = CAR_X;
        double trainPerTick = 0.1;      // 2 m/s at 20 TPS
        double walkPerTick = 0.22;

        double px = CAR_X + 1;          // just inside the entrance
        double py = CAR_Y + FEET_Y;
        double pz = CAR_Z + WALK_Z;
        int swaps = 0;

        for (int tick = 0; tick < 200; tick++) {
            PortalFrames f = frames(carriageX);
            boolean aboard = f.frameAt(px, py, pz) == PortalFrames.FRAME_CARRIAGE;

            // The carriage carries its riders along; in the static twin it does not.
            px += walkPerTick + (aboard ? trainPerTick : 0.0);
            carriageX += trainPerTick;

            PortalFrames after = frames(carriageX);
            PortalFrames.Move move = after.requiredMove(px, py, pz);
            if (move != null) {
                px = move.x();
                py = move.y();
                pz = move.z();
                swaps++;
                assertNull(after.requiredMove(px, py, pz), "not settled after one move at tick " + tick);
            }
        }

        assertEquals(1, swaps);
        // Ended up in the twin, which does not move with the train.
        assertEquals(TWIN_Y + FEET_Y, py, 1e-9);
    }

    // ---- puppet mirroring -----------------------------------------------------

    @Test
    @DisplayName("mirror maps a corridor position into the other copy, both directions")
    void mirrorBothDirections() {
        PortalFrames f = frames();

        PortalFrames.Move toTwin = f.mirror(CAR_X + 3, CAR_Y + FEET_Y, CAR_Z + WALK_Z);
        assertNotNull(toTwin);
        assertEquals(PortalFrames.FRAME_TWIN, toTwin.toFrame());
        assertEquals(TWIN_X + 3, toTwin.x(), 1e-9);
        assertEquals(TWIN_Y + FEET_Y, toTwin.y(), 1e-9);
        assertEquals(TWIN_Z + WALK_Z, toTwin.z(), 1e-9);

        PortalFrames.Move toCarriage = f.mirror(TWIN_X + 6, TWIN_Y + FEET_Y, TWIN_Z + WALK_Z);
        assertNotNull(toCarriage);
        assertEquals(PortalFrames.FRAME_CARRIAGE, toCarriage.toFrame());
        assertEquals(CAR_X + 6, toCarriage.x(), 1e-9);
        assertEquals(CAR_Y + FEET_Y, toCarriage.y(), 1e-9);
        assertEquals(CAR_Z + WALK_Z, toCarriage.z(), 1e-9);
    }

    @Test
    @DisplayName("mirroring twice returns the original position")
    void mirrorRoundTrips() {
        PortalFrames f = frames();
        PortalFrames.Move there = f.mirror(CAR_X + 2.75, CAR_Y + FEET_Y, CAR_Z + WALK_Z);
        assertNotNull(there);
        PortalFrames.Move back = f.mirror(there.x(), there.y(), there.z());
        assertNotNull(back);

        assertEquals(PortalFrames.FRAME_CARRIAGE, back.toFrame());
        assertEquals(CAR_X + 2.75, back.x(), 1e-9);
        assertEquals(CAR_Y + FEET_Y, back.y(), 1e-9);
        assertEquals(CAR_Z + WALK_Z, back.z(), 1e-9);
    }

    /**
     * The property that separates a puppet from a swap: {@link PortalFrames#requiredMove} goes quiet
     * inside the hysteresis band, but an entity standing there is still visible to someone in the
     * other copy and must still have a stand-in. A puppet that inherited the band would blink out
     * across 2.5 blocks of the crossing zone — the exact stretch where both players are most likely
     * to be looking at each other.
     */
    @Test
    @DisplayName("mirror still answers inside the hysteresis band, where requiredMove stays silent")
    void mirrorIgnoresTheHysteresisBand() {
        PortalFrames f = frames();
        double onTheLine = LAYOUT.midX();

        assertNull(f.requiredMove(CAR_X + onTheLine, CAR_Y + FEET_Y, CAR_Z + WALK_Z));

        PortalFrames.Move puppet = f.mirror(CAR_X + onTheLine, CAR_Y + FEET_Y, CAR_Z + WALK_Z);
        assertNotNull(puppet);
        assertEquals(TWIN_X + onTheLine, puppet.x(), 1e-9);
    }

    @Test
    @DisplayName("mirror ignores the role — a stand-in is not a swap decision")
    void mirrorIsRoleAgnostic() {
        double past = LAYOUT.midX() + SWAP_PAST;
        PortalFrames.Move entry = frames().mirror(CAR_X + past, CAR_Y + FEET_Y, CAR_Z + WALK_Z);
        PortalFrames.Move exit = exitFrames().mirror(CAR_X + past, CAR_Y + FEET_Y, CAR_Z + WALK_Z);

        assertNotNull(entry);
        assertNotNull(exit);
        assertEquals(entry.toFrame(), exit.toFrame());
        assertEquals(entry.x(), exit.x(), 1e-9);
    }

    @Test
    @DisplayName("mirror returns nothing for a position in neither corridor")
    void mirrorRejectsOutside() {
        PortalFrames f = frames();
        assertNull(f.mirror(CAR_X + 40, CAR_Y + FEET_Y, CAR_Z + WALK_Z));
        assertNull(f.mirror(CAR_X + 3, CAR_Y + 40, CAR_Z + WALK_Z));
    }

    /** Just past the band, so the position is unambiguously on one side of the line. */
    private static final double SWAP_PAST = PortalFrames.SWAP_HYSTERESIS + 0.1;
}
