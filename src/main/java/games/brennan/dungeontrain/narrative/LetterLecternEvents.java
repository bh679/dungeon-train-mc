package games.brennan.dungeontrain.narrative;
import games.brennan.dungeontrain.DtCore;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.OpenLetterEditorPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
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
 * the unsigned book &amp; quill resting on the lectern as a "Letter X" draft.</p>
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
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
        // Book & quill only (WRITABLE_BOOK_CONTENT) — a signed written book keeps vanilla behaviour.
        ItemStack stack = event.getItemStack();
        if (!stack.has(DataComponents.WRITABLE_BOOK_CONTENT)) return;

        // Only intercept when the letter can actually be uploaded (feature on + network consent).
        // When the gate fails we do NOT cancel, so vanilla places the book & quill normally.
        if (!SharedBookGate.canWriteLetter(player)) return;

        // Suppress vanilla placement so the book stays in hand for signing. We deliberately do not
        // cancel the client's own prediction here — the server drives the screen open via S2C, and the
        // brief place-prediction rolls back before the (pages-carrying) screen opens.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);

        PENDING_LECTERN.put(player.getUUID(), GlobalPos.of(level.dimension(), pos.immutable()));
        DungeonTrainNet.sendTo(player,
                new OpenLetterEditorPacket(event.getHand().ordinal(), pos.immutable(), readPages(stack)));
        LOGGER.debug("[DungeonTrain] Letter: {} opened the sign screen from a lectern at {}",
                player.getName().getString(), pos);
    }

    /**
     * Client closed the sign screen WITHOUT signing (via {@code LetterDraftToLecternPacket}): leave
     * the unsigned book &amp; quill on the lectern as a "Letter X" draft. No-op if the player no longer
     * holds a book &amp; quill (they actually signed) or the lectern is occupied.
     */
    public static void handleDraftToLectern(ServerPlayer player, BlockPos pos) {
        PENDING_LECTERN.remove(player.getUUID());
        try {
            ItemStack book = findWritableInHand(player);
            if (book.isEmpty()) return;
            ServerLevel level = player.serverLevel();
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof LecternBlock)) return;
            if (state.getValue(LecternBlock.HAS_BOOK)) return; // occupied (e.g. a narrative lectern) — keep it in hand

            // Label the unsigned draft with its would-be series index before it rests on the lectern.
            MinecraftServer server = player.getServer();
            if (server != null) {
                long deaths = GlobalPlayerStats.totalDeaths(player.getUUID());
                int idx = NarrativeProgressData.get(server.overworld()).peekNextIndex(player.getUUID(), deaths);
                book.set(DataComponents.CUSTOM_NAME, Component.literal("Letter " + idx));
            }
            // tryPlaceBook splits one book off the hand stack onto the lectern block-entity.
            LecternBlock.tryPlaceBook(player, level, pos, state, book);
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
        public static void onLogout(net.minecraft.world.entity.player.Player leftPlayer) {
        PENDING_LECTERN.remove(leftPlayer.getUUID());
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
