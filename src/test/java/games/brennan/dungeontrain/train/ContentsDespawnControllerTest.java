package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static games.brennan.dungeontrain.train.ContentsDespawnController.AWAY_GRACE_TICKS;
import static games.brennan.dungeontrain.train.ContentsDespawnController.Action;
import static games.brennan.dungeontrain.train.ContentsDespawnController.DESPAWN_RADIUS_BLOCKS;
import static games.brennan.dungeontrain.train.ContentsDespawnController.DESPAWN_RADIUS_SQ;
import static games.brennan.dungeontrain.train.ContentsDespawnController.MAX_RESTORES_PER_TICK;
import static games.brennan.dungeontrain.train.ContentsDespawnController.MAX_SWEEPS_PER_TICK;
import static games.brennan.dungeontrain.train.ContentsDespawnController.RESTORE_RADIUS_BLOCKS;
import static games.brennan.dungeontrain.train.ContentsDespawnController.RESTORE_RADIUS_SQ;
import static games.brennan.dungeontrain.train.ContentsDespawnController.decide;
import static games.brennan.dungeontrain.train.ContentsDespawnController.isSweepable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the contents-despawn distance gate. No Minecraft bootstrap — the two cores
 * under test ({@code decide} and {@code isSweepable}) take primitives and string collections
 * precisely so they can be exercised here, the same split
 * {@code PhysicsFreezeControllerTest} relies on.
 *
 * <p>Every expectation is derived from the package-private constants rather than hard-coded, so
 * retuning a radius or a grace period cannot leave a stale green test behind.</p>
 */
class ContentsDespawnControllerTest {

    private static final String CONTENTS_TAG = CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX + "7";
    private static final double FAR = DESPAWN_RADIUS_SQ * 4.0;

    // ---------------------------------------------------------------- Table A: decide()

    @Test
    void despawnsWhenFarAndSettled() {
        assertEquals(Action.DESPAWN, decide(true, FAR, true, false, AWAY_GRACE_TICKS));
    }

    @Test
    void despawnIsLazy_oneTickShortOfGraceDoesNothing() {
        assertEquals(Action.NONE, decide(true, FAR, true, false, AWAY_GRACE_TICKS - 1));
        assertEquals(Action.NONE, decide(true, FAR, true, false, 0));
    }

    @Test
    void despawnBoundaryIsExclusive() {
        // Exactly at the radius is NOT beyond it — the player is still in the "leave it alone" zone.
        assertEquals(Action.NONE, decide(true, DESPAWN_RADIUS_SQ, true, false, Integer.MAX_VALUE));
    }

    @Test
    void neverDespawnsWithAPlayerOnTop() {
        assertEquals(Action.NONE, decide(true, 0.0, true, false, Integer.MAX_VALUE));
    }

    @Test
    void doesNothingWhenContentsWereNeverSpawned() {
        // The spawn gate still owns this group, or it is an empty carriage.
        assertEquals(Action.NONE, decide(true, FAR, false, false, Integer.MAX_VALUE));
    }

    @Test
    void restoreIsEagerAndBoundaryIsInclusive() {
        assertEquals(Action.RESTORE, decide(true, RESTORE_RADIUS_SQ, false, true, 0));
        assertEquals(Action.RESTORE, decide(true, RESTORE_RADIUS_SQ - 1, false, true, Integer.MAX_VALUE));
    }

    @Test
    void deadBandHoldsInBothDirections() {
        // Between the two radii: snapshotted stays snapshotted...
        double band = (RESTORE_RADIUS_SQ + DESPAWN_RADIUS_SQ) / 2.0;
        assertEquals(Action.NONE, decide(true, band, false, true, Integer.MAX_VALUE));
        // ...and live stays live. This is what stops a player pacing the boundary from thrashing.
        assertEquals(Action.NONE, decide(true, band, true, false, Integer.MAX_VALUE));
    }

    @Test
    void staysDespawnedWhileFarAway() {
        assertEquals(Action.NONE, decide(true, FAR, false, true, Integer.MAX_VALUE));
    }

    @Test
    void disabledRestoresEverythingAndDespawnsNothing() {
        assertEquals(Action.RESTORE, decide(false, FAR, false, true, Integer.MAX_VALUE));
        assertEquals(Action.NONE, decide(false, FAR, true, false, Integer.MAX_VALUE));
    }

