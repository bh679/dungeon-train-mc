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
 * Appends the consequence lines to the {@code Free Play} effect's inventory
 * hover tooltip — explaining <em>why</em> the run-scoped effect is there.
 * In-world the mouse is captured so the top-right HUD icon can't be hovered;
 * this is the surface where Minecraft natively supports hovering an effect icon
 * (the inventory). Mirrors {@link WarmthOfTheFireTooltip}; lines load from the
 * {@code .desc.*} lang keys.
 */
public final class FreePlayTooltip {

    private FreePlayTooltip() {}

    public static void onGatherEffectTooltips(net.minecraft.world.effect.MobEffectInstance effect, java.util.List<net.minecraft.network.chat.Component> tooltip) {
        if (!effect.getEffect().is(ModMobEffects.FREE_PLAY.getId())) return;
        tooltip.add(
            Component.translatable("effect.dungeontrain.free_play.desc.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(
            Component.translatable("effect.dungeontrain.free_play.desc.2").withStyle(ChatFormatting.GRAY));
        tooltip.add(
            Component.translatable("effect.dungeontrain.free_play.desc.3").withStyle(ChatFormatting.GRAY));
    }
}
