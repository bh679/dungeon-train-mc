package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.event.AchievementEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
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
        long newTotal = GlobalPlayerStats.addStartingBooksRead(player.getUUID(), 1L);
        AchievementEvents.notifyStartingBooksRead(player, newTotal);
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
     * Pre-mutate random-book stacks the moment they reach a held hand slot,
     * so by the time the player right-clicks to open, the client already has
     * the corrected stack synced. Mutating later (inside
     * {@code RightClickItem}) is too late — the client opens
     * {@code BookViewScreen} locally from its stale stack before the server's
     * mutation reaches it.
     *
     * <p>Logic (world-scoped):
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
        Optional<RandomBookTag.RandomBookIdentity> idOpt = RandomBookTag.read(stack);
        if (idOpt.isEmpty()) return;

        ServerLevel overworld = overworldOf(player);
        if (overworld == null) return;
        NarrativeProgressData data = NarrativeProgressData.get(overworld);

        RandomBookTag.RandomBookIdentity id = idOpt.get();
        if (!data.hasSeenRandomBook(id.basename(), id.variantIndex())) {
            // First time the world has seen this exact tuple — mark and let
            // the read flow proceed unchanged.
            data.markRandomBookSeen(id.basename(), id.variantIndex());
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

    private static ServerLevel overworldOf(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) return null;
        return server.overworld();
    }
}
