package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvBlocks;
import games.brennan.dungeontrain.portal.home.HomeRoomRegistry;
import games.brennan.dungeontrain.portal.home.HomeRoomVisitSessions;
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
 * What a home room lets you do to it, and where.
 *
 * <ul>
 *   <li><b>On the live train</b> (a pair that rolled somebody's room, {@link HomeRoomRegistry}): look
 *       only. Nobody breaks, places, pours, takes from a chest or knocks a frame down — the owner
 *       included; it is their save, and only a death visit writes to it.</li>
 *   <li><b>In a death visit</b> ({@link HomeRoomVisitSessions}): build freely, but the TV stays — it is
 *       the room's anchor, and a room without one cannot be saved.</li>
 *   <li><b>In the editor</b>: nothing here applies; the Portals plot is in neither set.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class HomeRoomGuard {

    private HomeRoomGuard() {}

    /** The rule, with the two facts that decide it. Pure. */
    static boolean denies(boolean inLiveHomeRoom, boolean inVisit, boolean isTv) {
        return inLiveHomeRoom || (inVisit && isTv);
    }

    private static boolean quiet() {
        return HomeRoomRegistry.isEmpty() && HomeRoomVisitSessions.isEmpty();
    }

    private static boolean live(ServerLevel level, BlockPos pos) {
        return HomeRoomRegistry.at(level.dimension(), pos) != null;
    }

    private static boolean visit(BlockPos pos) {
        return HomeRoomVisitSessions.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static void refuse(Player player, BlockPos pos, boolean isTv) {
        String key = isTv && !live((ServerLevel) player.level(), pos) ? "chat.dungeontrain.home_room.tv_stays" : "chat.dungeontrain.home_room.view_only";
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        boolean tv = TvBlocks.isTelevision(event.getState());
        if (!denies(live(level, pos), visit(pos), tv)) return;
        event.setCanceled(true);
        refuse(player, pos, tv);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!live(level, event.getPos())) return;
        event.setCanceled(true);
        refuse(player, event.getPos(), false);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!live(level, event.getPos())) return;
        event.setCanceled(true);
        refuse(player, event.getPos(), false);
    }

    /** Opening a chest, barrel, furnace, hopper — any block that holds items — is taking from it. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        if (!live(level, pos)) return;
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
        if (!live(level, target) && !live(level, block.getBlockPos())) return;
        event.setCanceled(true);
        refuse(player, target, false);
    }

    /** Frames, paintings and stands are part of the room too. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (quiet() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!decor(event.getTarget()) || !live(level, event.getTarget().blockPosition())) return;
        event.setCanceled(true);
        refuse(player, event.getTarget().blockPosition(), false);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttackEntity(AttackEntityEvent event) {
        if (quiet() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!decor(event.getTarget()) || !live(player.serverLevel(), event.getTarget().blockPosition())) return;
        event.setCanceled(true);
        refuse(player, event.getTarget().blockPosition(), false);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (HomeRoomRegistry.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        event.getAffectedBlocks().removeIf(pos -> live(level, pos));
    }

    private static boolean decor(Entity entity) {
        return entity instanceof HangingEntity || entity instanceof ArmorStand;
    }
}
