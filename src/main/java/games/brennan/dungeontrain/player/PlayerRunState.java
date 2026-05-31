package games.brennan.dungeontrain.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-player "current run" state, attached to {@link net.minecraft.world.entity.player.Player}
 * via NeoForge AttachmentType. Holds streak-style counters that exist for
 * the duration of a single life and reset on respawn.
 *
 * <p>Counters split into two groups:</p>
 *
 * <p><b>Achievement / difficulty-driving:</b></p>
 * <ul>
 *   <li>{@link #uniqueChests} — distinct chest/barrel block positions
 *       opened in the current streak; drives the
 *       "open 100 chests without opening the same one twice" advancement.</li>
 *   <li>{@link #cartsSinceDeath} — total <em>absolute</em> carriages traversed
 *       since last death (forward + backward both count); drives the
 *       carts-in-run tiers.</li>
 *   <li>{@link #cartsBackwardSinceDeath} — backward-only subtotal of
 *       {@link #cartsSinceDeath}; drives "The Long Way Back".</li>
 *   <li>{@link #travelledCarriageIndex} — signed net carriages traversed by
 *       the player as leader this life (forward positive, backward negative).
 *       Drives mob difficulty: the spawn-time tier is computed from
 *       {@code max(this counter)} across all online players, so a single death
 *       resets the player's own contribution to 0 and the next mob spawn falls
 *       back to whatever any other player still has.</li>
 * </ul>
 *
 * <p><b>Death-screen stats (this run only, reset on respawn):</b></p>
 * <ul>
 *   <li>{@link #mobKills} — entities killed by this player.</li>
 *   <li>{@link #distanceBlocks} — accumulated world-space movement while
 *       boarded; sums train-carried + on-train walking distance.</li>
 *   <li>{@link #runTicks} — server ticks elapsed since the run began.</li>
 *   <li>{@link #containersOpened} — flat counter of chest/barrel opens +
 *       decorated-pot breaks. (Pre-debounce — counts distinct interactions,
 *       not unique positions like {@link #uniqueChests}.)</li>
 *   <li>{@link #booksReadCount} — written-book right-click count.</li>
 *   <li>{@link #weaponKills} — per-item kill tally used to resolve the
 *       most-used weapon at death.</li>
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
        Codec.INT.optionalFieldOf("travelledCarriageIndex", 0).forGetter(PlayerRunState::travelledCarriageIndex),
        Codec.INT.optionalFieldOf("mobKills", 0).forGetter(PlayerRunState::mobKills),
        Codec.DOUBLE.optionalFieldOf("distanceBlocks", 0.0).forGetter(PlayerRunState::distanceBlocks),
        Codec.LONG.optionalFieldOf("runTicks", 0L).forGetter(PlayerRunState::runTicks),
        Codec.INT.optionalFieldOf("containersOpened", 0).forGetter(PlayerRunState::containersOpened),
        Codec.INT.optionalFieldOf("booksReadCount", 0).forGetter(PlayerRunState::booksReadCount),
        Codec.unboundedMap(Codec.STRING, Codec.INT)
            .optionalFieldOf("weaponKills", Map.of()).forGetter(PlayerRunState::weaponKillsSnapshot)
    ).apply(instance, PlayerRunState::new));

    private final Set<BlockPos> uniqueChests;
    private int cartsSinceDeath;
    private int cartsBackwardSinceDeath;
    private int travelledCarriageIndex;
    private int mobKills;
    private double distanceBlocks;
    private long runTicks;
    private int containersOpened;
    private int booksReadCount;
    /** Item-id (e.g. {@code minecraft:iron_sword}) → number of mob kills credited to that weapon this run. */
    private final Map<String, Integer> weaponKills;

    public PlayerRunState() {
        this.uniqueChests = new HashSet<>();
        this.cartsSinceDeath = 0;
        this.cartsBackwardSinceDeath = 0;
        this.travelledCarriageIndex = 0;
        this.mobKills = 0;
        this.distanceBlocks = 0.0;
        this.runTicks = 0L;
        this.containersOpened = 0;
        this.booksReadCount = 0;
        this.weaponKills = new HashMap<>();
    }

    public PlayerRunState(List<BlockPos> uniqueChests,
                          int cartsSinceDeath,
                          int cartsBackwardSinceDeath,
                          int travelledCarriageIndex,
                          int mobKills,
                          double distanceBlocks,
                          long runTicks,
                          int containersOpened,
                          int booksReadCount,
                          Map<String, Integer> weaponKills) {
        this.uniqueChests = new HashSet<>(uniqueChests);
        this.cartsSinceDeath = cartsSinceDeath;
        this.cartsBackwardSinceDeath = cartsBackwardSinceDeath;
        this.travelledCarriageIndex = travelledCarriageIndex;
        this.mobKills = mobKills;
        this.distanceBlocks = distanceBlocks;
        this.runTicks = runTicks;
        this.containersOpened = containersOpened;
        this.booksReadCount = booksReadCount;
        this.weaponKills = new HashMap<>(weaponKills);
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

    public int mobKills() {
        return mobKills;
    }

    public double distanceBlocks() {
        return distanceBlocks;
    }

    public long runTicks() {
        return runTicks;
    }

    public int containersOpened() {
        return containersOpened;
    }

    public int booksReadCount() {
        return booksReadCount;
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

    public int incrementMobKills() {
        return ++mobKills;
    }

    /**
     * Snapshot view of weapon-kill counts for codec serialization. Returns a
     * fresh copy; callers must not mutate.
     */
    public Map<String, Integer> weaponKillsSnapshot() {
        return new HashMap<>(weaponKills);
    }

    /**
     * Record one mob-kill credit against {@code weapon}. Empty stacks are
     * tracked as {@code minecraft:air} (bare-hand kills) so they can be
     * distinguished from "no kills yet" in the death-screen item slot.
     */
    public void recordWeaponKill(ItemStack weapon) {
        Item item = weapon.isEmpty() ? net.minecraft.world.item.Items.AIR : weapon.getItem();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        weaponKills.merge(id.toString(), 1, Integer::sum);
    }

    /**
     * Resolve the most-used weapon for this run as a fresh ItemStack(count=1),
     * or {@code ItemStack.EMPTY} if no kills have been recorded. Ties broken
     * by insertion order (HashMap iteration is undefined, accepted — the
     * tied weapons would all be reasonable answers).
     */
    public ItemStack mostUsedWeapon() {
        String bestId = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : weaponKills.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestId = e.getKey();
            }
        }
        if (bestId == null) return ItemStack.EMPTY;
        ResourceLocation rl = ResourceLocation.tryParse(bestId);
        if (rl == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == net.minecraft.world.item.Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item);
    }

    /** Add {@code blocks} to the per-run distance counter. Negatives are ignored. */
    public double addDistance(double blocks) {
        if (blocks <= 0.0 || !Double.isFinite(blocks)) return distanceBlocks;
        distanceBlocks += blocks;
        return distanceBlocks;
    }

    /** Add {@code ticks} to the per-run time counter. Negatives are ignored. */
    public long addRunTicks(long ticks) {
        if (ticks <= 0L) return runTicks;
        runTicks += ticks;
        return runTicks;
    }

    public int incrementContainersOpened() {
        return ++containersOpened;
    }

    public int incrementBooksRead() {
        return ++booksReadCount;
    }

    /** Reset cart counters and travelled-carriage-index (called on respawn). */
    public void resetCarts() {
        cartsSinceDeath = 0;
        cartsBackwardSinceDeath = 0;
        travelledCarriageIndex = 0;
    }

    /** Reset the death-screen stat counters. */
    public void resetDeathStats() {
        mobKills = 0;
        distanceBlocks = 0.0;
        runTicks = 0L;
        containersOpened = 0;
        booksReadCount = 0;
        weaponKills.clear();
    }

    /** Reset everything (called on respawn). */
    public void resetAll() {
        clearChests();
        resetCarts();
        resetDeathStats();
    }
}
