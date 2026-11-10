package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.GatherEffectScreenTooltipsEvent;

import java.util.List;

/**
 * Hover tooltip for the {@link UntimedEffectClient#isUntimed untimed} effects (compact effect layout
 * only — vanilla shows none in the wide one): drops vanilla's duration line, which for an effect that
 * lasts exactly as long as its condition would only read "∞", and adds the effect's
 * {@code .description} lang line.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class UntimedEffectTooltip {

    private UntimedEffectTooltip() {}

    @SubscribeEvent
    public static void onGatherEffectTooltips(GatherEffectScreenTooltipsEvent event) {
        if (!UntimedEffectClient.isUntimed(event.getEffectInstance().getEffect())) return;
        List<Component> tooltip = event.getTooltip();
        if (tooltip.size() > 1) tooltip.remove(1); // vanilla's duration line, after the name
        tooltip.add(
            Component.translatable(event.getEffectInstance().getDescriptionId() + ".description")
                .withStyle(ChatFormatting.GRAY));
    }
}
