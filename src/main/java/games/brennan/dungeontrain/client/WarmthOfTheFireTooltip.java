package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Appends a "Slow Healing" subtitle to the
 * {@link games.brennan.dungeontrain.registry.effect.WarmthOfTheFireEffect}
 * inventory tooltip — the line is loaded from the {@code .description} lang
 * key so it picks up translations.
 */
public final class WarmthOfTheFireTooltip {

    private WarmthOfTheFireTooltip() {}

    public static void onGatherEffectTooltips(net.minecraft.world.effect.MobEffectInstance effect, java.util.List<net.minecraft.network.chat.Component> tooltip) {
        if (!effect.getEffect().is(ModMobEffects.WARMTH_OF_THE_FIRE.getId())) return;
        tooltip.add(
            Component.translatable("effect.dungeontrain.warmth_of_the_fire.description")
                .withStyle(ChatFormatting.GRAY));
    }
}
