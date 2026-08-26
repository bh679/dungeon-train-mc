package games.brennan.dungeontrain.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 *   <li>{@link #echoesKilled}, {@link #maxCarriagesNoChest} — per-run feats fed to the public
 *       leaderboards. Deliberately NOT in {@link #CODEC}, matching {@code booksWrittenCount} and the
 *       other transient death-screen stats here: the codec is already at RecordCodecBuilder's
 *       16-field cap, and nesting an existing field to make room would silently zero an in-progress
 *       life's counters on upgrade. The cost is that a mid-life logout restarts these two, exactly as
 *       it already does for books written; the LIFETIME echo-kill tally lives in
 *       {@code GlobalPlayerStats} and is unaffected.</li>
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

    /**
     * How many tamed entity types the run remembers by name. The count itself is exact; this only
     * bounds the list the death report names, which no surface shows more than a handful of.
     */
    private static final int TAMED_NAMES_CAP = 16;

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
            .optionalFieldOf("weaponKills", Map.of()).forGetter(PlayerRunState::weaponKillsSnapshot),
        Codec.INT.optionalFieldOf("playerKills", 0).forGetter(PlayerRunState::playerKills),
        Codec.DOUBLE.optionalFieldOf("damageDealt", 0.0).forGetter(PlayerRunState::damageDealt),
        Codec.DOUBLE.optionalFieldOf("damageTaken", 0.0).forGetter(PlayerRunState::damageTaken),
        UUIDUtil.CODEC.listOf().optionalFieldOf("encounteredMobs", List.of()).forGetter(PlayerRunState::encounteredMobsList),
        UUIDUtil.CODEC.listOf().optionalFieldOf("befriendedMobs", List.of()).forGetter(PlayerRunState::befriendedMobsList),
        Codec.LONG.optionalFieldOf("trainTimeTicks", 0L).forGetter(PlayerRunState::trainTimeTicks)
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
    /** PlayerMob kills this run — subset of {@link #mobKills} (which counts all entities). */
    private int playerKills;
    /** Total damage this player dealt to other entities this run (health points). */
    private double damageDealt;
    /** Total damage this player took this run (health points). */
    private double damageTaken;
    /**
     * Echoes this player has KILLED this run. Distinct from the encounter tally — meeting an echo and
     * putting one down are different feats, and only the second one is this.
     */
    private int echoesKilled;
    /** Carriages passed since the last loot container was opened this run. Reset by {@link #openedLootContainer}. */
    private int carriagesSinceChest;
    /** High-water mark of {@link #carriagesSinceChest} this run — the longest stretch gone without looting. */
    private int maxCarriagesNoChest;
    /** Distinct PlayerMobs come near this run (drives the death-screen "encountered" stat). */
    private final Set<UUID> encounteredMobs;
    /** Distinct PlayerMobs that liked this player (feeling above the friend threshold) this run — the death-screen "friends" tally. */
    private final Set<UUID> befriendedMobs;
    /** Server ticks spent boarded this run (boarded-only; resets on death). Time twin of {@link #distanceBlocks}. */
    private long trainTimeTicks;
    /**
     * Distinct narrative letters read at a lectern this run, keyed
     * {@code storyBasename + "#" + letterIndex}. Dedupes page-turns / re-opens
     * so each lectern story counts {@link #booksReadCount} once. Reset on death
     * (per-run), so re-reading a story in a later life counts again.
     *
     * <p><b>In-memory only — deliberately NOT in {@link #CODEC}.</b> The codec's
     * {@code RecordCodecBuilder.group(...)} caps at 16 fields and the other
     * counters already fill it; persisting this would force an existing field
     * into a nested sub-record (resetting it for everyone mid-run on update).
     * {@code booksReadCount} itself IS persisted, so a normal logout keeps the
     * tally — only the dedup memory resets, at worst over-counting by one if a
     * player relogs mid-run and re-opens a lectern they already read.</p>
     */
    private final Set<String> narrativeLetters;
    /**
     * Books this player wrote & contributed to the community shared-book pool this run —
     * the death-screen "books written" tally (twin of {@link #booksReadCount}).
     * Incremented from the sign-book mixin each time a book is uploaded + burned.
     *
     * <p><b>In-memory only — deliberately NOT in {@link #CODEC}</b> (the 16-field cap, see
     * {@link #narrativeLetters}). Writing a book is a rare discrete action; the only cost of not
     * persisting is that writing one then relogging mid-run resets the tally to 0.</p>
     */
    private int booksWrittenCount;
    /**
     * Death Notes and Love Notes this player has SIGNED this run — the per-run twins of the
     * {@code deathnotes_written} / {@code lovenotes_written} leaderboards, and what a Faulthurst
     * stat book reports for them.
     *
     * <p>Only the WRITING half of each note subject has a per-run meaning at all: how many people
     * a note reached, and how the fight ended in their world, are answered by their save and not
     * this one.</p>
     *
     * <p><b>In-memory only — deliberately NOT in {@link #CODEC}</b> (the 16-field cap, see
     * {@link #narrativeLetters}), exactly as for {@link #booksWrittenCount} beside them: signing a
     * note is a rare discrete act, and the only cost is that signing one then relogging mid-run
     * resets the tally.</p>
     */
    private int deathNotesWritten;
    private int loveNotesWritten;
    /**
     * Visual identity of the PlayerMob that likes this player most this run — the
     * death-screen "friend" portrait — captured while it is loaded near the player
     * and only kept when its feeling clears the friend threshold (see
     * {@code RunStatsEvents}). {@code null} if no mob liked you enough. The
     * companion {@link #friendFeeling} keeps the warmest feeling so a stronger
     * friend supersedes a weaker one.
     *
     * <p><b>In-memory only — deliberately NOT in {@link #CODEC}</b> (the 16-field
     * cap, see {@link #narrativeLetters}). It only needs to live until the death
     * packet is built; the client rebuilds the portrait from the packet.</p>
     */
    private PlayerMobAppearance friendAppearance;
    /** Warmest feeling-toward-this-player (0–10) behind {@link #friendAppearance}; lower can be superseded. */
    private float friendFeeling = Float.NEGATIVE_INFINITY;
    /**
     * Visual identity of the MOST-RECENT PlayerMob killed this run, or
     * {@code null} if none (last-wins — overwritten each kill). Same transient,
     * non-codec rationale as {@link #friendAppearance}.
     */
    private PlayerMobAppearance killedAppearance;
    /**
     * Dungeon Train advancements (ids) earned this life, in earn order — the
     * death-screen "accolades" row on the cargo page. Recorded by
     * {@code AchievementEvents.onAdvancementEarn} (genuine earns only, never the
     * login replay), read into the death packet at death.
     *
     * <p><b>In-memory only — deliberately NOT in {@link #CODEC}</b> (the 16-field
     * cap, see {@link #narrativeLetters}). It only needs to live until the death
     * packet is built; the client resolves icons/titles from its own advancement
     * tree. A logout mid-run drops the list (same trade-off as
     * {@link #friendAppearance}).</p>
     */
    private final Set<ResourceLocation> earnedAdvancements;
    /**
     * Community shared-books already SERVED (resolved into the player's hand) this life, mapped to the
     * signed carriage index the player was at when served. Drives the shared-book loot selector's
     * no-duplicate-per-life rule and the "carriage scrolled far behind" re-serve escape (a served book
     * becomes eligible again only while unread AND its serve-carriage is {@code >= FAR_THRESHOLD} behind
     * the player's current {@link #travelledCarriageIndex}). See {@code SharedBookSelector}.
     *
     * <p><b>In-memory only — deliberately NOT in {@link #CODEC}</b> (the 16-field cap, see
     * {@link #narrativeLetters}). Per-life dedup is a soft nicety; a relog mid-run at worst lets one
     * already-served book be offered again, which is harmless.</p>
     */
    private final Map<Integer, Integer> servedBookToCarriage;
    /**
     * Animals tamed this run, in taming order — the death-screen and death-report "tamed" tally.
     * {@link #tamedCount} is the true total; this list keeps only the first
     * {@link #TAMED_NAMES_CAP} entity types, which is all the report ever names.
     *
     * <p><b>In-memory only — deliberately NOT in {@link #CODEC}</b> (the 16-field cap, see
     * {@link #narrativeLetters}). Taming is a discrete, memorable act; the cost of not persisting
     * is that taming then relogging mid-run resets the tally, exactly as for
     * {@link #booksWrittenCount}.</p>
     */
    private final List<ResourceLocation> tamedAnimals;
    /** Animals tamed this run — exact, even past {@link #TAMED_NAMES_CAP} named types. */
    private int tamedCount;

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
        this.playerKills = 0;
        this.damageDealt = 0.0;
        this.damageTaken = 0.0;
        this.encounteredMobs = new HashSet<>();
        this.befriendedMobs = new HashSet<>();
        this.trainTimeTicks = 0L;
        this.echoesKilled = 0;
        this.carriagesSinceChest = 0;
        this.maxCarriagesNoChest = 0;
        this.tamedAnimals = new ArrayList<>();
        this.tamedCount = 0;
        this.narrativeLetters = new HashSet<>();
        this.earnedAdvancements = new LinkedHashSet<>();
        this.servedBookToCarriage = new HashMap<>();
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
                          Map<String, Integer> weaponKills,
                          int playerKills,
                          double damageDealt,
                          double damageTaken,
                          List<UUID> encounteredMobs,
                          List<UUID> befriendedMobs,
                          long trainTimeTicks) {
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
        this.playerKills = playerKills;
        this.damageDealt = damageDealt;
        this.damageTaken = damageTaken;
        this.encounteredMobs = new HashSet<>(encounteredMobs);
        this.befriendedMobs = new HashSet<>(befriendedMobs);
        this.trainTimeTicks = trainTimeTicks;
        this.echoesKilled = 0;
        this.carriagesSinceChest = 0;
        this.maxCarriagesNoChest = 0;
        this.tamedAnimals = new ArrayList<>();
        this.tamedCount = 0;
        this.narrativeLetters = new HashSet<>();
        this.earnedAdvancements = new LinkedHashSet<>();
        this.servedBookToCarriage = new HashMap<>();
    }

    /**
     * Record one animal tamed this run. The count is always incremented; the type is remembered by
     * name only while the list is under {@link #TAMED_NAMES_CAP}.
     *
     * @return the new tamed total
     */
    public int recordTame(ResourceLocation entityType) {
        if (tamedAnimals.size() < TAMED_NAMES_CAP) {
            tamedAnimals.add(entityType);
        }
        return ++tamedCount;
    }

    /** Animals tamed this run. */
    public int tamedCount() {
        return tamedCount;
    }

    /** The tamed entity types, in taming order — at most {@link #TAMED_NAMES_CAP} of them. */
    public List<ResourceLocation> tamedAnimals() {
        return List.copyOf(tamedAnimals);
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

    public int booksWrittenCount() {
        return booksWrittenCount;
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
        // Same absolute measure feeds the chest-free streak: riding a carriage counts whichever way
        // you went, and the streak only ends when you actually open something.
        carriagesSinceChest += abs;
        if (carriagesSinceChest > maxCarriagesNoChest) maxCarriagesNoChest = carriagesSinceChest;
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

    /** Server ticks spent boarded this run (boarded-only). Drives the single-life time advancements. */
    public long trainTimeTicks() {
        return trainTimeTicks;
    }

    /** Add {@code ticks} to the per-run boarded-time counter. Negatives are ignored. Returns the new total. */
    public long addTrainTimeTicks(long ticks) {
        if (ticks <= 0L) return trainTimeTicks;
        trainTimeTicks += ticks;
        return trainTimeTicks;
    }

    /**
     * Count one container toward the death-screen tally. Does NOT touch the chest-free streak —
     * {@link #openedLootContainer()} is the one that ends it.
     */
    public int incrementContainersOpened() {
        return ++containersOpened;
    }

    /**
     * Count a chest or barrel: the tally, and the end of the chest-free streak.
     *
     * <p>Separate from {@link #incrementContainersOpened()} because a decorated pot is not loot in
     * the sense this streak means. The "Contained Loop" advancement has always let vases pass — only
     * the chest/barrel path stamps its marker — while the streak behind the leaderboard board reset
     * on anything that raised the counter, so smashing a vase quietly ended a run the advancement
     * considered still alive. Two streaks under one name, disagreeing. Now they agree, and the board
     * means what its heading says: chests and barrels.</p>
     */
    public int openedLootContainer() {
        carriagesSinceChest = 0; // the streak is over — the high-water mark keeps what it was worth
        return incrementContainersOpened();
    }

    /** Echoes killed this run. */
    public int echoesKilled() {
        return echoesKilled;
    }

    public int incrementEchoesKilled() {
        return ++echoesKilled;
    }

    /** The longest run of carriages passed without opening a container this run. */
    public int maxCarriagesNoChest() {
        return maxCarriagesNoChest;
    }

    /**
     * Carriages passed while dealing no damage at all — this run's pacifist distance, measured in
     * carriages. Derived rather than counted: the two inputs are already tracked, and dealing any
     * damage disqualifies the whole run, so there is no partial streak to keep.
     */
    public int pacifistCarriages() {
        return damageDealt > 0.0 ? 0 : Math.abs(travelledCarriageIndex);
    }

    public int incrementBooksRead() {
        return ++booksReadCount;
    }

    public int incrementBooksWritten() {
        return ++booksWrittenCount;
    }

    /** Death Notes signed this run. */
    public int deathNotesWritten() {
        return deathNotesWritten;
    }

    public int incrementDeathNotesWritten() {
        return ++deathNotesWritten;
    }

    /** Love Notes signed this run. */
    public int loveNotesWritten() {
        return loveNotesWritten;
    }

    public int incrementLoveNotesWritten() {
        return ++loveNotesWritten;
    }

    /**
     * Record a narrative letter (key {@code storyBasename + "#" + letterIndex})
     * read at a lectern this run.
     *
     * @return {@code true} if newly read this run — the caller increments the
     *         death-screen books-read tally; {@code false} if already counted
     *         (a page-turn or re-open of the same letter).
     */
    public boolean recordNarrativeRead(String key) {
        return narrativeLetters.add(key);
    }

    /**
     * Record that community shared-book {@code bookId} was served to this player this life, at signed
     * carriage index {@code carriageIndex}. Last-serve wins (a later re-serve after the far-behind escape
     * updates the recorded carriage). See {@code SharedBookSelector}.
     */
    public void markServed(int bookId, int carriageIndex) {
        servedBookToCarriage.put(bookId, carriageIndex);
    }

    /** Whether community shared-book {@code bookId} has been served to this player this life. */
    public boolean wasServed(int bookId) {
        return servedBookToCarriage.containsKey(bookId);
    }

    /**
     * The signed carriage index at which {@code bookId} was served this life, or {@code null} if it has
     * not been served. Used by the far-behind re-serve escape.
     */
    public Integer servedCarriage(int bookId) {
        return servedBookToCarriage.get(bookId);
    }

    /**
     * Record a Dungeon Train advancement (id) earned this life for the
     * death-screen accolades row. Earn order preserved; duplicates ignored.
     *
     * @return {@code true} if newly recorded this run.
     */
    public boolean recordEarnedAdvancement(ResourceLocation id) {
        return earnedAdvancements.add(id);
    }

    /** In-memory view of Dungeon Train advancements earned this life, in earn order. */
    public List<ResourceLocation> earnedAdvancements() {
        return new ArrayList<>(earnedAdvancements);
    }

    /** PlayerMob kills this run (subset of {@link #mobKills}). */
    public int playerKills() {
        return playerKills;
    }

    public int incrementPlayerKills() {
        return ++playerKills;
    }

    public double damageDealt() {
        return damageDealt;
    }

    public double damageTaken() {
        return damageTaken;
    }

    /** Add {@code amount} to the per-run damage-dealt counter. Non-finite / ≤0 ignored. */
    public double addDamageDealt(double amount) {
        if (amount <= 0.0 || !Double.isFinite(amount)) return damageDealt;
        damageDealt += amount;
        return damageDealt;
    }

    /** Add {@code amount} to the per-run damage-taken counter. Non-finite / ≤0 ignored. */
    public double addDamageTaken(double amount) {
        if (amount <= 0.0 || !Double.isFinite(amount)) return damageTaken;
        damageTaken += amount;
        return damageTaken;
    }

    /** Codec-friendly view (List, not Set). */
    public List<UUID> encounteredMobsList() {
        return new ArrayList<>(encounteredMobs);
    }

    /**
     * Record a PlayerMob this player has come near this run.
     *
     * @return {@code true} if newly encountered this run, {@code false} if
     *         already counted (caller uses this to drive the all-time
     *         encounter counter exactly once per distinct mob per run).
     */
    public boolean recordEncounter(UUID mobUuid) {
        return encounteredMobs.add(mobUuid);
    }

    /** Number of distinct PlayerMobs encountered this run. */
    public int encounteredCount() {
        return encounteredMobs.size();
    }

    /** Codec-friendly view (List, not Set). */
    public List<UUID> befriendedMobsList() {
        return new ArrayList<>(befriendedMobs);
    }

    /**
     * Record a PlayerMob that likes this player (above the friend threshold) this
     * run — the death-screen "friends" tally; fed by the proximity scan.
     *
     * @return {@code true} if newly recorded this run, {@code false} if already counted.
     */
    public boolean recordBefriended(UUID mobUuid) {
        return befriendedMobs.add(mobUuid);
    }

    /** Number of distinct PlayerMobs that liked this player (above the friend threshold) this run. */
    public int befriendedCount() {
        return befriendedMobs.size();
    }

    /** Visual identity of the warmest PlayerMob friend this run (likes you most, above threshold), or {@code null}. */
    public PlayerMobAppearance friendAppearance() {
        return friendAppearance;
    }

    /** The feeling (0–10) behind {@link #friendAppearance}, or {@link Float#NEGATIVE_INFINITY} if none captured. */
    public float friendFeeling() {
        return friendFeeling;
    }

    /** Keep the warmest friend's appearance this run — a higher feeling supersedes the current one. */
    public void captureFriendAppearance(PlayerMobAppearance appearance, float feeling) {
        if (appearance != null && feeling > friendFeeling) {
            friendAppearance = appearance;
            friendFeeling = feeling;
        }
    }

    /** Visual identity of the most-recent PlayerMob killed this run, or {@code null}. */
    public PlayerMobAppearance killedAppearance() {
        return killedAppearance;
    }

    /** Capture the most-recently killed mob's appearance (last-wins). */
    public void setKilledAppearance(PlayerMobAppearance appearance) {
        killedAppearance = appearance;
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
        trainTimeTicks = 0L;
        containersOpened = 0;
        booksReadCount = 0;
        booksWrittenCount = 0;
        deathNotesWritten = 0;
        loveNotesWritten = 0;
        narrativeLetters.clear();
        earnedAdvancements.clear();
        servedBookToCarriage.clear();
        weaponKills.clear();
        playerKills = 0;
        damageDealt = 0.0;
        damageTaken = 0.0;
        encounteredMobs.clear();
        befriendedMobs.clear();
        friendAppearance = null;
        friendFeeling = Float.NEGATIVE_INFINITY;
        killedAppearance = null;
        echoesKilled = 0;
        carriagesSinceChest = 0;
        maxCarriagesNoChest = 0;
    }

    /** Reset everything (called on respawn). */
    public void resetAll() {
        clearChests();
        resetCarts();
        resetDeathStats();
    }
}
