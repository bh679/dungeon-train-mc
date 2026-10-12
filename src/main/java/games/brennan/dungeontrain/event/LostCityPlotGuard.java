package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.building.LostCityReferences;
import games.brennan.dungeontrain.editor.LostCityReferenceEditor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Keeps the official Lost City buildings view-only in the editor ({@link LostCityReferenceEditor}).
 *
 * <p>Big Lost City is All Rights Reserved: DT may place its buildings, not let anyone change or take them. So
 * inside a Lost City plot no block is broken or placed — by anyone, operators included — no bucket is emptied
 * or filled, and an explosion leaves it standing. The editor's save, copy and upload paths refuse a Lost City
 * template on their own; this is the in-world half.</p>
 *
 * <p>Also owns the reference list's lifetime: read from the registries once the server is up.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LostCityPlotGuard {

    private LostCityPlotGuard() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        LostCityReferences.reload(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LostCityReferences.clear();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (guarded(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
            tell(event.getPlayer());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (guarded(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
            tell(event.getEntity());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            if (guarded(event.getLevel(), snapshot.getPos())) {
                event.setCanceled(true);
                tell(event.getEntity());
                return;
            }
        }
    }

    /** A bucket raycasts from {@code use}, never places through a block event — so refuse it by where it aims. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBucket(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getItemStack().getItem() instanceof net.minecraft.world.item.BucketItem)) return;
        HitResult target = event.getEntity().pick(event.getEntity().blockInteractionRange(), 1.0F, true);
        if (!(target instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        if (guarded(event.getLevel(), hit.getBlockPos()) || guarded(event.getLevel(), hit.getBlockPos().relative(hit.getDirection()))) {
            event.setCanceled(true);
            tell(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        event.getAffectedBlocks().removeIf(pos -> guarded(level, pos));
    }

    /** Whether {@code pos} in {@code level} is inside an official building's plot while its row stands. */
    static boolean guarded(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || server.dimension() != Level.OVERWORLD) return false;
        if (LostCityReferences.all().isEmpty()) return false;
        return LostCityReferenceEditor.plotContaining(pos).isPresent();
    }

    private static void tell(Entity who) {
        if (who instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("chat.dungeontrain.editor.lost_city_view_only")
                .withStyle(ChatFormatting.YELLOW), true);
        }
    }
}
