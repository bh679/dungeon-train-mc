package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.GatherEffectScreenTooltipsEvent;

/**
 * Appends a "Slow Healing" subtitle to the
 * {@link games.brennan.dungeontrain.registry.effect.WarmthOfTheFireEffect}
 * inventory tooltip — the line is loaded from the {@code .description} lang
 * key so it picks up translations.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class WarmthOfTheFireTooltip {

    private WarmthOfTheFireTooltip() {}

    @SubscribeEvent
    public static void onGatherEffectTooltips(GatherEffectScreenTooltipsEvent event) {
        if (!event.getEffectInstance().getEffect().is(ModMobEffects.WARMTH_OF_THE_FIRE.getId())) return;
        event.getTooltip().add(
            Component.translatable("effect.dungeontrain.warmth_of_the_fire.description")
                .withStyle(ChatFormatting.GRAY));
    }
}
