package games.brennan.dungeontrain.player;

import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the {@link PlayerRunState#travelledCarriageIndex} field that
 * landed in v0.255.0 — both the in-memory mutator/reset wiring and the
 * codec persistence path.
 *
 * <p>The two load-bearing guarantees these tests pin:
 * <ul>
 *   <li>{@link PlayerRunState#resetCarts()} (called from
 *       {@link PlayerRunState#resetAll()} on {@code PlayerRespawnEvent})
 *       clears {@code travelledCarriageIndex}. If a future edit adds a
 *       new counter and forgets to clear it here, mob difficulty after
 *       respawn would silently inherit pre-death progress.</li>
 *   <li>{@link PlayerRunState#CODEC} declares
 *       {@code optionalFieldOf("travelledCarriageIndex", 0)} so v0.254-era
 *       save files (which never wrote the key) decode cleanly with a
 *       default of 0. If a future refactor drops the {@code optional} part,
 *       all existing players' saves would fail to load.</li>
 * </ul></p>
 */
final class PlayerRunStateTest {

    @Test
    @DisplayName("Default state: travelledCarriageIndex is 0")
    void defaultTravelledIsZero() {
        PlayerRunState state = new PlayerRunState();
        assertEquals(0, state.travelledCarriageIndex());
    }

    @Test
    @DisplayName("advanceTravelled: positive deltas accumulate (5 + 3 = 8)")
    void advanceTravelledPositiveDelta() {
        PlayerRunState state = new PlayerRunState();
        state.advanceTravelled(5);
        state.advanceTravelled(3);
        assertEquals(8, state.travelledCarriageIndex());
    }

    @Test
    @DisplayName("advanceTravelled: negative delta subtracts (10 + (-3) = 7) — sign is preserved")
    void advanceTravelledNegativeDelta() {
        PlayerRunState state = new PlayerRunState();
        state.advanceTravelled(10);
        state.advanceTravelled(-3);
        assertEquals(7, state.travelledCarriageIndex());
    }

    @Test
    @DisplayName("advanceTravelled: zero delta is a no-op (early-return guard)")
    void advanceTravelledZeroDeltaNoOp() {
        PlayerRunState state = new PlayerRunState();
        state.advanceTravelled(5);
        state.advanceTravelled(0);
        assertEquals(5, state.travelledCarriageIndex());
    }

    @Test
    @DisplayName("resetCarts clears travelledCarriageIndex AND both cart counters")
    void resetCartsClearsTravelled() {
        PlayerRunState state = new PlayerRunState();
        state.recordCartMovement(5);
        state.recordCartMovement(-3);
        state.advanceTravelled(42);
        // Sanity: we actually put non-zero values in before resetting.
        assertEquals(8, state.cartsSinceDeath());
        assertEquals(3, state.cartsBackwardSinceDeath());
        assertEquals(42, state.travelledCarriageIndex());

        state.resetCarts();

        assertEquals(0, state.cartsSinceDeath());
        assertEquals(0, state.cartsBackwardSinceDeath());
        assertEquals(0, state.travelledCarriageIndex());
    }

    @Test
    @DisplayName("resetAll clears chests AND every counter (including travelledCarriageIndex)")
    void resetAllClearsTravelledAndChests() {
        PlayerRunState state = new PlayerRunState();
        state.addChestPos(new BlockPos(1, 64, 2));
        state.addChestPos(new BlockPos(3, 64, 4));
        state.recordCartMovement(7);
        state.recordCartMovement(-2);
        state.advanceTravelled(42);
        // Sanity:
        assertEquals(2, state.chestStreak());
        assertEquals(9, state.cartsSinceDeath());
        assertEquals(2, state.cartsBackwardSinceDeath());
        assertEquals(42, state.travelledCarriageIndex());

        state.resetAll();

        assertEquals(0, state.chestStreak());
        assertTrue(state.uniqueChests().isEmpty());
        assertEquals(0, state.cartsSinceDeath());
        assertEquals(0, state.cartsBackwardSinceDeath());
        assertEquals(0, state.travelledCarriageIndex());
    }

    @Test
    @DisplayName("addTrainTimeTicks accumulates; resetDeathStats clears it (single-life semantics)")
    void trainTimeTicksAccumulateAndReset() {
        PlayerRunState state = new PlayerRunState();
        assertEquals(0L, state.trainTimeTicks());
        state.addTrainTimeTicks(10L);
        state.addTrainTimeTicks(10L);
        assertEquals(20L, state.trainTimeTicks());
        // Negative / zero deltas are ignored (mirrors addRunTicks / addDistance guards).
        state.addTrainTimeTicks(0L);
        state.addTrainTimeTicks(-5L);
        assertEquals(20L, state.trainTimeTicks());
        // Death resets single-life time, just like distanceBlocks / runTicks.
        state.resetDeathStats();
        assertEquals(0L, state.trainTimeTicks());
    }

    @Test
    @DisplayName("Codec round-trip via NbtOps preserves travelledCarriageIndex and every other field")
    void codecRoundTripPreservesTravelled() {
        List<BlockPos> chests = List.of(
            new BlockPos(10, 64, 20),
            new BlockPos(-5, 70, 15)
        );
        // Constructor signature: chests, cartsSinceDeath, cartsBackwardSinceDeath,
        // travelledCarriageIndex, mobKills, distanceBlocks, runTicks,
        // containersOpened, booksReadCount, weaponKills, playerKills,
        // damageDealt, damageTaken, encounteredMobs, befriendedMobs, trainTimeTicks.
        List<UUID> encountered = List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002")
        );
        List<UUID> befriended = List.of(
            UUID.fromString("00000000-0000-0000-0000-00000000000a")
        );
        PlayerRunState original = new PlayerRunState(chests, 11, 4, 17, 0, 0.0, 0L, 0, 0, Map.of(),
            3, 12.5, 7.0, encountered, befriended, 99000L);

        DataResult<Tag> encoded = PlayerRunState.CODEC.encodeStart(NbtOps.INSTANCE, original);
        Tag tag = encoded.result().orElseThrow(
            () -> new AssertionError("encode failed: " + encoded.error().map(DataResult.Error::message).orElse("?"))
        );
        assertNotNull(tag);

        DataResult<PlayerRunState> parsed = PlayerRunState.CODEC.parse(NbtOps.INSTANCE, tag);
        PlayerRunState reloaded = parsed.result().orElseThrow(
            () -> new AssertionError("parse failed: " + parsed.error().map(DataResult.Error::message).orElse("?"))
        );

        assertEquals(11, reloaded.cartsSinceDeath());
        assertEquals(4, reloaded.cartsBackwardSinceDeath());
        assertEquals(17, reloaded.travelledCarriageIndex());
        assertEquals(2, reloaded.chestStreak());
        assertTrue(reloaded.uniqueChests().contains(new BlockPos(10, 64, 20)));
        assertTrue(reloaded.uniqueChests().contains(new BlockPos(-5, 70, 15)));
        assertEquals(3, reloaded.playerKills());
        assertEquals(12.5, reloaded.damageDealt());
        assertEquals(7.0, reloaded.damageTaken());
        assertEquals(2, reloaded.encounteredCount());
        assertEquals(1, reloaded.befriendedCount());
        assertEquals(99000L, reloaded.trainTimeTicks());
    }

    @Test
    @DisplayName("Codec back-compat: legacy v0.254 tag (no travelledCarriageIndex key) decodes with default 0")
    void codecBackwardCompatMissingTravelledDefaultsToZero() {
        // Handcraft a CompoundTag matching the v0.254 wire format: the three
        // pre-existing fields are present, travelledCarriageIndex is absent.
        // This is the exact shape an existing player save will have before
        // they first respawn under the new code.
        List<BlockPos> chests = List.of(new BlockPos(100, 65, 200));
        DataResult<Tag> chestsTag = BlockPos.CODEC.listOf().encodeStart(NbtOps.INSTANCE, chests);
        Tag encodedChests = chestsTag.result().orElseThrow(
            () -> new AssertionError("chest list encode failed: " + chestsTag.error().map(DataResult.Error::message).orElse("?"))
        );

        CompoundTag legacy = new CompoundTag();
        legacy.put("uniqueChests", encodedChests);
        legacy.putInt("cartsSinceDeath", 12);
        legacy.putInt("cartsBackwardSinceDeath", 3);
        // Deliberately no "travelledCarriageIndex" key — that's the migration we're testing.

        DataResult<PlayerRunState> parsed = PlayerRunState.CODEC.parse(NbtOps.INSTANCE, legacy);
        PlayerRunState reloaded = parsed.result().orElseThrow(
            () -> new AssertionError("legacy parse failed: " + parsed.error().map(DataResult.Error::message).orElse("?"))
        );

        assertEquals(0, reloaded.travelledCarriageIndex(),
            "missing travelledCarriageIndex key must default to 0 — guards the optionalFieldOf migration");
        assertEquals(12, reloaded.cartsSinceDeath(),
            "old fields must still round-trip alongside the missing-default behaviour");
        assertEquals(3, reloaded.cartsBackwardSinceDeath());
        assertEquals(1, reloaded.chestStreak());
        assertTrue(reloaded.uniqueChests().contains(new BlockPos(100, 65, 200)));
    }
}
