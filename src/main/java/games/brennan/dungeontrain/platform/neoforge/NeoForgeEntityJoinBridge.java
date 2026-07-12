package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtEntityJoinCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtMobEffectRemoveCallback;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for DT's two cancellable event
 * categories: {@code EntityJoinLevelEvent} and {@code MobEffectEvent.Remove}.
 * Auto-registered via {@link EventBusSubscriber}. Exact semantic passthrough.
 *
 * <p>Both events are cancellable and no DT handler used {@code receiveCanceled},
 * so the bridge iterates DT callbacks in registration order and stops at the FIRST
 * that returns {@code true}, mapping it to {@code event.setCanceled(true)} — the
 * same short-circuit-on-first-cancel the NeoForge bus produced for
 * non-{@code receiveCanceled} listeners. The bridge itself is a plain (non-
 * {@code receiveCanceled}) listener, so if a higher-priority other-mod listener
 * cancels first, NeoForge never calls the bridge — matching today.</p>
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class NeoForgeEntityJoinBridge {

    private NeoForgeEntityJoinBridge() {}

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        for (DtEntityJoinCallback cb : DtEvents.ENTITY_JOIN.listeners()) {
            if (cb.onEntityJoin(event.getEntity(), event.getLevel(), event.loadedFromDisk())) {
                event.setCanceled(true);
                return; // first cancel wins — no DT handler uses receiveCanceled
            }
        }
    }

    @SubscribeEvent
    public static void onMobEffectRemove(MobEffectEvent.Remove event) {
        for (DtMobEffectRemoveCallback cb : DtEvents.MOB_EFFECT_REMOVE.listeners()) {
            if (cb.onEffectRemove(event.getEntity(), event.getEffect())) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
