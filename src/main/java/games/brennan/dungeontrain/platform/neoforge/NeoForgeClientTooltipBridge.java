package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.event.DtEffectTooltipCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtGatherTooltipComponentsCallback;
import games.brennan.dungeontrain.platform.event.DtItemTooltipCallback;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.client.event.GatherEffectScreenTooltipsEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for the client tooltip events. Client-only
 * so it never loads on a dedicated server. Each handler mutates a live list on the
 * event; the bridge hands that live list through unchanged — pure passthrough.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientTooltipBridge {

    private NeoForgeClientTooltipBridge() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        for (DtItemTooltipCallback cb : DtEvents.ITEM_TOOLTIP.listeners()) {
            cb.onItemTooltip(event.getItemStack(), event.getToolTip());
        }
    }

    @SubscribeEvent
    public static void onEffectTooltip(GatherEffectScreenTooltipsEvent event) {
        for (DtEffectTooltipCallback cb : DtEvents.EFFECT_TOOLTIP.listeners()) {
            cb.onGatherEffectTooltips(event.getEffectInstance(), event.getTooltip());
        }
    }

    @SubscribeEvent
    public static void onGatherComponents(RenderTooltipEvent.GatherComponents event) {
        for (DtGatherTooltipComponentsCallback cb : DtEvents.GATHER_TOOLTIP_COMPONENTS.listeners()) {
            cb.onGatherComponents(event.getItemStack(), event.getTooltipElements());
        }
    }
}
