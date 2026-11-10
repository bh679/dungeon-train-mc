package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.GatherEffectScreenTooltipsEvent;

import java.util.List;

/**
 * The {@link games.brennan.dungeontrain.registry.effect.DriftingEffect} hover tooltip (compact effect
 * layout only — vanilla shows none in the wide one): drops vanilla's duration line, which for an
 * effect that lasts as long as the player stays inside would only read "∞", and adds the
 * {@code .description} line, a lang key so it picks up translations.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DriftingTooltip {

    private DriftingTooltip() {}

    @SubscribeEvent
    public static void onGatherEffectTooltips(GatherEffectScreenTooltipsEvent event) {
        if (!event.getEffectInstance().getEffect().is(ModMobEffects.DRIFTING.getId())) return;
        List<Component> tooltip = event.getTooltip();
        if (tooltip.size() > 1) tooltip.remove(1); // vanilla's duration line, after the name
        tooltip.add(
            Component.translatable("effect.dungeontrain.drifting.description")
                .withStyle(ChatFormatting.GRAY));
    }
}
