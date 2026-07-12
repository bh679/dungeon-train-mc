package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.CompletionistAdvancement;
import games.brennan.dungeontrain.advancement.FarStartAdvancement;
import games.brennan.dungeontrain.advancement.GlobalAchievementStore;
import games.brennan.dungeontrain.advancement.GlobalBookBurnStats;
import games.brennan.dungeontrain.advancement.GlobalNarrativeProgress;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.advancement.NothingButBooksAdvancement;
import games.brennan.dungeontrain.advancement.PacifistAdvancement;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.narrative.NarrativeProgress;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.narrative.PlayerPlayedMarker;
import games.brennan.dungeontrain.narrative.RandomBookFile;
import games.brennan.dungeontrain.narrative.RandomBookRegistry;
import games.brennan.dungeontrain.narrative.StartingBookContext;
import games.brennan.dungeontrain.narrative.StartingBookFactory;
import games.brennan.dungeontrain.narrative.StartingBookRegistry;
import games.brennan.dungeontrain.narrative.StoryFile;
import games.brennan.dungeontrain.narrative.StoryRegistry;
import games.brennan.dungeontrain.net.AdvancementsHintPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Wires the mod's custom criterion triggers ({@link ModAdvancementTriggers})
 * to gameplay signals, and bridges per-world vanilla advancement state to
 * the cross-world {@link GlobalAchievementStore} sidecar.
 *
 * <p>Hooks:
 * <ul>
 *   <li>{@link PlayerInteractEvent.RightClickBlock} (chest/trapped/barrel) →
 *       update {@link PlayerRunState#uniqueChests}, fire
 *       {@link ModAdvancementTriggers#UNIQUE_CHESTS_OPENED}.</li>
 *   <li>{@link PlayerEvent.PlayerRespawnEvent} → reset
 *       {@link PlayerRunState} (both streak set and cart counter).</li>
 *   <li>{@link PlayerEvent.PlayerLoggedInEvent} → grant the multiplayer-join
 *       advancement when applicable, absorb this world's already-earned
 *       advancements into the sidecar, then read the sidecar and replay any
 *       previously-earned advancements onto this world's
 *       {@code PlayerAdvancements}.</li>
 *   <li>{@link AdvancementEvent.AdvancementEarnEvent} → append any granted
 *       GUI-visible advancement (vanilla, Dungeon Train, or other mod) to the
 *       sidecar so it survives world deletion; the hidden recipe tree is
 *       excluded (see {@link #shouldPersist}).</li>
 * </ul>
 *
 * <p>Static notify methods are called by other event classes:
 * <ul>
 *   <li>{@link #notifyCartAdvance(ServerPlayer, int)} — called from
 *       {@link BoardingProgressEvents} on the leader's per-tick advance.</li>
 *   <li>{@link #notifyStoryProgress(ServerPlayer)} — called from
 *       {@link games.brennan.dungeontrain.narrative.NarrativeBookEvents}
 *       after every story / random-book read.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class AchievementEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Per-player debounce for {@link PlayerInteractEvent.RightClickBlock}:
     * RightClickBlock can fire multiple times for a single user action
     * (server + client roundtrip, off-hand pass) — within this many ticks
     * we treat a click on the same block as a duplicate of the previous.
     */
    private static final int CHEST_CLICK_DEBOUNCE_TICKS = 10;

    /** Per-player last-right-clicked chest pos + tick, for debouncing only. */
    private static final Map<UUID, BlockPos> LAST_CHEST_POS = new HashMap<>();
    private static final Map<UUID, Long> LAST_CHEST_TICK = new HashMap<>();

    /**
     * Set while {@link #onPlayerLoggedIn} replays the cross-world sidecar onto
     * this world's advancements. That replay calls {@code award(...)}, which
     * re-fires {@link AdvancementEvent.AdvancementEarnEvent} for every
     * previously-earned advancement — without this guard the keybind hint would
     * spam the joining player on every login. Server-thread scoped (login and
     * award both run on the server thread), mirroring the gamerule / Discord
     * announce suppression that wraps the same loop.
     */
    private static volatile boolean replaying = false;

    private AchievementEvents() {}

    // ---------------- Chest opens ----------------

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        BlockState state = event.getLevel().getBlockState(pos);
        Block block = state.getBlock();
        // ChestBlock covers both regular chests and trapped chests
        // (TrappedChestBlock extends ChestBlock in vanilla 1.21.1).
        if (!(block instanceof ChestBlock || block instanceof BarrelBlock)) return;
        // Skip the "sneaking-with-block-to-place" case so adjacent placements
        // don't get counted as chest opens.
        if (player.isShiftKeyDown() && event.getItemStack().getItem() instanceof BlockItem) return;
        // Debounce duplicate-fire on the same pos within a short window.
        UUID uuid = player.getUUID();
        long tick = event.getLevel().getGameTime();
        BlockPos lastPos = LAST_CHEST_POS.get(uuid);
        Long lastTick = LAST_CHEST_TICK.get(uuid);
        if (pos.equals(lastPos) && lastTick != null && tick - lastTick < CHEST_CLICK_DEBOUNCE_TICKS) return;
        LAST_CHEST_POS.put(uuid, pos.immutable());
        LAST_CHEST_TICK.put(uuid, tick);

        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        // Flat per-open counter for the death-screen summary — increments on
        // every (debounced) chest/barrel open, including repeats of the same
        // position. Decorated-pot breaks feed the same counter from
        // RunStatsEvents.onPotBreak.
        run.incrementContainersOpened();
        boolean added = run.addChestPos(pos);
        if (!added) {
            // Streak broken — duplicate open. Reset and start fresh from this chest.
            run.clearChests();
            run.addChestPos(pos);
            LOGGER.debug("[DungeonTrain] Chest streak reset by duplicate open at {} (player {})",
                pos, player.getName().getString());
        }
        ModAdvancementTriggers.UNIQUE_CHESTS_OPENED.get().trigger(player, run.chestStreak());
    }

    // ---------------- Carriages-in-run ----------------

    /**
     * Called from {@link BoardingProgressEvents} every tick the leader player
     * advances along the train. Accepts signed {@code delta}: forward
     * (positive) and backward (negative).
     *
     * <p>{@link ModAdvancementTriggers#CARTS_IN_RUN} (tiers 100 / 1000 / 10000)
     * fires off {@code Math.abs(PlayerRunState.travelledCarriageIndex())} —
     * the same per-player counter that
     * {@link games.brennan.dungeontrain.event.MobDifficultyEvents} reads for
     * spawn-time tier (via {@code max(...)} across online players). The
     * on-screen difficulty progression and the achievement progression now
     * stay coherent: both reset on the player's death, both tick off the
     * same leader delta.</p>
     *
     * <p>{@link ModAdvancementTriggers#CARTS_BOTH_DIRECTIONS} ("The Long Way
     * Back") uses the per-life forward / backward subtotals (absolute deltas)
     * tracked in {@link PlayerRunState#cartsForwardSinceDeath} /
     * {@link PlayerRunState#cartsBackwardSinceDeath} — backward travel
     * contributes positively there, while it subtracts from the signed
     * {@code travelledCarriageIndex} used above.</p>
     *
     * <p>{@link PacifistAdvancement} (100 / 250 / 1000 tiers) uses the same
     * travelled-carriage counter as {@code carts_100}, gated on
     * {@link PlayerRunState#damageDealt()} being exactly zero — both reset
     * together on death, so a fresh life always has a clean shot at the chain.</p>
     */
    public static void notifyCartAdvance(ServerPlayer player, int delta) {
        if (delta == 0) return;
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        run.recordCartMovement(delta);
        // Carriage-distance milestones read the EFFECTIVE progress (raw travel + the
        // admin difficulty offset), so an admin-set difficulty is treated as genuine
        // progress here too. The forward/backward subtotals below stay on the real
        // signed movement — "The Long Way Back" is about actual back-and-forth travel.
        int effectiveTravelled = Math.abs(
            DifficultyProgression.effectiveTravelled(run.travelledCarriageIndex()));
        ModAdvancementTriggers.CARTS_IN_RUN.get()
            .trigger(player, effectiveTravelled);
        ModAdvancementTriggers.CARTS_BOTH_DIRECTIONS.get()
            .trigger(player, run.cartsForwardSinceDeath(), run.cartsBackwardSinceDeath());
        // "The Far Start" — same travelled-carriage counter as carts_100 but a
        // longer haul, reached while still carrying the (unread, unburned)
        // starting book. Gated cheaply, so the inventory scan only runs past the threshold.
        FarStartAdvancement.checkAndGrant(player, effectiveTravelled);
        // "Pacifist" chain — same travelled-carriage counter as carts_100 but
        // requires zero damage dealt this life at each threshold.
        PacifistAdvancement.checkAndGrant(player, effectiveTravelled, run.damageDealt());
    }

    // ---------------- Biome-diversity milestones ----------------

    /**
     * Called from {@link BoardingProgressEvents} when a boarded player's
     * distinct-biome count grows. {@code distinctBiomes} is the player's
     * single-life {@link games.brennan.dungeontrain.player.PlayerBiomeProgress#biomeCount()}.
     * Drives the exploration count tiers ("Far Afield", "Many Lands", "World
     * Without End", "Terra Omnia").
     */
    public static void notifyBiomesVisited(ServerPlayer player, int distinctBiomes) {
        ModAdvancementTriggers.BIOMES_VISITED.get().trigger(player, distinctBiomes);
    }

    // ---------------- Train-time milestones ----------------

    /**
     * Called from {@link BoardingProgressEvents} per-scan for every boarded
     * player. {@code totalTicks} is the player's cumulative
     * {@link games.brennan.dungeontrain.advancement.GlobalPlayerStats#trainTicks}
     * across all worlds and sessions.
     */
    public static void notifyTrainTime(ServerPlayer player, long totalTicks) {
        ModAdvancementTriggers.TRAIN_TIME.get().trigger(player, totalTicks);
    }

    /**
     * Called from {@link BoardingProgressEvents} per-scan for every boarded
     * player. {@code runTicks} is the player's single-life
     * {@link PlayerRunState#trainTimeTicks} (boarded-only, resets on death).
     * Drives the single-life time milestones ("One for the Road", "Marathon
     * Passenger"), the per-run twins of the cross-world train-time chain.
     */
    public static void notifyRunTrainTime(ServerPlayer player, long runTicks) {
        ModAdvancementTriggers.RUN_TRAIN_TIME.get().trigger(player, runTicks);
    }

    /**
     * Called from {@link BoardingProgressEvents} per-scan for every boarded
     * player. {@code runMeters} is the player's single-life
     * {@link PlayerRunState#distanceBlocks} (boarded-only, resets on death).
     * Drives the single-life distance milestones.
     */
    public static void notifyRunDistance(ServerPlayer player, double runMeters) {
        ModAdvancementTriggers.RUN_DISTANCE.get().trigger(player, runMeters);
    }

    /**
     * Called from {@link BoardingProgressEvents} per-scan for every boarded
     * player. {@code lifetimeMeters} is the player's cross-world
     * {@link GlobalPlayerStats#distanceBlocks(UUID)} accumulator. Drives the
     * lifetime distance milestones.
     */
    public static void notifyLifetimeDistance(ServerPlayer player, double lifetimeMeters) {
        ModAdvancementTriggers.LIFETIME_DISTANCE.get().trigger(player, lifetimeMeters);
    }

    /**
     * Called from {@link games.brennan.dungeontrain.narrative.NarrativeBookEvents}
     * after every random-book held-right-click. {@code totalReads} is the
     * player's cumulative {@link GlobalPlayerStats#randomBooksRead} across
     * all worlds and sessions. Re-reads count.
     */
    public static void notifyRandomBooksRead(ServerPlayer player, long totalReads) {
        ModAdvancementTriggers.RANDOM_BOOKS_READ.get().trigger(player, totalReads);
    }

    /**
     * Called from {@link games.brennan.dungeontrain.narrative.NarrativeBookEvents}
     * after every starting-book held-right-click. {@code totalReads} is the
     * player's cumulative {@link GlobalPlayerStats#startingBooksRead} across
     * all worlds and sessions. Re-reads count.
     */
    public static void notifyStartingBooksRead(ServerPlayer player, long totalReads) {
        ModAdvancementTriggers.STARTING_BOOKS_READ.get().trigger(player, totalReads);
    }

    /**
     * Called from {@link games.brennan.dungeontrain.event.StartingBookEvents#onEntityJoinLevel}
     * when a starting/random book burns without ever having been opened.
     * {@code totalBurned} is the player's cumulative
     * {@link GlobalBookBurnStats#booksBurnedUnread} across all worlds and sessions.
     */
    public static void notifyBooksBurnedUnread(ServerPlayer player, long totalBurned) {
        ModAdvancementTriggers.BOOKS_BURNED_UNREAD.get().trigger(player, totalBurned);
    }

    // ---------------- Player encounters ----------------

    /**
     * Called from {@link games.brennan.dungeontrain.event.RunStatsEvents}'s
     * proximity scan when a player comes near a PlayerMob it has not yet seen
     * this run. {@code total} is the player's cumulative
     * {@link GlobalPlayerStats#playersEncountered} across all worlds and
     * sessions. Drives the "Strangers on a Train" milestone.
     */
    public static void notifyEncounter(ServerPlayer player, long total) {
        ModAdvancementTriggers.ENCOUNTERED_PLAYERS.get().trigger(player, total);
    }

    /**
     * Called from {@link games.brennan.dungeontrain.event.RunStatsEvents}'s
     * proximity scan when a player comes within ~4 blocks of another passenger
     * (a PlayerMob or another real player) while both are on the train. Drives
     * the "I'm Not Alone" advancement; vanilla dedupe keeps it to one grant.
     */
    public static void notifyProximityOnTrain(ServerPlayer player) {
        ModAdvancementTriggers.PROXIMITY_ON_TRAIN.get().trigger(player);
    }

    /**
     * Fired whenever a player reads a book of any kind — narrative (lectern or
     * held), random, starting, shared/community, or a plain player-written
     * written book. Drives "The Enchiridion" (read your first book). Routed
     * through the generic {@link ModAdvancementTriggers#GAMEPLAY_ACTION} marker
     * with action id {@code read_any_book}; vanilla award is idempotent so
     * re-reads are harmless.
     */
    public static void notifyBookRead(ServerPlayer player) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "read_any_book");
    }

    /**
     * Fired when a player tags {@code @dev} / {@code @brennanhatton} in chat.
     * Drives "Summon The Creator". Routed through the generic
     * {@link ModAdvancementTriggers#GAMEPLAY_ACTION} marker with action id
     * {@code tagged_creator}; earns regardless of whether Brennan is online.
     */
    public static void notifyTaggedCreator(ServerPlayer player) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "tagged_creator");
    }

    /**
     * Fired whenever a relayed Developer message is actually delivered to a player's in-game
     * chat. Drives "The Creator Answers". Routed through the generic
     * {@link ModAdvancementTriggers#GAMEPLAY_ACTION} marker with action id
     * {@code creator_answered}; called from
     * {@link games.brennan.dungeontrain.event.DevMessageConsent} at both delivery sites
     * (immediate delivery and held-message flush on consent).
     */
    public static void notifyCreatorAnswered(ServerPlayer player) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "creator_answered");
    }

    // ---------------- Narrative progress ----------------

    /**
     * Called from {@link games.brennan.dungeontrain.narrative.NarrativeBookEvents}
     * after every successful story-letter or random-book-variant mark. Cheap:
     * scans the registry-vs-progress state and fires the appropriate
     * trigger only if a set just transitioned to complete; vanilla
     * advancement dedupe suppresses repeat grants.
     */
    public static void notifyStoryProgress(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        NarrativeProgressData data = NarrativeProgressData.get(overworld);

        if (anyStoryRead()) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "any_story");
        }
        if (allFaulthurstTitlesSeen(data)) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "faulthurst_all_titles");
        }
        if (allFaulthurstSeen(data)) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "faulthurst");
        }
        if (allQuietRulesSeen(data)) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "quiet_rules");
        }
        if (allStoriesRead()) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "all_stories");
        }
        if (allStoryVariantsSeen()) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "all_story_variants");
        }
        if (allStartingBookTitlesSeen(data)) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "starting_books_all_titles");
        }
        // Starting-book sets are folder-driven. The grand-slam all_starting_books
        // set spans every context; each context that declares an
        // achievementSetId() also fires as its own "read this folder" set. A new
        // dimension book-set needs no change here — only a case in
        // StartingBookContext.achievementSetId() plus its advancement JSON + lang.
        Set<String> dimSeen = PlayerPlayedMarker.seenDimensionVariants(player.getUUID());
        if (allStartingBooksSeen(data, dimSeen)) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "all_starting_books");
        }
        for (StartingBookContext ctx : StartingBookContext.values()) {
            Optional<String> setId = ctx.achievementSetId();
            if (setId.isPresent() && dimensionSetComplete(ctx, dimSeen)) {
                ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, setId.get());
            }
        }
    }

    /**
     * Merge this world's per-world story reads (byStory + story-variant seen)
     * into the cross-world {@link GlobalNarrativeProgress}. Idempotent
     * (set-union). Called on login so progress predating the global store — or
     * earned in another world — counts toward the cross-world story
     * advancements. Lectern selection still reads the per-world data, untouched.
     */
    private static void absorbWorldStoryProgressIntoGlobal(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        NarrativeProgressData data = NarrativeProgressData.get(server.overworld());
        // Both snapshots are already in the shape GlobalNarrativeProgress wants
        // (byStory: basename -> letters; variants: "basename#letter" -> variants),
        // so hand them straight to the single-write bulk merge.
        Map<String, Set<Integer>> letters = new HashMap<>();
        data.snapshotStories().forEach((basename, p) -> letters.put(basename, p.readLetters()));
        Map<String, Set<Integer>> variants = new HashMap<>();
        data.storyVariantsSnapshot().forEach((key, p) -> variants.put(key, p.readLetters()));
        GlobalNarrativeProgress.absorbAll(letters, variants);
    }

    /**
     * True when every variant of every letter of every story currently
     * loaded by {@link StoryRegistry} has been marked seen in the cross-world
     * {@link GlobalNarrativeProgress}. Drives the
     * {@code read_all_story_variants} ("Every Reality, Every Word")
     * achievement. Empty registry → false.
     */
    private static boolean allStoryVariantsSeen() {
        java.util.List<String> all = StoryRegistry.basenames();
        if (all.isEmpty()) return false;
        Map<String, NarrativeProgress> snapshot = GlobalNarrativeProgress.storyVariantsSnapshot();
        for (String basename : all) {
            StoryFile file = StoryRegistry.getByBasename(basename).orElse(null);
            if (file == null) return false;
            for (var letter : file.letters()) {
                int totalVariants = letter.variants().size();
                if (totalVariants == 0) continue;
                NarrativeProgress p = snapshot.getOrDefault(
                    basename + "#" + letter.index(),
                    new NarrativeProgress()
                );
                Set<Integer> seen = p.readLetters();
                for (int v = 0; v < totalVariants; v++) {
                    if (!seen.contains(v)) return false;
                }
            }
        }
        return true;
    }

    /**
     * True when every starting book has at least one variant marked seen.
     * Drives the {@code starting_books_all_titles} milestone ("Welcome Back")
     * — weaker than {@link #allStartingBooksSeen}; requires every title to
     * have been encountered at least once, not every variant.
     */
    private static boolean allStartingBookTitlesSeen(games.brennan.dungeontrain.narrative.NarrativeProgressData data) {
        java.util.List<String> all = games.brennan.dungeontrain.narrative.StartingBookRegistry.basenames();
        if (all.isEmpty()) return false;
        java.util.Map<String, games.brennan.dungeontrain.narrative.NarrativeProgress> snapshot =
            data.startingBookSeenSnapshot();
        for (String basename : all) {
            var bookOpt = games.brennan.dungeontrain.narrative.StartingBookRegistry.getByBasename(basename);
            if (bookOpt.isEmpty()) return false;
            if (bookOpt.get().variants().isEmpty()) continue;
            games.brennan.dungeontrain.narrative.NarrativeProgress p =
                snapshot.getOrDefault(basename, new games.brennan.dungeontrain.narrative.NarrativeProgress());
            if (p.readLetters().isEmpty()) return false;
        }
        return true;
    }

    /**
     * True when every variant of every starting book — across <em>every</em>
     * {@link StartingBookContext} folder — has been seen by this player. Backs
     * the grand-slam {@code all_starting_books} set (Inter-Reality Passenger):
     * the ultimate "every book, every variant, every context", Nether and End
     * included.
     *
     * <p>Folder-driven, so it auto-expands as book content grows — add a book,
     * a variant, or a whole folder and it is required here with no code change.
     * The seen-store is chosen per folder by its delivery model: dimension-
     * routed folders (those with a {@link StartingBookContext#achievementSetId()})
     * cycle per-installation and live in {@code dimSeen}
     * ({@link PlayerPlayedMarker#seenDimensionVariants}); every other folder is
     * world-scoped in {@link NarrativeProgressData#startingBookSeenSnapshot()}.</p>
     */
    private static boolean allStartingBooksSeen(NarrativeProgressData data, Set<String> dimSeen) {
        Map<String, NarrativeProgress> snapshot = data.startingBookSeenSnapshot();
        boolean anyBook = false;
        for (StartingBookContext ctx : StartingBookContext.values()) {
            List<RandomBookFile> books = StartingBookRegistry.booksIn(ctx);
            if (books.isEmpty()) continue;
            anyBook = true;
            if (ctx.achievementSetId().isPresent()) {
                // Dimension-routed folder — defer to the per-folder check, which
                // reads the per-installation delivery store.
                if (!dimensionSetComplete(ctx, dimSeen)) return false;
                continue;
            }
            // Lifecycle folder — world-scoped seen snapshot, per variant.
            // Variant indices are 0-based (the NarrativeProgress.markRead
            // variant-0 fix landed in PR #290), so the full 0..total-1 range
            // must be present for completion.
            for (RandomBookFile book : books) {
                int total = book.variants().size();
                if (total == 0) continue;
                NarrativeProgress p = snapshot.getOrDefault(book.basename(), new NarrativeProgress());
                for (int i = 0; i < total; i++) {
                    if (!p.readLetters().contains(i)) return false;
                }
            }
        }
        return anyBook;
    }

    /**
     * True when a dimension-routed folder ({@code ctx}) has had every one of
     * its {@code (book, variant)} tuples delivered to this player. Backs the
     * per-folder sets declared by {@link StartingBookContext#achievementSetId()}
     * and is the per-folder subset of {@link #allStartingBooksSeen}. Reuses
     * {@link StartingBookFactory#hasUnseenDimensionTuples} so set membership
     * stays identical to the welcome-cycle's own notion of "exhausted"; auto-
     * expands when books or variants are added to the folder. An empty pool is
     * never complete.
     */
    private static boolean dimensionSetComplete(StartingBookContext ctx, Set<String> dimSeen) {
        return StartingBookRegistry.countFor(ctx) > 0
            && !StartingBookFactory.hasUnseenDimensionTuples(ctx, dimSeen);
    }

    /**
     * True when at least one story is fully read (every letter marked) in the
     * cross-world {@link GlobalNarrativeProgress}. Drives the
     * {@code collecting_stories} milestone — the player's first complete story.
     */
    private static boolean anyStoryRead() {
        for (String basename : StoryRegistry.basenames()) {
            StoryFile file = StoryRegistry.getByBasename(basename).orElse(null);
            if (file == null) continue;
            NarrativeProgress p = GlobalNarrativeProgress.progressFor(basename);
            if (p.isComplete(file.letters().size())) return true;
        }
        return false;
    }

    /**
     * True when every story currently loaded by {@link StoryRegistry} has
     * all letters marked read in the cross-world {@link GlobalNarrativeProgress}.
     * Mirrors the 1-based letter indexing used by
     * {@link NarrativeProgress#isComplete}.
     */
    private static boolean allStoriesRead() {
        java.util.List<String> all = StoryRegistry.basenames();
        if (all.isEmpty()) return false;
        for (String basename : all) {
            StoryFile file = StoryRegistry.getByBasename(basename).orElse(null);
            if (file == null) return false;
            NarrativeProgress p = GlobalNarrativeProgress.progressFor(basename);
            if (!p.isComplete(file.letters().size())) return false;
        }
        return true;
    }

    /**
     * True when every random-book file whose basename contains
     * {@code "faulthurst"} has every variant marked seen. Drives the
     * {@code faulthurst_all_variants} milestone ("Faulthurst's Favourite").
     */
    private static boolean allFaulthurstSeen(NarrativeProgressData data) {
        boolean anyFaulthurst = false;
        for (String basename : RandomBookRegistry.basenames()) {
            if (!basename.toLowerCase().contains("faulthurst")) continue;
            anyFaulthurst = true;
            RandomBookFile file = RandomBookRegistry.getByBasename(basename).orElse(null);
            if (file == null) return false;
            int total = file.variants().size();
            Set<Integer> seen = data.randomBookSnapshot()
                .getOrDefault(basename, new NarrativeProgress())
                .readLetters();
            for (int i = 0; i < total; i++) {
                if (!seen.contains(i)) return false;
            }
        }
        return anyFaulthurst;
    }

    /**
     * True when every Faulthurst random-book has at least one variant
     * marked seen. Drives the {@code faulthurst_all_titles} milestone
     * ("I Must Know"). Weaker than {@link #allFaulthurstSeen} — requires
     * the player to have encountered every title, not every variant.
     */
    private static boolean allFaulthurstTitlesSeen(NarrativeProgressData data) {
        boolean anyFaulthurst = false;
        for (String basename : RandomBookRegistry.basenames()) {
            if (!basename.toLowerCase().contains("faulthurst")) continue;
            anyFaulthurst = true;
            Set<Integer> seen = data.randomBookSnapshot()
                .getOrDefault(basename, new NarrativeProgress())
                .readLetters();
            if (seen.isEmpty()) return false;
        }
        return anyFaulthurst;
    }

    /**
     * True when every variant of every random-book whose basename contains
     * {@code "rules"} has been marked seen. Drives the {@code read_all_rules}
     * ("Know The Rules") achievement — currently the single {@code quiet_rules}
     * book ("Quiet Rules of the Train"), each variant a numbered Rule.
     *
     * <p>Fully registry-driven: the per-variant count comes from
     * {@code file.variants().size()} and the membership from the
     * {@code contains("rules")} matcher, so adding/removing Rules — or adding a
     * whole new {@code *rules*} book — re-scales the requirement with no code
     * change. An empty match set (no rules book loaded) → false.</p>
     */
    private static boolean allQuietRulesSeen(NarrativeProgressData data) {
        boolean anyRules = false;
        for (String basename : RandomBookRegistry.basenames()) {
            if (!basename.toLowerCase().contains("rules")) continue;
            anyRules = true;
            RandomBookFile file = RandomBookRegistry.getByBasename(basename).orElse(null);
            if (file == null) return false;
            int total = file.variants().size();
            Set<Integer> seen = data.randomBookSnapshot()
                .getOrDefault(basename, new NarrativeProgress())
                .readLetters();
            for (int i = 0; i < total; i++) {
                if (!seen.contains(i)) return false;
            }
        }
        return anyRules;
    }

    // ---------------- Respawn reset ----------------

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.isEndConquered()) return; // End → overworld portal, not a death.
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        run.resetAll();
        // Exploration progress is per-life too — clear distinct biomes so the
        // count tiers (and "Terra Omnia") restart for the new run.
        player.getData(ModDataAttachments.PLAYER_BIOME_PROGRESS.get()).clear();
        // Per-life travelled-carriage-index is now 0; push the HUD packet
        // immediately so the overlay reflects the reset without waiting for
        // the next 10-tick BoardingProgressEvents scan.
        BoardingProgressEvents.sendPlayerHudPacket(player);
        LOGGER.debug("[DungeonTrain] Respawn: reset PlayerRunState for {}", player.getName().getString());
    }

    // ---------------- Multiplayer join ----------------

    /**
     * Grant {@code dungeontrain:multiplayer_join} when the world this player
     * just joined is a multiplayer one. A dedicated server (or any host this
     * player doesn't own) is multiplayer on its own; an integrated LAN host
     * becomes multiplayer the moment a second player is connected.
     *
     * <p>When detected, every currently-connected player is awarded — so the
     * LAN host who was already online earns "someone joined yours" alongside
     * the guest who earns "joined a multiplayer world". Opening a single-player
     * world to LAN does not re-login the host, so awarding all online players
     * when the guest connects is what retroactively grants the host. Vanilla
     * advancement dedupe makes the repeated firing idempotent (a no-op once the
     * advancement is already complete).</p>
     */
    private static void awardMultiplayerJoinIfApplicable(ServerPlayer joined) {
        MinecraftServer server = joined.getServer();
        if (server == null) return;
        // Dedicated → isSingleplayerOwner is always false → multiplayer.
        // Integrated LAN → the joining guest is never the singleplayer owner.
        boolean multiplayer = !server.isSingleplayerOwner(joined.getGameProfile())
            || server.getPlayerCount() >= 2;
        if (!multiplayer) return;
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            ModAdvancementTriggers.MULTIPLAYER_JOIN.get().trigger(online);
        }
    }

    // ---------------- Global sidecar ↔ vanilla mirror ----------------

    /**
     * Persistence policy for the cross-world {@link GlobalAchievementStore}: an
     * advancement is persisted iff it appears in the advancements screen (has a
     * display). This captures vanilla, Dungeon Train, and other mods'
     * advancements alike, while excluding the hidden display-less
     * {@code recipes/*} tree — the same partition
     * {@link CompletionistAdvancement} uses to skip recipe-style advancements.
     * Single source of truth for the three sidecar call sites: earn-append
     * ({@link #onAdvancementEarn}), login back-fill
     * ({@link #absorbWorldAdvancementsIntoSidecar}), and logout revoke-reconcile
     * ({@link #onPlayerLoggedOut}).
     */
    private static boolean shouldPersist(AdvancementHolder holder) {
        return holder.value().display().isPresent();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Multiplayer-join grant runs first, independent of the sidecar replay
        // below — a first-time joiner has an empty sidecar and would otherwise
        // be skipped by the early-return inside replaySidecarAdvancements.
        awardMultiplayerJoinIfApplicable(player);
        // Absorb this world's story reads into the cross-world global record,
        // then re-evaluate the story advancements (which read the global store)
        // so progress earned in other worlds — or before this store existed —
        // can complete them here.
        absorbWorldStoryProgressIntoGlobal(player);
        notifyStoryProgress(player);
        // Absorb this world's already-earned advancements into the cross-world
        // store FIRST, so progress that predates this store — or a freshly-
        // updated player's complete vanilla / other-mod trees, which will never
        // re-fire AdvancementEarnEvent — is captured before the replay below
        // mirrors the store back onto this world.
        absorbWorldAdvancementsIntoSidecar(player);
        // Replay the cross-world sidecar onto this world's advancements, then
        // re-evaluate the "Everything Burrito" capstone. The capstone check runs
        // last and unconditionally: a player who finished every other advancement
        // on another world — or before this capstone existed — has no further
        // earn to fire the live check in onAdvancementEarn, so login is the only
        // place that backfill can happen. It reads this world's restored
        // (post-replay) progress and grants normally (replaying is false here).
        replaySidecarAdvancements(player);
        CompletionistAdvancement.checkAndGrant(player);
    }

    /**
     * Replay the cross-world {@link GlobalAchievementStore} sidecar onto this
     * world's {@code PlayerAdvancements}, re-granting every advancement the
     * player earned on this instance regardless of which world it happened in.
     * No-op when the sidecar is empty.
     *
     * <p>The replay {@code award(...)} calls re-fire
     * {@link AdvancementEvent.AdvancementEarnEvent}; {@link #replaying} guards
     * the keybind-hint and capstone re-check side effects for the duration,
     * while the announce gamerule + Discord suppression keep the replay silent
     * so a rejoining player isn't spammed for advancements already earned.</p>
     */
    private static void replaySidecarAdvancements(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Set<ResourceLocation> granted = GlobalAchievementStore.read(uuid);
        if (granted.isEmpty()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        // Suppress vanilla's chat broadcast on each award() by temporarily
        // disabling the gamerule that gates it. Toasts are already silenced
        // by vanilla: the first flushDirty packet after login carries
        // isFirstPacket=true → shouldReset=true, and the client skips toast
        // emission for reset packets.
        GameRules.BooleanValue announce =
            server.getGameRules().getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS);
        boolean wasAnnouncing = announce.get();
        if (wasAnnouncing) announce.set(false, server);
        // Mirror that silence to the bundled Discord Presence channel. Its
        // AdvancementEarnEvent listener is independent of the announce gamerule,
        // so without this each replayed grant would re-post the advancement to
        // Discord — double-announcing one the player already earned in a prior
        // world. The gate is thread-local and scoped to just this replay loop:
        // genuine first-time earns, and a server's global announce setting, are
        // unaffected. (replayed is an int[] holder so the lambda can mutate it.)
        int[] replayed = {0};
        replaying = true;
        try {
            DiscordService.runWithAdvancementAnnounceSuppressed(() -> {
                for (ResourceLocation id : granted) {
                    AdvancementHolder holder = mgr.get(id);
                    if (holder == null) {
                        LOGGER.warn("[DungeonTrain] Globally-granted advancement {} not in registry — skipping",
                            id);
                        continue;
                    }
                    for (String key : holder.value().criteria().keySet()) {
                        if (player.getAdvancements().award(holder, key)) replayed[0]++;
                    }
                }
            });
        } finally {
            replaying = false;
            if (wasAnnouncing) announce.set(true, server);
        }
        if (replayed[0] > 0) {
            LOGGER.info("[DungeonTrain] Replayed {} criteria from global store for {} (silent)",
                replayed[0], player.getName().getString());
        }
    }

    /**
     * Absorb every already-earned, GUI-visible advancement in this world into the
     * cross-world {@link GlobalAchievementStore}, so it survives into other
     * worlds. Complements {@link #onAdvancementEarn}, which only captures
     * advancements earned <em>after</em> this store began tracking: a player who
     * updated to this version already has complete vanilla / other-mod trees that
     * will never re-fire {@link AdvancementEvent.AdvancementEarnEvent}, so without
     * this login-time sweep they'd never enter the store. Mirrors the
     * {@link #absorbWorldStoryProgressIntoGlobal} pattern. Idempotent set-union,
     * batched into a single {@link GlobalAchievementStore#appendAll} write, and
     * filtered by {@link #shouldPersist}.
     */
    private static void absorbWorldAdvancementsIntoSidecar(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        Set<ResourceLocation> done = new LinkedHashSet<>();
        for (AdvancementHolder holder : mgr.getAllAdvancements()) {
            if (!shouldPersist(holder)) continue;
            // Don't absorb a cheated world's gameplay/vanilla earns into the
            // cross-world profile; editor/* authoring still flows through.
            if (!RunIntegrity.persistsAdvancement(player, holder)) continue;
            if (player.getAdvancements().getOrStartProgress(holder).isDone()) {
                done.add(holder.id());
            }
        }
        int added = GlobalAchievementStore.appendAll(player.getUUID(), done);
        if (added > 0) {
            LOGGER.info("[DungeonTrain] Absorbed {} already-earned advancement(s) into global store for {}",
                added, player.getName().getString());
        }
    }

    /**
     * Flush this player's cumulative stats (train-time) to disk on logout
     * so a crash before server-stop doesn't lose their session's accrual.
     * Also evict from the in-memory cache — the next login re-loads.
     *
     * <p>Also reconciles the global achievement sidecar: any persisted
     * advancement (vanilla, Dungeon Train, or other mod) currently in the
     * sidecar but NOT in this world's state has been revoked via
     * {@code /advancement revoke} (since login-replay would otherwise
     * keep them in sync). Drop revoked entries from the sidecar so the
     * revoke sticks across worlds.</p>
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID uuid = player.getUUID();
        GlobalPlayerStats.flush(uuid);
        GlobalPlayerStats.evict(uuid);
        GlobalBookBurnStats.flush(uuid);
        GlobalBookBurnStats.evict(uuid);
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        Set<ResourceLocation> sidecar = GlobalAchievementStore.read(uuid);
        int removed = 0;
        for (ResourceLocation id : sidecar) {
            AdvancementHolder holder = mgr.get(id);
            if (holder == null) continue; // removed mod/datapack — leave it (may return)
            if (player.getAdvancements().getOrStartProgress(holder).isDone()) continue;
            if (GlobalAchievementStore.remove(uuid, id)) removed++;
        }
        if (removed > 0) {
            LOGGER.info("[DungeonTrain] Logout reconcile: removed {} revoked advancement(s) from sidecar for {}",
                removed, player.getName().getString());
        }
    }

    /**
     * Final flush on server stop. Catches every cached player at once;
     * complements the per-player logout flush for the case where the server
     * shuts down while players are still online.
     */
    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        GlobalPlayerStats.flushAll();
        GlobalBookBurnStats.flushAll();
    }

    /**
     * Throttled per-player check for "Nothing But Books" — the condition
     * (every main-storage slot holds a story book) isn't tied to any single
     * gameplay signal (item pickup, inventory-screen close, etc. could all
     * complete or break it), so a cheap periodic scan is the simplest correct
     * trigger. Once-per-second per player; {@link NothingButBooksAdvancement
     * #checkAndGrant} itself early-returns once already earned.
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 != 0) return;
        NothingButBooksAdvancement.checkAndGrant(player);
    }

    /**
     * Converted off the NeoForge bus (Stage 2a) — now a plain
     * {@link games.brennan.dungeontrain.platform.event.DtAdvancementEarnCallback}
     * registered via {@code DtEvents.ADVANCEMENT_EARN} (see {@code NeoForgeServerEvents}),
     * fired by {@code NeoForgeAdvancementBridge}. Logic unchanged.
     */
    public static void onAdvancementEarn(net.minecraft.world.entity.Entity entity,
                                         AdvancementHolder advancement) {
        if (!(entity instanceof ServerPlayer player)) return;
        ResourceLocation id = advancement.id();
        // Persist across worlds: capture every GUI-visible advancement — vanilla,
        // Dungeon Train, and other mods alike — not just dungeontrain:*. The
        // hidden display-less recipe tree is filtered out by shouldPersist.
        // Cheated runs earn live but don't write to the cross-world profile
        // (editor/* authoring stays exempt) — see RunIntegrity.
        if (shouldPersist(advancement)
                && RunIntegrity.persistsAdvancement(player, advancement)
                && GlobalAchievementStore.append(player.getUUID(), id)) {
            LOGGER.info("[DungeonTrain] Wrote global achievement {} for {}",
                id, player.getName().getString());
        }
        // Nudge new players toward the advancements screen: on a genuine
        // (non-replay) gameplay-advancement earn, ping the client to maybe show
        // the keybind hint. Scoped to dungeontrain:* — this guard was previously
        // implied by the early-return above, before persistence broadened to all
        // namespaces; without it the hint + capstone re-check would now fire on
        // every vanilla/mod advancement earn. editor/* is excluded — a
        // creative-mode dev tab. The client
        // decides whether to actually display it (gated on its local "opened
        // advancements" flag) and renders it with the live keybind.
        if (!replaying
                && id.getNamespace().equals(DungeonTrain.MOD_ID)
                && !id.getPath().startsWith("editor/")) {
            // Death-screen "accolades": record this genuine, non-editor Dungeon Train
            // earn into the per-life run state; read into the death packet on death.
            player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).recordEarnedAdvancement(id);
            DungeonTrainNet.sendTo(player, new AdvancementsHintPacket());
            // Re-evaluate the "Everything Burrito" capstone (every non-editor
            // advancement earned). Skip its own earn: the award inside
            // checkAndGrant re-fires this event, and the id guard avoids the
            // needless re-entry (the award is idempotent once done regardless).
            if (!id.equals(CompletionistAdvancement.ID)) {
                CompletionistAdvancement.checkAndGrant(player);
            }
        }
    }
}
