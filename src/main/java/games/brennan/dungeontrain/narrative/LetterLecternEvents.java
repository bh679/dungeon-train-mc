package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.OpenLetterEditorPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side half of the player-written "lectern letters" feature. When a player right-clicks a
 * lectern (vanilla or the mod's {@link games.brennan.dungeontrain.narrative.block.NarrativeLecternBlock})
 * while holding a book &amp; quill, and the feature is active for them
 * ({@link SharedBookGate#canWriteLetter}), this:
 * <ol>
 *   <li>suppresses vanilla placement so the book stays in hand (vanilla signing is inventory-slot
 *       based — a lectern can never be the sign target),</li>
 *   <li>records the lectern in {@link #PENDING_LECTERN} so the sign-interception mixin knows this
 *       sign is a letter and where to burn it, and</li>
 *   <li>tells the client to open the vanilla book edit/sign screen via {@link OpenLetterEditorPacket}.</li>
 * </ol>
 *
 * <p>If the player <b>signs</b>, {@code ServerGamePacketListenerImplSignBookMixin} consumes the
 * pending entry and routes to the letter upload + burn. If they <b>close without signing</b>, the
 * client sends {@code LetterDraftToLecternPacket}, which calls {@link #handleDraftToLectern} to leave
 * the book &amp; quill resting on the lectern as an unsigned draft, exactly as written.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LetterLecternEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Player UUID → the lectern (dimension + pos) whose letter sign screen they currently have open.
     * Set on right-click (open), cleared on sign (consumed by the mixin), on draft-place, or on
     * logout. One entry per player — a player can only have one book screen open at a time.
     */
    private static final Map<UUID, GlobalPos> PENDING_LECTERN = new ConcurrentHashMap<>();

    private LetterLecternEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LecternBlock)) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Only intercept when the letter can actually be uploaded (feature on + network consent).
        // When the gate fails we do NOT cancel, so vanilla places the book & quill normally (and a
        // resting draft stays readable through the vanilla lectern screen).
        if (!SharedBookGate.canWriteLetter(player)) return;

        // Two ways into the editor, and the book being edited always ends up in the player's HAND —
        // vanilla signing is inventory-slot based, so a lectern can never itself be the sign target.
        ItemStack stack = event.getItemStack();
        ItemStack editing;
        if (stack.has(DataComponents.WRITABLE_BOOK_CONTENT)) {
            // Book & quill in hand. Suppress vanilla placement so it stays there for signing. We
            // deliberately do not cancel the client's own prediction — the server drives the screen
            // open via S2C, and the brief place-prediction rolls back before the (pages-carrying)
            // screen opens. A signed written book keeps vanilla behaviour (no component, no match).
            editing = stack;
        } else if (stack.isEmpty()) {
            // Empty hand on a plain lectern holding an unsigned draft: take it back into the hand so
            // the player can finish and sign it — the "draft to finish later" half of the feature.
            // Returns EMPTY (→ vanilla) for anything else, including a signed book on a lectern.
            editing = takeDraftFromLectern(player, serverLevel, pos, state, event.getHand());
            if (editing.isEmpty()) return;
        } else {
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);

        PENDING_LECTERN.put(player.getUUID(), GlobalPos.of(level.dimension(), pos.immutable()));
        DungeonTrainNet.sendTo(player,
                new OpenLetterEditorPacket(event.getHand().ordinal(), pos.immutable(), readPages(editing)));
        LOGGER.debug("[DungeonTrain] Letter: {} opened the sign screen from a lectern at {}",
                player.getName().getString(), pos);
    }

    /**
     * Lift an unsigned book &amp; quill draft off a <b>plain</b> lectern into {@code hand}, returning it
     * (or {@link ItemStack#EMPTY} when this lectern holds nothing we should touch).
     *
     * <p>Deliberately narrow, because this hijacks an otherwise ordinary lectern right-click:</p>
     * <ul>
     *   <li>{@code minecraft:lectern} only — a {@link games.brennan.dungeontrain.narrative.block.NarrativeLecternBlock}
     *       holds mod story content and must keep opening it,</li>
     *   <li>the resting book must be a book &amp; quill ({@code WRITABLE_BOOK_CONTENT}); every signed
     *       book — narrative, library-carriage, player-written — falls through to vanilla untouched.</li>
     * </ul>
     */
    private static ItemStack takeDraftFromLectern(ServerPlayer player, ServerLevel level, BlockPos pos,
                                                  BlockState state, InteractionHand hand) {
        if (!state.is(Blocks.LECTERN)) return ItemStack.EMPTY;
        if (!state.getValue(LecternBlock.HAS_BOOK)) return ItemStack.EMPTY;
        if (!(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) return ItemStack.EMPTY;
        if (!lectern.getBook().has(DataComponents.WRITABLE_BOOK_CONTENT)) return ItemStack.EMPTY;

        ItemStack draft = clearPlainLecternBook(player, level, pos, state);
        if (draft.isEmpty()) return ItemStack.EMPTY;
        player.setItemInHand(hand, draft);
        return draft;
    }

    /**
     * Take the book off a <b>plain</b> lectern and leave the block visibly empty, returning what it
     * held ({@link ItemStack#EMPTY} if it held nothing or is not a plain lectern — a narrative lectern
     * keeps its own story state and is never cleared here).
     *
     * <p>Goes through vanilla {@link LecternBlock#resetBookState} rather than a bare
     * {@code setBlock} so {@code HAS_BOOK} / {@code POWERED}, the block game event and the
     * comparator/redstone update below the lectern all land the way vanilla does them.</p>
     */
    public static ItemStack clearPlainLecternBook(ServerPlayer player, ServerLevel level, BlockPos pos,
                                                  BlockState state) {
        if (!state.is(Blocks.LECTERN)) return ItemStack.EMPTY;
        if (!(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) return ItemStack.EMPTY;
        if (!lectern.hasBook()) return ItemStack.EMPTY;

        ItemStack book = lectern.getBook().copy();
        lectern.setBook(ItemStack.EMPTY);
        lectern.setChanged();
        LecternBlock.resetBookState(player, level, pos, state, /*hasBook*/ false);
        return book;
    }

    /**
     * Client closed the sign screen WITHOUT signing (via {@code LetterDraftToLecternPacket}): leave
     * the book &amp; quill on the lectern as an unsigned draft, unchanged. No-op if the player no longer
     * holds a book &amp; quill (they actually signed) or the lectern is occupied.
     */
    public static void handleDraftToLectern(ServerPlayer player, BlockPos pos) {
        PENDING_LECTERN.remove(player.getUUID());
        MinecraftServer server = player.getServer();
        if (server == null) return;
        // Deferred by one tick ON PURPOSE. Vanilla's "Done" button closes the screen and only THEN
        // sends its edit packet (BookEditScreen#init: setScreen(null) before saveChanges(false)), so
        // the pages the player just typed arrive immediately after this call. Placing the book now
        // would move it out of the hand slot that packet targets, vanilla would drop the edit, and
        // Done — the button whose whole job is to save the draft — would save nothing.
        server.tell(new TickTask(server.getTickCount() + 1, () -> placeDraftOnLectern(player, pos)));
    }

    /** The deferred body of {@link #handleDraftToLectern}; runs a tick later, so re-validate everything. */
    private static void placeDraftOnLectern(ServerPlayer player, BlockPos pos) {
        try {
            if (player.hasDisconnected()) return;
            ItemStack book = findWritableInHand(player);
            if (book.isEmpty()) return;
            ServerLevel level = player.serverLevel();
            if (!level.hasChunkAt(pos)) return; // lectern unloaded in the intervening tick
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof LecternBlock)) return;
            if (state.getValue(LecternBlock.HAS_BOOK)) return; // occupied (e.g. a narrative lectern) — keep it in hand

            // The draft rests on the lectern EXACTLY as the player wrote it — no rename, no added
            // components. It is an unfinished book & quill, not a titled artefact: the title is
            // chosen at sign time (LetterSigning falls back to "Letter X" only if left blank), and
            // stamping one here made an untouched draft look like something already signed.
            if (!(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) return;
            lectern.setBook(book.copyWithCount(1));
            lectern.setChanged();
            LecternBlock.resetBookState(player, level, pos, state, /*hasBook*/ true);
            level.playSound(null, pos, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1.0F, 1.0F);

            // Take the hand copy explicitly. Vanilla's tryPlaceBook routes through
            // ItemStack#consumeAndReturn, which SKIPS the shrink for a player with infinite
            // materials — so in creative the book was duplicated onto the lectern and the player
            // kept one. Pressing Done must hand back nothing, in either game mode.
            book.shrink(1);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Letter: draft-to-lectern failed at {}: {}", pos, t.toString());
        }
    }

    /**
     * Consume and return the lectern a player's current sign is a letter for, or {@code null} if this
     * sign did not originate from a lectern (a normal / shared-book sign). Called at HEAD of the
     * sign-interception mixin. The returned {@link GlobalPos} tells the caller where to spawn the burn.
     */
    public static GlobalPos consumePending(UUID uuid) {
        return PENDING_LECTERN.remove(uuid);
    }

    /** Drop any stale pending lectern when a player disconnects. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING_LECTERN.remove(event.getEntity().getUUID());
    }

    /** Raw page strings of a book &amp; quill, in order. Empty when the stack carries no writable content. */
    public static List<String> readPages(ItemStack stack) {
        WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (content == null) return List.of();
        return content.pages().stream().map(Filterable::raw).toList();
    }

    private static ItemStack findWritableInHand(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.has(DataComponents.WRITABLE_BOOK_CONTENT)) return main;
        ItemStack off = player.getOffhandItem();
        if (off.has(DataComponents.WRITABLE_BOOK_CONTENT)) return off;
        return ItemStack.EMPTY;
    }
}
