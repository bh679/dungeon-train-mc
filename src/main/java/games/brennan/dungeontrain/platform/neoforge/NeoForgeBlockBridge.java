package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtAttackEntityCallback;
import games.brennan.dungeontrain.platform.event.DtBlockBreakCallback;
import games.brennan.dungeontrain.platform.event.DtBlockDropsCallback;
import games.brennan.dungeontrain.platform.event.DtBlockPlaceCallback;
import games.brennan.dungeontrain.platform.event.DtEntityLeaveCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for block break/place/drops, entity
 * leave and attack-entity events. Auto-registered via {@link EventBusSubscriber}.
 * Exact semantic passthrough — no logic. All handlers were NORMAL priority and
 * none cancel, so each event is subscribed once and fires {@code listeners()} in
 * registration order.
 *
 * <p>NeoForge's {@code BlockEvent.EntityMultiPlaceEvent} is deliberately NOT
 * bridged: its {@code getReplacedBlockSnapshots()} returns a loader-specific
 * {@code BlockSnapshot} that cannot be represented in a {@code :common} callback,
 * so {@code EditorMirrorLiveHandler.onMultiBlockPlace} stays a NeoForge
 * {@code @SubscribeEvent} on that class.</p>
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class NeoForgeBlockBridge {

    private NeoForgeBlockBridge() {}

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        for (DtBlockBreakCallback cb : DtEvents.BLOCK_BREAK.listeners()) {
            cb.onBlockBreak(event.getLevel(), event.getPlayer(), event.getPos(),
                event.getState(), event.isCanceled());
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        for (DtBlockPlaceCallback cb : DtEvents.BLOCK_PLACE.listeners()) {
            cb.onBlockPlace(event.getEntity(), event.getLevel(), event.getPlacedBlock(),
                event.getPos(), event.isCanceled());
        }
    }

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        for (DtBlockDropsCallback cb : DtEvents.BLOCK_DROPS.listeners()) {
            cb.onBlockDrops(event.getLevel(), event.getPos(), event.getState(),
                event.getBreaker(), event.getDrops());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        for (DtEntityLeaveCallback cb : DtEvents.ENTITY_LEAVE.listeners()) {
            cb.onEntityLeave(event.getEntity(), event.getLevel());
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        for (DtAttackEntityCallback cb : DtEvents.ATTACK_ENTITY.listeners()) {
            cb.onAttackEntity(event.getEntity(), event.getTarget(), event.isCanceled());
        }
    }
}
