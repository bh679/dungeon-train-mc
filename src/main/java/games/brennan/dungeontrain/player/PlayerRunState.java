package games.brennan.dungeontrain.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-player "current run" state, attached to {@link net.minecraft.world.entity.player.Player}
 * via NeoForge AttachmentType. Holds streak-style counters that exist for
 * the duration of a single life and reset on respawn:
 *
 * <ul>
 *   <li>{@link #uniqueChests} — positions of distinct chest blocks the
 *       player has opened in the current streak. Drives the
 *       "open 100 chests without opening the same one twice" advancement.</li>
 *   <li>{@link #cartsSinceDeath} — total <em>absolute</em> carriages the
 *       player has traversed since their last death (forward + backward
 *       both count). Drives the carts-in-run tiers.</li>
 *   <li>{@link #cartsBackwardSinceDeath} — subset of {@link #cartsSinceDeath}
 *       accumulated from backward (negative-delta) leader movement. The
 *       forward subtotal is derived as {@code cartsSinceDeath - cartsBackwardSinceDeath}.
 *       Drives the "The Long Way Back" achievement.</li>
 *   <li>{@link #travelledCarriageIndex} — signed net carriages traversed by
 *       the player as leader this life (forward positive, backward negative).
 *       Drives mob difficulty: the spawn-time tier is computed from
 *       {@code max(this counter)} across all online players, so a single death
 *       resets the player's own contribution to 0 and the next mob spawn falls
 *       back to whatever any other player still has.</li>
 * </ul>
 *
 * <p>Persisted via {@link #CODEC} so the streak survives logout / world
 * reload. Manually cleared by {@code AchievementEvents} on
 * {@link net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent}
 * — NeoForge does NOT auto-copy player attachments across the respawn
 * clone, so "do nothing" already gives us the reset; we explicitly null
 * out anyway to be defensive.</p>
 */
public final class PlayerRunState {

    public static final Codec<PlayerRunState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        BlockPos.CODEC.listOf().optionalFieldOf("uniqueChests", List.of()).forGetter(PlayerRunState::uniqueChestsList),
        Codec.INT.optionalFieldOf("cartsSinceDeath", 0).forGetter(PlayerRunState::cartsSinceDeath),
        Codec.INT.optionalFieldOf("cartsBackwardSinceDeath", 0).forGetter(PlayerRunState::cartsBackwardSinceDeath),
        Codec.INT.optionalFieldOf("travelledCarriageIndex", 0).forGetter(PlayerRunState::travelledCarriageIndex)
    ).apply(instance, PlayerRunState::new));

    private final Set<BlockPos> uniqueChests;
    private int cartsSinceDeath;
    private int cartsBackwardSinceDeath;
    private int travelledCarriageIndex;

    public PlayerRunState() {
        this.uniqueChests = new HashSet<>();
        this.cartsSinceDeath = 0;
        this.cartsBackwardSinceDeath = 0;
        this.travelledCarriageIndex = 0;
    }

    public PlayerRunState(List<BlockPos> uniqueChests, int cartsSinceDeath, int cartsBackwardSinceDeath, int travelledCarriageIndex) {
        this.uniqueChests = new HashSet<>(uniqueChests);
        this.cartsSinceDeath = cartsSinceDeath;
        this.cartsBackwardSinceDeath = cartsBackwardSinceDeath;
        this.travelledCarriageIndex = travelledCarriageIndex;
    }

    public Set<BlockPos> uniqueChests() {
        return uniqueChests;
    }

    /** Codec-friendly view (List, not Set). */
    public List<BlockPos> uniqueChestsList() {
        return new ArrayList<>(uniqueChests);
    }

    /** Total absolute carriages traversed since last death (forward + backward). */
    public int cartsSinceDeath() {
        return cartsSinceDeath;
    }

    /** Subtotal of {@link #cartsSinceDeath} accumulated from backward movement. */
    public int cartsBackwardSinceDeath() {
        return cartsBackwardSinceDeath;
    }

    /** Derived forward subtotal: {@code cartsSinceDeath - cartsBackwardSinceDeath}. */
    public int cartsForwardSinceDeath() {
        return cartsSinceDeath - cartsBackwardSinceDeath;
    }

    /**
     * Attempt to add {@code pos} to the unique-chests set.
     *
     * @return {@code true} if the position was new (streak grew),
     *         {@code false} if it was already present (streak resets — caller
     *         is responsible for clearing and re-adding).
     */
    public boolean addChestPos(BlockPos pos) {
        return uniqueChests.add(pos.immutable());
    }

    /** Drop every chest position. */
    public void clearChests() {
        uniqueChests.clear();
    }

    /** Number of distinct chests in the current streak. */
    public int chestStreak() {
        return uniqueChests.size();
    }

    /**
     * Record a leader-anchor carriage delta. Both forward (positive) and
     * backward (negative) movement add {@code |signedDelta|} to the
     * absolute {@link #cartsSinceDeath} counter; backward movement also
     * advances {@link #cartsBackwardSinceDeath}.
     *
     * @return the new absolute {@link #cartsSinceDeath} after the update.
     */
    public int recordCartMovement(int signedDelta) {
        if (signedDelta == 0) return cartsSinceDeath;
        int abs = Math.abs(signedDelta);
        cartsSinceDeath += abs;
        if (signedDelta < 0) cartsBackwardSinceDeath += abs;
        return cartsSinceDeath;
    }

    /** Signed net carriages traversed by this player as leader since their last death. */
    public int travelledCarriageIndex() {
        return travelledCarriageIndex;
    }

    /**
     * Add a signed leader-anchor delta to {@link #travelledCarriageIndex}.
     * Mirrors {@link #recordCartMovement} but preserves sign — mob difficulty
     * uses {@code abs(travelledCarriageIndex) / carriagesPerTier} to compute
     * tier, so backward movement reduces tier (matches the prior behavior of
     * the now-deprecated global counter).
     */
    public void advanceTravelled(int signedDelta) {
        if (signedDelta == 0) return;
        travelledCarriageIndex += signedDelta;
    }

    /** Reset cart counters and travelled-carriage-index (called on respawn). */
    public void resetCarts() {
        cartsSinceDeath = 0;
        cartsBackwardSinceDeath = 0;
        travelledCarriageIndex = 0;
    }

    /** Reset everything (called on respawn). */
    public void resetAll() {
        clearChests();
        resetCarts();
    }
}
