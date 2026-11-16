package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalPairIndex;
import games.brennan.dungeontrain.portal.PortalRoomContainers;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
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
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
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
 * Keeps storage out of the places a dimensional carriage cannot hold it: shulker boxes out of either
 * copy of its corridor, and chests, barrels, hoppers and shulker boxes out of its portal room and
 * every copy of that room.
 *
 * <h2>Corridors: shulker boxes</h2>
 * <p>A dimensional carriage exists twice — the corridor riding the train and its static twin at the
 * world floor — and {@link games.brennan.dungeontrain.portal.PortalEditMirror} copies every edit from
 * either to the other so the crossing between them stays invisible. It copies block <i>state</i>
 * only. For a torch that is exactly right and for a chest it is close enough, but a shulker box
 * mirrored as a bare block is a second shulker box: break the copy and it drops, which duplicates the
 * item outright. So the box cannot go down there. Corridor cells are tested with
 * {@link PortalPairIndex#isCorridorCell}, the same index the mirror consults, so "cell the mirror
 * would copy" and "cell that refuses a box" cannot drift apart.</p>
 *
 * <h2>Rooms: storage of any kind</h2>
 * <p>The room past the corridor does not stay put — see {@link PortalRoomContainers} — so what a
 * container holds there is lost or duplicated as the room is re-stamped, tiled, relocated or carried
 * between worlds. Room cells are tested with {@link PortalCarriageEvents#portalRoomBodyPairKey}: the
 * room body and every standing copy, corridors excluded, the same volume the boarding credit uses.
 * The carriage and its corridors are deliberately not part of this: chests, barrels and hoppers stay
 * placeable there.</p>
 *
 * <h2>Refused at the click, not undone afterwards</h2>
 * <p>The obvious hook, {@link BlockEvent.EntityPlaceEvent}, fires once the block is already in the
 * world: cancelling it puts the block down and takes it away again, which the player sees flicker,
 * and which momentarily feeds a real block change to the mirror. So the primary refusal is
 * {@link PlayerInteractEvent.RightClickBlock}, which runs <i>before</i> the item is used, and denies
 * only the item half ({@code setUseItem}) so right-clicking a chest with a box in hand still opens
 * the chest. The place events below stay as a backstop for placements that never pass through a
 * right-click at all. The client declines to predict the same placements
 * ({@code ClientPortalContainerPrediction}) so nothing appears and vanishes.</p>
 *
 * <p>Breaking a container placed before this existed, or stamped from a room's template, still
 * works, so nothing is stranded.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalContainerPlacementEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Where the refusal happened, which decides what the player is told. */
    enum Reason {
        CORRIDOR_SHULKER("chat.dungeontrain.portal.no_shulker_box.1",
            "chat.dungeontrain.portal.no_shulker_box.2"),
        ROOM_CONTAINER("chat.dungeontrain.portal.no_container.1",
            "chat.dungeontrain.portal.no_container.2");

        final String firstKey;
        final String secondKey;

        Reason(String firstKey, String secondKey) {
            this.firstKey = firstKey;
            this.secondKey = secondKey;
        }
    }

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

    private record Pending(UUID player, long dueAt, String key) {}

    private PortalContainerPlacementEvents() {}

    /**
     * Why {@code block} may not go at {@code pos}, or {@code null} if it may.
     *
     * <p>The room test is cheap to skip: it is only asked for one of the refused blocks, only in the
     * overworld (where every portal structure stands), and the query itself returns at once when no
     * structure is standing.</p>
     */
    static Reason refusalFor(ServerLevel level, BlockPos pos, Block block) {
        boolean shulker = block instanceof ShulkerBoxBlock;
        if (shulker && PortalPairIndex.isCorridorCell(level, pos)) return Reason.CORRIDOR_SHULKER;

        if (!PortalRoomContainers.isRefusedInRoom(block)) return null;
        if (!level.dimension().equals(Level.OVERWORLD)) return null;
        Integer pair = PortalCarriageEvents.portalRoomBodyPairKey(
            DungeonTrainWorldData.get(level).dims(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return pair != null ? Reason.ROOM_CONTAINER : null;
    }

    /**
     * The refusal that actually matters: deny the click before the block is ever placed.
     *
     * <p>The target cell is derived through {@link BlockPlaceContext}, which is what vanilla itself
     * asks — the face offset, unless the clicked block is replaceable, in which case the click lands
     * on it. Testing the clicked position instead would refuse a box placed against the <i>outside</i>
     * of a wall, which is somewhere it is perfectly welcome.</p>
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getUseItem() == TriState.FALSE) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getItemStack().getItem() instanceof BlockItem item)) return;
        Block block = item.getBlock();
        if (!PortalRoomContainers.isRefusedInRoom(block)) return;

        Player player = event.getEntity();
        BlockPos target = new BlockPlaceContext(
            new UseOnContext(player, event.getHand(), event.getHitVec())).getClickedPos();
        Reason reason = refusalFor(level, target, block);
        if (reason == null) return;

        event.setUseItem(TriState.FALSE);
        refuse(player, target, block, reason, "click");
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
        if (!PortalRoomContainers.isRefusedInRoom(block)) return;
        Reason reason = refusalFor(level, event.getPos(), block);
        if (reason == null) return;

        event.setCanceled(true);
        refuse(event.getEntity(), event.getPos(), block, reason, "place");
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
            if (!PortalRoomContainers.isRefusedInRoom(block)) continue;
            Reason reason = refusalFor(level, pos, block);
            if (reason == null) continue;

            event.setCanceled(true);
            refuse(event.getEntity(), pos, block, reason, "multi-place");
            return;
        }
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
    private static void refuse(Entity entity, BlockPos pos, Block block, Reason reason, String via) {
        LOGGER.info("[DungeonTrain] Portal: refused {} at {} ({}, {})",
            BuiltInRegistries.BLOCK.getKey(block), pos, via, reason);
        if (!(entity instanceof ServerPlayer player)) return;

        long now = player.serverLevel().getGameTime();
        Long told = LAST_TOLD.get(player.getUUID());
        if (told != null && now - told < REPEAT_COOLDOWN_TICKS) return;
        LAST_TOLD.put(player.getUUID(), now);

        player.sendSystemMessage(Component.translatable(reason.firstKey).withStyle(ChatFormatting.GRAY));
        PENDING.removeIf(p -> p.player().equals(player.getUUID()));
        PENDING.add(new Pending(player.getUUID(), now + SECOND_LINE_DELAY_TICKS, reason.secondKey));
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
                    Component.translatable(pending.key()).withStyle(ChatFormatting.GRAY));
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
