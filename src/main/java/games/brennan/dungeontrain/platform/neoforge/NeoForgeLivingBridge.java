package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtFinalizeSpawnCallback;
import games.brennan.dungeontrain.platform.event.DtLivingDamageCallback;
import games.brennan.dungeontrain.platform.event.DtLivingDeathCallback;
import games.brennan.dungeontrain.platform.event.DtLivingEquipmentChangeCallback;
import games.brennan.dungeontrain.platform.event.DtPriority;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for the living-entity events (death,
 * post-damage, equipment change). Auto-registered via {@link EventBusSubscriber}.
 * Exact semantic passthrough — no logic.
 *
 * <p><b>Death priority/cancellation:</b> DT's death handlers span NORMAL and LOW,
 * none using {@code receiveCanceled}. The bridge subscribes death at both tiers
 * without {@code receiveCanceled}, so if another mod cancels at a higher priority
 * NeoForge never calls the bridge (matching the old direct listeners); the
 * {@code isCanceled()} passed to callbacks is therefore always false when they
 * run — faithfully carried so the handler's defensive check is preserved. No DT
 * handler cancels death, so the bridge never sets the event cancelled. Damage
 * (Post) and equipment change were single-tier NORMAL read-only events.</p>
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class NeoForgeLivingBridge {

    private NeoForgeLivingBridge() {}

    // ---- LivingDeathEvent (NORMAL, LOW) -----------------------------------

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onLivingDeathNormal(LivingDeathEvent event) {
        for (DtLivingDeathCallback cb : DtEvents.LIVING_DEATH.listeners(DtPriority.NORMAL)) {
            cb.onDeath(event.getEntity(), event.getSource(), event.isCanceled());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingDeathLow(LivingDeathEvent event) {
        for (DtLivingDeathCallback cb : DtEvents.LIVING_DEATH.listeners(DtPriority.LOW)) {
            cb.onDeath(event.getEntity(), event.getSource(), event.isCanceled());
        }
    }

    // ---- LivingDamageEvent.Post (NORMAL) ----------------------------------

    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        for (DtLivingDamageCallback cb : DtEvents.LIVING_DAMAGE.listeners()) {
            cb.onLivingDamage(event.getEntity(), event.getSource(), event.getNewDamage());
        }
    }

    // ---- LivingEquipmentChangeEvent (NORMAL) ------------------------------

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        for (DtLivingEquipmentChangeCallback cb : DtEvents.LIVING_EQUIPMENT_CHANGE.listeners()) {
            cb.onEquipmentChange(event.getEntity(), event.getSlot(), event.getTo());
        }
    }

    // ---- FinalizeSpawnEvent (NORMAL) --------------------------------------
    // setSpawnCancelled is a result flag, not a propagation-stopping cancel: every
    // listener runs, so the bridge invokes all callbacks and passes each a sink
    // that maps a cancel request to event.setSpawnCancelled(true).

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        for (DtFinalizeSpawnCallback cb : DtEvents.FINALIZE_SPAWN.listeners()) {
            cb.onFinalizeSpawn(event.getEntity(), event.getLevel(), event.getSpawnType(),
                event.getX(), () -> event.setSpawnCancelled(true));
        }
    }
}
