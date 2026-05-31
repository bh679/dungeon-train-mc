package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.GlobalAchievementStore;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
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
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Wires the mod's three custom criterion triggers ({@link ModAdvancementTriggers})
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
 *   <li>{@link PlayerEvent.PlayerLoggedInEvent} → read sidecar, replay any
 *       previously-earned advancements onto this world's
 *       {@code PlayerAdvancements}.</li>
 *   <li>{@link AdvancementEvent.AdvancementEarnEvent} → append granted
 *       {@code dungeontrain:*} advancements to the sidecar so they survive
 *       world deletion.</li>
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
     */
    public static void notifyCartAdvance(ServerPlayer player, int delta) {
        if (delta == 0) return;
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        run.recordCartMovement(delta);
        ModAdvancementTriggers.CARTS_IN_RUN.get()
            .trigger(player, Math.abs(run.travelledCarriageIndex()));
        ModAdvancementTriggers.CARTS_BOTH_DIRECTIONS.get()
            .trigger(player, run.cartsForwardSinceDeath(), run.cartsBackwardSinceDeath());
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

        if (anyStoryRead(data)) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "any_story");
        }
        if (allFaulthurstSeen(data)) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "faulthurst");
        }
        if (allStoriesRead(data)) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "all_stories");
        }
        if (allStoryVariantsSeen(data)) {
            ModAdvancementTriggers.STORY_SET_COMPLETED.get().trigger(player, "all_story_variants");
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
     * True when every variant of every letter of every story currently
     * loaded by {@link StoryRegistry} has been marked seen in
     * {@code data.storyVariantsSnapshot()}. Drives the
     * {@code read_all_story_variants} ("Every Reality, Every Word")
     * achievement. Empty registry → false.
     */
    private static boolean allStoryVariantsSeen(NarrativeProgressData data) {
        java.util.List<String> all = StoryRegistry.basenames();
        if (all.isEmpty()) return false;
        Map<String, NarrativeProgress> snapshot = data.storyVariantsSnapshot();
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
     * — Nether Return Again, End of the Line — and is the per-folder subset of
     * {@link #allStartingBooksSeen}. Reuses
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
     * True when at least one story is fully read (every letter marked).
     * Drives the {@code collecting_stories} milestone — the player's first
     * complete story.
     */
    private static boolean anyStoryRead(NarrativeProgressData data) {
        for (String basename : StoryRegistry.basenames()) {
            StoryFile file = StoryRegistry.getByBasename(basename).orElse(null);
            if (file == null) continue;
            NarrativeProgress p = data.progressFor(basename);
            if (p.isComplete(file.letters().size())) return true;
        }
        return false;
    }

    /**
     * True when every story currently loaded by {@link StoryRegistry} has
     * all letters marked read in {@code data}. Mirrors the 1-based letter
     * indexing used by {@link NarrativeProgress#isComplete}.
     */
    private static boolean allStoriesRead(NarrativeProgressData data) {
        java.util.List<String> all = StoryRegistry.basenames();
        if (all.isEmpty()) return false;
        for (String basename : all) {
            StoryFile file = StoryRegistry.getByBasename(basename).orElse(null);
            if (file == null) return false;
            NarrativeProgress p = data.progressFor(basename);
            if (!p.isComplete(file.letters().size())) return false;
        }
        return true;
    }

    /**
     * True when every random-book file whose basename contains
     * {@code "faulthurst"} has every variant marked seen.
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

    // ---------------- Respawn reset ----------------

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.isEndConquered()) return; // End → overworld portal, not a death.
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        run.resetAll();
        // Per-life travelled-carriage-index is now 0; push the HUD packet
        // immediately so the overlay reflects the reset without waiting for
        // the next 10-tick BoardingProgressEvents scan.
        BoardingProgressEvents.sendPlayerHudPacket(player);
        LOGGER.debug("[DungeonTrain] Respawn: reset PlayerRunState for {}", player.getName().getString());
    }

    // ---------------- Global sidecar ↔ vanilla mirror ----------------

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
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
        int replayed = 0;
        try {
            for (ResourceLocation id : granted) {
                AdvancementHolder holder = mgr.get(id);
                if (holder == null) {
                    LOGGER.warn("[DungeonTrain] Globally-granted advancement {} not in registry — skipping",
                        id);
                    continue;
                }
                for (String key : holder.value().criteria().keySet()) {
                    if (player.getAdvancements().award(holder, key)) replayed++;
                }
            }
        } finally {
            if (wasAnnouncing) announce.set(true, server);
        }
        if (replayed > 0) {
            LOGGER.info("[DungeonTrain] Replayed {} criteria from global store for {} (silent)",
                replayed, player.getName().getString());
        }
    }

    /**
     * Flush this player's cumulative stats (train-time) to disk on logout
     * so a crash before server-stop doesn't lose their session's accrual.
     * Also evict from the in-memory cache — the next login re-loads.
     *
     * <p>Also reconciles the global achievement sidecar: any
     * {@code dungeontrain:*} advancement currently in the sidecar but
     * NOT in this world's vanilla state has been revoked via
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
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        Set<ResourceLocation> sidecar = GlobalAchievementStore.read(uuid);
        int removed = 0;
        for (ResourceLocation id : sidecar) {
            if (!DungeonTrain.MOD_ID.equals(id.getNamespace())) continue;
            AdvancementHolder holder = mgr.get(id);
            if (holder == null) continue;
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
    }

    @SubscribeEvent
    public static void onAdvancementEarn(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ResourceLocation id = event.getAdvancement().id();
        if (!id.getNamespace().equals(DungeonTrain.MOD_ID)) return;
        if (GlobalAchievementStore.append(player.getUUID(), id)) {
            LOGGER.info("[DungeonTrain] Wrote global achievement {} for {}",
                id, player.getName().getString());
        }
    }
}
