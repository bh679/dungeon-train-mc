package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtInteractionInput;
import games.brennan.dungeontrain.platform.event.DtInteractionInputCallback;
import games.brennan.dungeontrain.platform.event.DtLeftClickBlockCallback;
import games.brennan.dungeontrain.platform.event.DtMouseButtonCallback;
import games.brennan.dungeontrain.platform.event.DtPriority;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for the cancellable client input events
 * ({@code MouseButton.Pre}, {@code InteractionKeyMappingTriggered}, and the
 * client-side {@code LeftClickBlock}). Client-only so it never loads on a dedicated
 * server. Cancellation semantics are replicated exactly — pure passthrough.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientInputBridge {

    private NeoForgeClientInputBridge() {}

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        for (DtMouseButtonCallback cb : DtEvents.MOUSE_BUTTON_PRE.listeners()) {
            if (cb.onMouseButton(event.getButton(), event.getAction(), event.getModifiers())) {
                event.setCanceled(true);
                return; // first cancel wins — matches NeoForge cancellable-event short-circuit
            }
        }
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        DtInteractionInput input = new DtInteractionInput() {
            @Override public void setCanceled(boolean canceled) { event.setCanceled(canceled); }
            @Override public void setSwingHand(boolean swingHand) { event.setSwingHand(swingHand); }
        };
        for (DtInteractionInputCallback cb : DtEvents.INTERACTION_KEY.listeners()) {
            // Replicate NeoForge's dispatch: once canceled, non-receiveCanceled listeners skip.
            if (event.isCanceled()) {
                return;
            }
            cb.onInteraction(input);
        }
    }

    // LeftClickBlock — two tiers. A HIGHEST cancel skips the NORMAL observer because the
    // NORMAL subscription does not receiveCanceled (NeoForge default), exactly as on the bus.

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlockHighest(PlayerInteractEvent.LeftClickBlock event) {
        for (DtLeftClickBlockCallback cb : DtEvents.LEFT_CLICK_BLOCK.listeners(DtPriority.HIGHEST)) {
            if (cb.onLeftClickBlock(event.getLevel(), event.getPos())) {
                event.setCanceled(true);
                return;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onLeftClickBlockNormal(PlayerInteractEvent.LeftClickBlock event) {
        for (DtLeftClickBlockCallback cb : DtEvents.LEFT_CLICK_BLOCK.listeners(DtPriority.NORMAL)) {
            if (cb.onLeftClickBlock(event.getLevel(), event.getPos())) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
