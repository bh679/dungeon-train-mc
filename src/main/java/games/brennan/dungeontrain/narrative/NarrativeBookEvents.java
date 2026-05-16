package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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

    private static void recordRead(ServerPlayer player, NarrativeBookTag.NarrativeIdentity id) {
        ServerLevel overworld = overworldOf(player);
        if (overworld == null) return;
        NarrativeProgressData data = NarrativeProgressData.get(overworld);
        boolean changed = data.markRead(player.getUUID(), id.storyBasename(), id.letterIndex());
        if (changed) {
            LOGGER.info("[DungeonTrain] Narrative: marked {} letter {} read for {} ({})",
                id.storyBasename(), id.letterIndex(), player.getName().getString(), player.getUUID());
        }
    }

    private static ServerLevel overworldOf(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) return null;
        return server.overworld();
    }
}
