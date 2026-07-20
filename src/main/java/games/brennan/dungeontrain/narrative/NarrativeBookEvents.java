package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.GlobalNarrativeProgress;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.event.AchievementEvents;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Detects when a player opens a narrative-tagged book and advances the
 * player's progression.
 *
 * <p>Two trigger surfaces:
 * <ul>
 *   <li><b>Lectern right-click</b> — vanilla lectern interaction. The
 *       lectern's {@link LecternBlockEntity} holds the book ItemStack; we
 *       read the tag off it. Fires on every right-click while the lectern
 *       has a book — including page-flips — but {@link NarrativeProgress}
 *       is set-based so repeats no-op.</li>
 *   <li><b>Held book right-click</b> — player right-clicks a written book in
 *       hand. {@code RightClickItem} is server-side; the client opens the
 *       book screen locally on its own.</li>
 * </ul>
 *
 * <p>Both pathways funnel through {@link #recordRead} which dedupes against
 * {@link NarrativeProgressData} (the per-overworld store).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NarrativeBookEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NarrativeBookEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LecternBlock)) return;
        if (!state.getValue(LecternBlock.HAS_BOOK)) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof LecternBlockEntity lectern)) return;
        ItemStack book = lectern.getBook();
        if (book.isEmpty()) return;

        Optional<NarrativeBookTag.NarrativeIdentity> id = NarrativeBookTag.read(book);
        if (id.isEmpty()) return;

        recordRead(player, id.get());
        // Lectern (block) read — count it toward the death-screen books tally.
        // The held-book path (onRightClickItem) is intentionally excluded here:
        // it is already counted by RunStatsEvents.onBookRead.
        countLecternBookForRun(player, id.get());
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        Optional<NarrativeBookTag.NarrativeIdentity> id = NarrativeBookTag.read(stack);
        if (id.isEmpty()) return;
        recordRead(player, id.get());
    }

    /**
     * Increments the player's cumulative random-book read counter on every
     * held-right-click of a random-book item. Re-reads count — the counter
     * is event-driven, not deduped against the per-world seen set. Drives
     * the "Taking Notes" milestone via
     * {@link AchievementEvents#notifyRandomBooksRead}.
     *
     * <p>Only held-item right-clicks fire this; lectern right-clicks are
     * intentionally excluded because the lectern event fires on every
     * page-turn and would inflate the count.</p>
     */
    @SubscribeEvent
    public static void onRightClickRandomBookItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (RandomBookTag.read(stack).isEmpty()) return;
        BookReadMarkerTag.markOpened(stack);
        if (RunIntegrity.isCheated(player)) return; // global book-read stat frozen for cheated runs
        long newTotal = GlobalPlayerStats.addRandomBooksRead(player.getUUID(), 1L);
        AchievementEvents.notifyRandomBooksRead(player, newTotal);
    }

    /**
     * Increments the player's cumulative starting-book read counter on every
     * held-right-click of a starting-book item. Re-reads count. Drives the
     * "The Same But Different" milestone via
     * {@link AchievementEvents#notifyStartingBooksRead}.
     *
     * <p>Filter is {@link StartingBookTag#isStartingBook}; no overlap with
     * {@link #onRightClickRandomBookItem} because
     * {@link StartingBookFactory} never stamps {@link RandomBookTag}.</p>
     */
    @SubscribeEvent
    public static void onRightClickStartingBookItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!StartingBookTag.isStartingBook(stack)) return;
        BookReadMarkerTag.markOpened(stack);
        if (RunIntegrity.isCheated(player)) return; // global book-read stat frozen for cheated runs
        long newTotal = GlobalPlayerStats.addStartingBooksRead(player.getUUID(), 1L);
        AchievementEvents.notifyStartingBooksRead(player, newTotal);
    }

    /**
     * Fires the "read a stranger's book" advancement the first time a player holds-right-clicks a
     * book discovered from the community shared-book pool (chest loot credited to a real player —
     * see {@link SharedBookFoundTag}). One-shot marker via the generic
     * {@link ModAdvancementTriggers#GAMEPLAY_ACTION} trigger; vanilla advancement award is
     * idempotent, so repeat re-reads are harmless.
     *
     * <p>Held-right-click only, mirroring {@link #onRightClickRandomBookItem} /
     * {@link #onRightClickStartingBookItem} — a found book placed on a lectern is not covered, the
     * same scope cut already made for those two tags. Also stamps {@link BookReadMarkerTag} (same
     * as those two handlers) so the "burned without reading" milestone in
     * {@code StartingBookEvents} doesn't wrongly flag a book the player actually read before it
     * burned — see {@link BurnableBookTag}.</p>
     */
    @SubscribeEvent
    public static void onRightClickFoundSharedBookItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!SharedBookFoundTag.isFound(stack)) return;
        BookReadMarkerTag.markOpened(stack);
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "read_shared_book");

        // Record the community-book read world-scoped + monotonic (by relay pool id) so the shared-book
        // loot taper knows how much of the community pool this world has seen. Same held-right-click
        // scope as the advancement above — lectern reads of a found book stay uncounted, mirroring the
        // random/starting-book cut. No-op when the stack lacks a pool id (older discovered book).
        SharedBookReadTag.readId(stack).ifPresent(id -> {
            ServerLevel overworld = overworldOf(player);
            if (overworld == null) return;
            NarrativeProgressData data = NarrativeProgressData.get(overworld);
            if (data.markSharedBookEverRead(id)) {
                LOGGER.info("[DungeonTrain] SharedBook: world marked community book id {} read (by {})",
                    id, player.getName().getString());
            }
            // Per-player read record (persists across lives) so the shared-book loot selector can
            // deprioritise books THIS player has already read, independent of the world-scoped taper above.
            data.markPlayerReadShared(player.getUUID(), id);
        });
    }

    private static void recordRead(ServerPlayer player, NarrativeBookTag.NarrativeIdentity id) {
        ServerLevel overworld = overworldOf(player);
        if (overworld == null) return;
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        boolean letterNewly = data.markRead(id.storyBasename(), id.letterIndex());
        // Variant tracking is separate from letter-completion: a player can
        // re-read the same letter at a different lectern and reveal a fresh
        // variant. Pre-variant-stamp books decode with VARIANT_UNKNOWN — skip
        // the mark for them so the achievement never credits an unknown.
        boolean variantNewly = false;
        if (id.variantIndex() >= 0) {
            variantNewly = data.markStoryVariantSeen(id.storyBasename(), id.letterIndex(), id.variantIndex());
        }
        // Global, cross-world shared read-record. Per-world `data` above still
        // drives lectern selection; this store is what the story advancements
        // read, so "read every story" accumulates across worlds instead of
        // resetting each new world. Frozen for cheated runs — per-world `data`
        // above still records, so lectern selection is unaffected.
        if (!RunIntegrity.isCheated(player)) {
            GlobalNarrativeProgress.markRead(id.storyBasename(), id.letterIndex());
            if (id.variantIndex() >= 0) {
                GlobalNarrativeProgress.markVariantSeen(id.storyBasename(), id.letterIndex(), id.variantIndex());
            }
        }
        if (letterNewly) {
            LOGGER.info("[DungeonTrain] Narrative: world marked {} letter {} read (by {})",
                id.storyBasename(), id.letterIndex(), player.getName().getString());
        }
        if (variantNewly) {
            LOGGER.info("[DungeonTrain] Narrative: world marked {} letter {} variant {} seen (by {})",
                id.storyBasename(), id.letterIndex(), id.variantIndex(), player.getName().getString());
        }
        if (letterNewly || variantNewly) {
            games.brennan.dungeontrain.event.AchievementEvents.notifyStoryProgress(player);
        }
    }

    /**
     * Count a narrative letter read at a lectern toward the player's per-run
     * death-screen "books read" tally. Deduped via {@link PlayerRunState}'s
     * per-run {@code narrativeLetters} set so page-turns / re-opens of the same
     * letter count once, and the count resets each life.
     *
     * <p>Only the lectern (block-interaction) paths call this —
     * {@link #onRightClickBlock} and
     * {@link games.brennan.dungeontrain.narrative.block.NarrativeLecternBlock#useWithoutItem}.
     * Held-book reads are deliberately excluded: they already increment the
     * tally via {@code RunStatsEvents.onBookRead}, so counting them here would
     * double-count.</p>
     */
    public static void countLecternBookForRun(ServerPlayer player, NarrativeBookTag.NarrativeIdentity id) {
        String key = id.storyBasename() + "#" + id.letterIndex();
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        if (run.recordNarrativeRead(key)) {
            run.incrementBooksRead();
        }
    }

    /**
     * Count a served PLAYER narrative letter read at a lectern toward the per-run "books read" tally — the
     * discovery-half sibling of {@link #countLecternBookForRun}. Uses a {@code pnarr:seriesId#letterIndex}
     * dedup key, disjoint from mod-story keys, so a player letter and a mod letter that happen to share an
     * index never collide. Called only from the lectern lock path
     * ({@link games.brennan.dungeontrain.narrative.block.NarrativeLecternBlock#useWithoutItem}).
     */
    public static void countPlayerSeriesLetterForRun(ServerPlayer player,
                                                     PlayerNarrativeBookTag.PlayerNarrativeIdentity pid) {
        String key = "pnarr:" + pid.seriesId() + "#" + pid.letterIndex();
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        if (run.recordNarrativeRead(key)) {
            run.incrementBooksRead();
        }
    }

    /**
     * Pre-mutate random-book stacks the moment they reach a held hand slot,
     * so by the time the player right-clicks to open, the client already has
     * the corrected stack synced. Mutating later (inside
     * {@code RightClickItem}) is too late — the client opens
     * {@code BookViewScreen} locally from its stale stack before the server's
     * mutation reaches it.
     *
     * <p>Also stamps the "held" marker on discovered community books
     * ({@link SharedBookFoundTag}) — no content-swap needed for those, just the
     * held gate that unlocks the burn-after-reading flow (see
     * {@link BurnableBookTag}).</p>
     *
     * <p>Logic (world-scoped, random-book branch):
     * <ul>
     *   <li>Stack must be a {@link RandomBookTag}-stamped vanilla written book.</li>
     *   <li>If the world has not yet seen this {@code (basename, variantIndex)},
     *       mark it seen and leave the stack content as-is.</li>
     *   <li>If the world HAS seen it, ask {@link RandomBookFactory#pickUnseenForWorld}
     *       for an alternative tuple and swap the stack's content. The
     *       picker resets the world's tracking automatically when every
     *       loaded variant has been seen (silent cycle).</li>
     *   <li>After any content resolution, stamp the
     *       {@link RandomBookTag#NBT_HELD} "has been held" marker so the
     *       burn flow (see {@link games.brennan.dungeontrain.narrative.BurnableBookTag})
     *       can fire on subsequent drops. Random books that have never
     *       reached a held hand slot stay non-burnable, so chest / pot
     *       drops don't ignite on the floor.</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        EquipmentSlot slot = event.getSlot();
        if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) return;
        ItemStack stack = event.getTo();
        if (stack.isEmpty()) return;

        // Pending community-book placeholder — resolve it PER-PLAYER now that a hand is holding it, then
        // fall through to the shared-found branch for the same held-marker + author greeting a natively
        // discovered book gets. This is the IMMEDIATE path; the throttled inventory sweep in
        // {@link #onPlayerTick} is the safety net for every other way a book can be acquired.
        if (PlayerBookPendingTag.isPending(stack)) {
            if (!resolvePending(player, stack)) return; // pool cold/disabled — retry on a later hold or sweep
            // fall through — the shared-found branch below marks held + greets the author.
        }

        // Discovered community books: no content-swap needed (unlike random books below) — just
        // stamp the "held" marker so the burn-after-reading flow (BurnableBookTag) can fire on a
        // later close/drop, without igniting a book still sitting in an unopened chest.
        if (SharedBookFoundTag.isFound(stack)) {
            if (!SharedBookFoundTag.isHeld(stack)) {
                SharedBookFoundTag.markHeld(stack);
                LOGGER.info("[DungeonTrain] SharedBookFound: marked discovered book held (by {}) — will burn after reading",
                    player.getName().getString());
            }
            // If the holder authored this community book, greet them with how it's doing in the wild.
            FamiliarBookGreeter.maybeGreet(player, stack);
            return;
        }

        Optional<RandomBookTag.RandomBookIdentity> idOpt = RandomBookTag.read(stack);
        if (idOpt.isEmpty()) return;
        if (RandomBookTag.isHeld(stack)) return; // content already locked on a prior hold

        ServerLevel overworld = overworldOf(player);
        if (overworld == null) return;
        NarrativeProgressData data = NarrativeProgressData.get(overworld);

        RandomBookTag.RandomBookIdentity id = idOpt.get();
        if (!data.hasSeenRandomBook(id.basename(), id.variantIndex())) {
            // First time the world has seen this exact tuple — mark and let
            // the read flow proceed unchanged.
            data.markRandomBookSeen(id.basename(), id.variantIndex());
            data.markRandomBookVariantEverRead(id.basename(), id.variantIndex()); // monotonic — feeds shared-book loot chance
            LOGGER.info("[DungeonTrain] RandomBook: world marked {} variant {} seen (by {})",
                id.basename(), id.variantIndex(), player.getName().getString());
            games.brennan.dungeontrain.event.AchievementEvents.notifyStoryProgress(player);
            RandomBookTag.markHeld(stack);
            return;
        }

        // Already seen — try to swap to an unseen pick. The picker resets
        // tracking internally if every variant is seen, so the second-stage
        // call always returns something when the pool is non-empty.
        long seed = overworld.getGameTime() ^ player.getUUID().getLeastSignificantBits();
        Optional<RandomBookFactory.PickedBook> alt =
            RandomBookFactory.pickUnseenForWorld(data, seed);
        if (alt.isPresent()) {
            RandomBookFactory.replaceStackContent(stack, alt.get());
            data.markRandomBookSeen(alt.get().book().basename(), alt.get().variantIndex());
            data.markRandomBookVariantEverRead(alt.get().book().basename(), alt.get().variantIndex()); // monotonic — feeds loot chance
            LOGGER.info("[DungeonTrain] RandomBook: swapped seen {} v{} -> {} v{} (held by {})",
                id.basename(), id.variantIndex(),
                alt.get().book().basename(), alt.get().variantIndex(),
                player.getName().getString());
            games.brennan.dungeontrain.event.AchievementEvents.notifyStoryProgress(player);
        }
        // Stamp held even when the pool is empty and we left the stack as-is
        // — the player still held it; subsequent drops should burn.
        RandomBookTag.markHeld(stack);
    }

    /**
     * How often (in ticks) {@link #onPlayerTick} sweeps a player's inventory for unresolved community-book
     * placeholders. 20 ticks = once per second: fast enough that a book is a real player book by the time
     * anyone reads it, cheap enough to be invisible (a written-book item check over ~37 slots).
     */
    private static final int PENDING_SWEEP_INTERVAL_TICKS = 20;

    /**
     * Safety net for the pending-placeholder resolution.
     *
     * <p>{@link #onEquipmentChange} only fires for the MAINHAND / OFFHAND slots, but that is NOT how
     * players usually acquire these books: taking one out of a chiseled bookshelf hands it to
     * {@code Inventory#add} (never a hand slot), and the same is true of shift-clicking out of a chest,
     * hopper transfers, and item pickups. Those books would otherwise sit unresolved and be SEEN as
     * built-in mod books — which is exactly the slot's contract being broken, since
     * {@code ContainerContentsRoller} bakes a built-in book as the placeholder.</p>
     *
     * <p>So sweep the whole inventory on a throttled tick and resolve anything still pending. Cheap by
     * construction: the written-book item test rejects every non-book slot before any NBT is touched.</p>
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % PENDING_SWEEP_INTERVAL_TICKS != 0) return;
        // Cheap pre-checks before walking 37 slots: the feature must be on and the pool warm, or every
        // resolve would fail anyway.
        if (!SharedBookGate.canDiscover() || SharedBookPool.isEmpty()) return;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) continue; // item check first — no NBT cost
            if (!PlayerBookPendingTag.isPending(stack)) continue;
            resolvePending(player, stack);
        }
    }

    /**
     * Turn a pending placeholder into a real community book chosen for {@code player} by
     * {@link SharedBookSelector}, mutating {@code stack} in place. Returns {@code false} (leaving the
     * marker for a later retry) when discovery is off or the relay pool is empty/unreachable — the only
     * cases where keeping the built-in fallback is correct.
     */
    private static boolean resolvePending(ServerPlayer player, ItemStack stack) {
        if (!SharedBookGate.canDiscover() || SharedBookPool.isEmpty()) return false;
        ServerLevel ow = overworldOf(player);
        if (ow == null) return false;
        NarrativeProgressData data = NarrativeProgressData.get(ow);
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        java.util.UUID uuid = player.getUUID();

        // Window exhausted for this player? Pull the relay's next weight tier BEFORE selecting, and if
        // that fetch is in flight, defer rather than resolving — otherwise the selector can only relax
        // and re-serve a book they just had, which is precisely the repeat we are trying to avoid. The
        // 1s inventory sweep retries, so the book resolves a moment later out of the FRESH window.
        // A failed/unreachable fetch clears the in-flight flag, so this can never wedge: the next sweep
        // falls through and serves a repeat rather than leaving a built-in book forever.
        if (windowExhaustedFor(run)) {
            SharedBookPool.refreshAsync(WorldLanguage.hostLocale(player.getServer()));
            if (SharedBookPool.isRefreshInFlight()) return false;
        }
        SharedBookSelector.PlayerContext ctx = new SharedBookSelector.PlayerContext(
            // THIS holder's locale, not the world/host language: the pool is host-scoped at fetch time,
            // but which of those books count as "my language" is per-player.
            WorldInfoReporter.clientLanguage(player),
            id -> data.hasPlayerReadShared(uuid, id),
            run::wasServed,
            id -> { Integer c = run.servedCarriage(id); return c == null ? 0 : c; },
            run.travelledCarriageIndex(),
            DungeonTrainConfig.getSharedBookRepeatCarriages());
        // Vary the seed per stack as well as per tick: a sweep resolving several placeholders in the SAME
        // tick would otherwise feed the selector an identical seed and hand out the same book for each.
        long seed = ow.getGameTime() ^ uuid.getLeastSignificantBits() ^ (System.identityHashCode(stack) * 0x9E3779B9L);
        Optional<SharedBookPool.PoolBook> chosen = SharedBookSelector.select(SharedBookPool.snapshot(), ctx, seed);
        // Defensive only. The selector relaxes its per-life dedup on exhaustion, so it yields a book
        // whenever the pool has one — the built-in placeholder therefore survives ONLY when the relay is
        // unreachable / has no approved books, never merely because this player has already seen everything.
        if (chosen.isEmpty()) return false;
        SharedBookPool.PoolBook book = chosen.get();
        ItemStack shared = SharedBookPool.buildStack(book);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, shared.get(DataComponents.WRITTEN_BOOK_CONTENT));
        RandomBookTag.clearIdentity(stack);   // drop stale dt_random_book* keys
        PlayerBookPendingTag.clear(stack);    // resolved — no longer pending
        SharedBookFoundTag.stamp(stack);      // it's now a discovered player book
        SharedBookReadTag.stampId(stack, book.id());
        run.markServed(book.id(), run.travelledCarriageIndex()); // per-life dedup + far-behind escape
        LOGGER.info("[DungeonTrain] PlayerBook: resolved pending placeholder to community book {} (for {})",
            book.id(), player.getName().getString());

        // Serving that book may have just drained the window — pull the next tier now so the NEXT pickup
        // already has fresh content waiting (the pre-select guard above then has nothing to wait for).
        if (windowExhaustedFor(run)) {
            SharedBookPool.refreshAsync(WorldLanguage.hostLocale(player.getServer()));
        }
        return true;
    }

    /** True when {@code run} has been served every book in the current pool window — nothing new is left to offer. */
    private static boolean windowExhaustedFor(PlayerRunState run) {
        for (SharedBookPool.PoolBook b : SharedBookPool.snapshot()) {
            if (!run.wasServed(b.id())) return false;
        }
        return true;
    }

    private static ServerLevel overworldOf(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) return null;
        return server.overworld();
    }
}