    @Test
    void liveAndSnapshottedSimultaneouslyIsInert() {
        // Not a reachable state; assert it cannot double-act if a future edit makes it reachable.
        assertEquals(Action.NONE, decide(true, FAR, true, true, Integer.MAX_VALUE));
    }

    // ------------------------------------------------------- Table B: constant invariants

    @Test
    void despawnRadiusIsOutsideRestoreRadius() {
        assertTrue(DESPAWN_RADIUS_BLOCKS > RESTORE_RADIUS_BLOCKS,
            "despawn radius must sit outside the restore radius or the gates invert");
    }

    @Test
    void hysteresisBandClearsAGroupFootprint() {
        // A carriage group's footprint is ~37 blocks. The band has to exceed it, otherwise the
        // group's far end can be inside one radius while its anchor is outside the other.
        assertTrue(DESPAWN_RADIUS_BLOCKS - RESTORE_RADIUS_BLOCKS >= 64.0,
            "hysteresis band must comfortably exceed a group footprint");
    }

    @Test
    void restoreRadiusIsTheSpawnRadius() {
        // Aliased, not copied — a player walking up must see the same distance behaviour whether the
        // contents are spawning for the first time or being restored.
        assertEquals(TrainCarriageAppender.SPAWN_RADIUS_BLOCKS, RESTORE_RADIUS_BLOCKS);
    }

    @Test
    void despawnRadiusExceedsWidestClientTrackingRange() {
        // 128 blocks = 8 chunks, vanilla's widest mob tracking range, so no client can be tracking a
        // mob at the moment it is swept.
        assertTrue(DESPAWN_RADIUS_BLOCKS >= 128.0);
    }

    @Test
    void bandSurvivesWorstCaseClosingSpeed() {
        // A player cannot cross the band and re-enter the restore radius within the grace window.
        double bandBlocks = DESPAWN_RADIUS_BLOCKS - RESTORE_RADIUS_BLOCKS;
        double reachableBlocks = 16.0 * (AWAY_GRACE_TICKS / 20.0);
        assertTrue(bandBlocks > reachableBlocks,
            "grace window must be shorter than the time to cross the hysteresis band");
    }

    @Test
    void budgetsAreUsable() {
        assertTrue(AWAY_GRACE_TICKS >= 1);
        assertTrue(MAX_SWEEPS_PER_TICK >= 1);
        assertTrue(MAX_RESTORES_PER_TICK >= 1);
    }

    // ------------------------------------------------------------ Table C: isSweepable()

    @Test
    void sweepsOrdinaryTaggedContentsMobs() {
        assertTrue(isSweepable("minecraft:zombie", List.of(CONTENTS_TAG), Set.of()));
    }

    @Test
    void sweepsVillagersIncludingRerolledOnes() {
        assertTrue(isSweepable("minecraft:villager",
            List.of(CONTENTS_TAG, "dungeontrain_trades_rerolled"), Set.of()));
    }

    @Test
    void ignoresEntitiesThatAreNotCarriageContents() {
        assertFalse(isSweepable("minecraft:zombie", List.of(), Set.of()));
        assertFalse(isSweepable("minecraft:zombie", List.of("some_other_tag"), Set.of()));
    }

    @Test
    void exemptsPlayerMobs() {
        assertFalse(isSweepable(ContentsDespawnController.PLAYER_MOB_ID, List.of(CONTENTS_TAG), Set.of()));
    }

    @Test
    void exemptsDeathNoteEchoesRegardlessOfType() {
        assertFalse(isSweepable(ContentsDespawnController.PLAYER_MOB_ID, List.of(CONTENTS_TAG),
            Set.of(DeathNoteEchoSpawner.KEY_TARGET, DeathNoteEchoSpawner.KEY_NOTE_ID)));
        // An echo's identity is its persistent data, not its entity type.
        assertFalse(isSweepable("minecraft:zombie", List.of(CONTENTS_TAG),
            Set.of(DeathNoteEchoSpawner.KEY_AUTHOR)));
    }

    @Test
    void isNullSafe() {
        assertFalse(isSweepable("minecraft:zombie", null, null));
        assertTrue(isSweepable("minecraft:zombie", List.of(CONTENTS_TAG), null));
    }

    @Test
    void matchesOnTagPrefixOnly() {
        // Same semantics as TrainMembership.hasContentsTag — the suffix is not parsed here.
        assertTrue(isSweepable("minecraft:zombie",
            List.of(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX), Set.of()));
    }
}
