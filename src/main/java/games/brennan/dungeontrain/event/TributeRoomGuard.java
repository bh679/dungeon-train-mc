package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvBlocks;
import games.brennan.dungeontrain.portal.tribute.TributeRoomLiveEdits;
import games.brennan.dungeontrain.portal.tribute.TributeRoomRegistry;
import games.brennan.dungeontrain.portal.tribute.TributeRoomVisitSessions;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * What a tribute room lets you do to it, and where.
 *
 * <ul>
 *   <li><b>On the live train</b> (a pair that rolled somebody's room, {@link TributeRoomRegistry}): look
 *       only. Nobody breaks, places, pours, takes from a chest or knocks a frame down — except that its
 *       owner may place blocks into its empty space and take out again the ones they placed, never what
 *       was already there. What they build is saved to their room ({@link TributeRoomLiveEdits}).</li>
 *   <li><b>In a death visit</b> ({@link TributeRoomVisitSessions}): build freely, but the TV stays — it is
 *       the room's anchor, and a room without one cannot be saved.</li>
 *   <li><b>In the editor</b>: nothing here applies; the Portals plot is in neither set.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TributeRoomGuard {

    private TributeRoomGuard() {}

    /**
     * The rule, with the facts that decide it. Pure. A live room is look-only for everyone. A room on a
     * visit is the visitor's own to change, TV aside; anyone else in it (before the visitor arrives,
     * or wandering in) changes nothing, so only what its owner did after their death is ever saved.
     * {@code ownLiveBuild} is the one way into a live room: its owner placing into empty space, or
     * taking out a block they placed there.
     */
    static boolean denies(boolean inLiveTributeRoom, boolean inVisit, boolean isTv, boolean ownVisit, boolean ownLiveBuild) {
        return (inLiveTributeRoom && !ownLiveBuild) || (inVisit && (isTv || !ownVisit));
    }

    /** Whether the owner of a live room may make this change: placing needs empty space, breaking a block of their own. */
    static boolean ownLiveBuild(boolean isOwner, boolean placing, boolean intoAir, boolean placedByOwner) {
        return isOwner && (placing ? intoAir : placedByOwner);
    }

    /**
     * Blocks that change what was already there without breaking it — fire burns it, a hopper drains its
     * chests, a piston shoves it — and so are never the owner's to place in a live room.
     */
    static boolean reachesOriginals(BlockState placed) {
        return placed.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock
            || placed.getBlock() instanceof net.minecraft.world.level.block.HopperBlock
            || placed.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock;
    }

    private static boolean quiet() {
        return TributeRoomRegistry.isEmpty() && TributeRoomVisitSessions.isEmpty();
    }

    private static boolean live(ServerLevel level, BlockPos pos) {
        return TributeRoomRegistry.at(level.dimension(), pos) != null;
    }

    private static boolean visit(BlockPos pos) {
        return TributeRoomVisitSessions.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** Whether {@code pos} is inside {@code player}'s own visit. */
    private static boolean ownVisit(ServerPlayer player, BlockPos pos) {
        TributeRoomVisitSessions.Session own = TributeRoomVisitSessions.get(player.getUUID());
        return own != null && own.window().isInside(pos);
    }

    /** Someone else's visit room, or a room stood ahead of a visit: not this player's to touch. */
    private static boolean othersVisit(ServerPlayer player, BlockPos pos) {
        return visit(pos) && !ownVisit(player, pos);
    }

    /** A live room or another player's visit: look-only for {@code player}. */
    private static boolean lookOnly(ServerLevel level, ServerPlayer player, BlockPos pos) {
        return live(level, pos) || othersVisit(player, pos);
    }

    private static void refuse(Player player, BlockPos pos, boolean isTv) {
        ServerLevel level = (ServerLevel) player.level();
        TributeRoomRegistry.Standing standing = TributeRoomRegistry.at(level.dimension(), pos);
        String key = isTv && standing == null ? "chat.dungeontrain.tribute_room.tv_stays"
            : standing != null && player instanceof ServerPlayer sp && TributeRoomLiveEdits.isOwner(level, sp, standing)
                ? "chat.dungeontrain.tribute_room.own_placed_only"
                : "chat.dungeontrain.tribute_room.view_only";
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }

    /** Whether {@code player} owns the live room at {@code pos} and may build in it. */
    private static boolean owns(ServerLevel level, ServerPlayer player, TributeRoomRegistry.Standing standing) {
        return standing != null && TributeRoomLiveEdits.isOwner(level, player, standing);
    }

    /** A live room or another player's visit, for {@code placed} going into {@code replaced}: refused? */
    private static boolean placeDenied(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState replaced, BlockState placed) {
        TributeRoomRegistry.Standing standing = TributeRoomRegistry.at(level.dimension(), pos);
        boolean own = ownLiveBuild(owns(level, player, standing), true, replaced.isAir() && !reachesOriginals(placed), false);
        // Placing has no "own visit" case to spare: a visit room is only ever someone else's to refuse here.
        return denies(standing != null, othersVisit(player, pos), false, false, own);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        boolean tv = TvBlocks.isTelevision(event.getState());
        TributeRoomRegistry.Standing standing = TributeRoomRegistry.at(level.dimension(), pos);
        boolean own = standing != null && ownLiveBuild(owns(level, player, standing), false, false,
            TributeRoomLiveEdits.placedByOwner(standing, pos));
        if (!denies(standing != null, visit(pos), tv, ownVisit(player, pos), own)) return;
        event.setCanceled(true);
        refuse(player, pos, tv);
    }

    /** The owner's own block came out of their live room: saved, and no longer theirs to take. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBroken(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || TributeRoomRegistry.isEmpty() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getPlayer() instanceof ServerPlayer player)) return;
        TributeRoomRegistry.Standing standing = TributeRoomRegistry.at(level.dimension(), event.getPos());
        if (!owns(level, player, standing)) return;
        TributeRoomLiveEdits.recordBroken(player, standing, event.getPos());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event instanceof BlockEvent.EntityMultiPlaceEvent) return; // judged whole, below
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!placeDenied(level, player, event.getPos(), event.getBlockSnapshot().getState(), event.getPlacedBlock())) return;
        event.setCanceled(true);
        refuse(player, event.getPos(), false);
    }

    /** A door or bed: every cell it fills must be one the player may place into. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        for (net.neoforged.neoforge.common.util.BlockSnapshot cell : event.getReplacedBlockSnapshots()) {
            if (!placeDenied(level, player, cell.getPos(), cell.getState(), level.getBlockState(cell.getPos()))) continue;
            event.setCanceled(true);
            refuse(player, cell.getPos(), false);
            return;
        }
    }

    /** What the owner placed in their live room, once nothing has refused it: theirs to take out, and saved. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || TributeRoomRegistry.isEmpty() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)) return;
        java.util.List<net.neoforged.neoforge.common.util.BlockSnapshot> cells = event instanceof BlockEvent.EntityMultiPlaceEvent multi
            ? multi.getReplacedBlockSnapshots() : java.util.List.of(event.getBlockSnapshot());
        for (net.neoforged.neoforge.common.util.BlockSnapshot cell : cells) {
            TributeRoomRegistry.Standing standing = TributeRoomRegistry.at(level.dimension(), cell.getPos());
            if (owns(level, player, standing)) TributeRoomLiveEdits.recordPlaced(player, standing, cell.getPos());
        }
    }

    /** Opening a chest, barrel, furnace, hopper — any block that holds items — is taking from it. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        if (!lookOnly(level, player, pos)) return;
        BlockState state = level.getBlockState(pos);
        boolean container = state.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container;
        if (!container && !TvBlocks.isTelevision(state)) return;
        event.setCanceled(true);
        refuse(player, pos, false);
    }

    /** Pouring a bucket into the room: the block it would land in is in the room. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBucket(PlayerInteractEvent.RightClickItem event) {
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getItemStack().getItem() instanceof BucketItem)) return;
        HitResult hit = player.pick(5.0, 0f, true);
        if (!(hit instanceof BlockHitResult block) || hit.getType() == HitResult.Type.MISS) return;
        BlockPos target = block.getBlockPos().relative(block.getDirection());
        if (!lookOnly(level, player, target) && !lookOnly(level, player, block.getBlockPos())) return;
        event.setCanceled(true);
        refuse(player, target, false);
    }

    /** Frames, paintings and stands are part of the room too. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!decor(event.getTarget()) || !lookOnly(level, player, event.getTarget().blockPosition())) return;
        event.setCanceled(true);
        refuse(player, event.getTarget().blockPosition(), false);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttackEntity(AttackEntityEvent event) {
        if (quiet() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!decor(event.getTarget()) || !lookOnly(player.serverLevel(), player, event.getTarget().blockPosition())) return;
        event.setCanceled(true);
        refuse(player, event.getTarget().blockPosition(), false);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (TributeRoomRegistry.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        event.getAffectedBlocks().removeIf(pos -> live(level, pos));
    }

    private static boolean decor(Entity entity) {
        return entity instanceof HangingEntity || entity instanceof ArmorStand;
    }
}
