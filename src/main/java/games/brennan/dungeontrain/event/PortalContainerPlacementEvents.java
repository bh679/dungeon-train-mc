package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalCorridorContainers;
import games.brennan.dungeontrain.portal.PortalPairIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps storage out of both copies of a dimensional carriage's corridor: chests, trapped chests,
 * barrels, hoppers, shulker boxes and hopper minecarts ({@link PortalCorridorContainers}).
 *
 * <p>The corridor exists twice — the hallway riding the train and its static twin at the room end —
 * and {@link games.brennan.dungeontrain.portal.PortalEditMirror} copies every edit from either to the
 * other so the crossing between them stays invisible. It copies block <i>state</i> only, so a
 * container mirrored is a second, empty container: what is stored in one copy is missing from the
 * other, and a shulker box broken in the copy drops a second box outright. A hopper minecart is not a
 * block, but it sits on the corridor's rails pulling items out of whatever copy is above it, which is
 * the same split by another route. Corridor cells are tested with
 * {@link PortalPairIndex#isCorridorCell}, the same index the mirror consults, so "cell the mirror
 * would copy" and "cell that refuses a container" cannot drift apart.</p>
 *
 * <p><b>The dimensional room is untouched.</b> It has no twin and no mirror, so a chest in it behaves
 * like a chest anywhere else.</p>
 *
 * <h2>Refused at the click, not undone afterwards</h2>
 * <p>The obvious hook, {@link BlockEvent.EntityPlaceEvent}, fires once the block is already in the
 * world: cancelling it puts the block down and takes it away again, which the player sees flicker,
 * and which momentarily feeds a real block change to the mirror. So the primary refusal is
 * {@link PlayerInteractEvent.RightClickBlock}, which runs <i>before</i> the item is used, and denies
 * only the item half ({@code setUseItem}) so right-clicking a chest with a box in hand still opens
 * the chest. The place events below stay as a backstop for placements that never pass through a
 * right-click at all. The client declines to predict the same block placements
 * ({@code ClientPortalContainerPrediction}) so nothing appears and vanishes; a minecart is spawned by
 * the server alone, so it needs no such help.</p>
 *
 * <p>Every refusal says the same two lines the shulker box always has. Breaking a container placed
 * before this existed, or stamped from a template, still works, so nothing is stranded.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalContainerPlacementEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String REFUSED_KEY_1 = "chat.dungeontrain.portal.no_shulker_box.1";
    private static final String REFUSED_KEY_2 = "chat.dungeontrain.portal.no_shulker_box.2";

    /** The beat between the two lines. The second is an afterthought, not a second warning. */
    private static final int SECOND_LINE_DELAY_TICKS = 40;

    /**
     * How long a player is left alone after being told.
     *
     * <p>A refused click costs nothing, so a player who keeps trying — or who simply holds the
     * button — would otherwise paper their chat with the same two lines twenty times a second.</p>
     */
    private static final int REPEAT_COOLDOWN_TICKS = 600;

    /** Player → game time they were last told. Server thread only. */
    private static final Map<UUID, Long> LAST_TOLD = new HashMap<>();

    /** Second lines not yet due. Server thread only, and never more than one per player. */
    private static final List<Pending> PENDING = new ArrayList<>();

    private record Pending(UUID player, long dueAt) {}

    private PortalContainerPlacementEvents() {}

    /**
     * The refusal that actually matters: deny the click before anything is placed.
     *
     * <p>A block's target cell is derived through {@link BlockPlaceContext}, which is what vanilla
     * itself asks — the face offset, unless the clicked block is replaceable, in which case the click
     * lands on it. Testing the clicked position instead would refuse a chest placed against the
     * <i>outside</i> of a wall, which is somewhere it is perfectly welcome. A minecart is different:
     * vanilla puts it on the rail that was clicked, so that is the cell tested.</p>
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getUseItem() == TriState.FALSE) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Item item = event.getItemStack().getItem();
        Player player = event.getEntity();

        BlockPos target;
        if (PortalCorridorContainers.isRefusedCartInCorridor(item)) {
            target = event.getPos();
        } else if (item instanceof BlockItem blockItem
                && PortalCorridorContainers.isRefusedInCorridor(blockItem.getBlock())) {
            target = new BlockPlaceContext(
                new UseOnContext(player, event.getHand(), event.getHitVec())).getClickedPos();
        } else {
            return;
        }
        if (!PortalPairIndex.isCorridorCell(level, target)) return;

        event.setUseItem(TriState.FALSE);
        refuse(player, target, BuiltInRegistries.ITEM.getKey(item).toString(), "click");
    }

    /**
     * Backstop for placements that never pass through a right-click — another mod placing on a
     * player's behalf. Reached only when the click-side refusal did not apply, so in ordinary play
     * this never fires.
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Block block = event.getPlacedBlock().getBlock();
        if (!PortalCorridorContainers.isRefusedInCorridor(block)) return;
        if (!PortalPairIndex.isCorridorCell(level, event.getPos())) return;

        event.setCanceled(true);
        refuse(event.getEntity(), event.getPos(), blockName(block), "place");
    }

    /**
     * The bulk-placement counterpart. Effortless Building — bundled in the modpack — places whole
     * rows at once through this event. The event is all-or-nothing, so one refused cell refuses the
     * placement: a half-laid row would be worse than none.
     */
    @SubscribeEvent
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // Every cell is already in the world by the time this fires, so read each back rather than
        // trusting the event's single placed state — the same approach PortalEditEvents takes.
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            BlockPos pos = snapshot.getPos();
            Block block = level.getBlockState(pos).getBlock();
            if (!PortalCorridorContainers.isRefusedInCorridor(block)) continue;
            if (!PortalPairIndex.isCorridorCell(level, pos)) continue;

            event.setCanceled(true);
            refuse(event.getEntity(), pos, blockName(block), "multi-place");
            return;
        }
    }

    private static String blockName(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /**
     * Say why — in chat, and in the log.
     *
     * <p>Two lines, a beat apart, rather than one action-bar flash: it reads better arriving the way
     * an advancement does, and it stays in the scrollback for a player who looks away.</p>
     *
     * <p>The log line exists because a refusal is otherwise invisible after the fact ("nothing was
     * placed" and "nobody tried" read identically). It is written on every refusal, including the
     * ones the cooldown silences.</p>
     */
    private static void refuse(Entity entity, BlockPos pos, String what, String via) {
        LOGGER.info("[DungeonTrain] Portal: refused {} at {} ({})", what, pos, via);
        if (!(entity instanceof ServerPlayer player)) return;

        long now = player.serverLevel().getGameTime();
        Long told = LAST_TOLD.get(player.getUUID());
        if (told != null && now - told < REPEAT_COOLDOWN_TICKS) return;
        LAST_TOLD.put(player.getUUID(), now);

        player.sendSystemMessage(Component.translatable(REFUSED_KEY_1).withStyle(ChatFormatting.GRAY));
        PENDING.add(new Pending(player.getUUID(), now + SECOND_LINE_DELAY_TICKS));
    }

    /** Deliver the second lines that have come due, and forget players who have left. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            if (now < pending.dueAt()) continue;
            it.remove();

            // A player who logged out between the two lines simply does not hear the second one.
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
            if (player != null) {
                player.sendSystemMessage(
                    Component.translatable(REFUSED_KEY_2).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /**
     * Drop a world's worth of state when the server stops, so a second world in the same session
     * does not inherit the first's cooldowns or owe anybody a second line.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_TOLD.clear();
        PENDING.clear();
    }
}
