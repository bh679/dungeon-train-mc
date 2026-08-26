package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity.FreePlayCause;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.GatherEffectScreenTooltipsEvent;

/**
 * Appends the consequence lines <em>and the trigger</em> to the {@code Free Play} effect's
 * inventory hover tooltip — explaining what the effect costs, and what turned it on.
 * In-world the mouse is captured so the top-right HUD icon can't be hovered;
 * this is the surface where Minecraft natively supports hovering an effect icon
 * (the inventory). Mirrors {@link WarmthOfTheFireTooltip}; the consequence lines load from the
 * {@code .desc.*} lang keys.
 *
 * <p>The trigger lines come from {@link FreePlayCauseClient} — the server is the only side that
 * knows why, and the chat notice that says so is shown once and then scrolls away. This is where a
 * player who logs into a tainted world days later can still find out.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class FreePlayTooltip {

    private FreePlayTooltip() {}

    @SubscribeEvent
    public static void onGatherEffectTooltips(GatherEffectScreenTooltipsEvent event) {
        if (!event.getEffectInstance().getEffect().is(ModMobEffects.FREE_PLAY.getId())) return;
        event.getTooltip().add(
            Component.translatable("effect.dungeontrain.free_play.desc.1").withStyle(ChatFormatting.GRAY));
        event.getTooltip().add(
            Component.translatable("effect.dungeontrain.free_play.desc.2").withStyle(ChatFormatting.GRAY));
        event.getTooltip().add(
            Component.translatable("effect.dungeontrain.free_play.desc.3").withStyle(ChatFormatting.GRAY));
        // Nothing to add when the server hasn't said why (a pre-update server, or a badge that has
        // outlived its cause) — the tooltip then reads exactly as it did before.
        for (FreePlayCause cause : FreePlayCauseClient.causes()) {
            event.getTooltip().add(
                Component.translatable("effect.dungeontrain.free_play.trigger", cause.cause())
                    .withStyle(ChatFormatting.GRAY));
            if (cause.detail() != null) {
                event.getTooltip().add(cause.detail().copy().withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }
}
