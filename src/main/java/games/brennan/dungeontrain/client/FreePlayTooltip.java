package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity.FreePlayCause;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.GatherEffectScreenTooltipsEvent;

import java.util.List;

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
 *
 * <p>They sit <b>behind Shift</b> (the vanilla convention). "Why" is looked up once, when a player
 * is surprised by the badge; the three consequence lines are what everyone reads every time. Adding
 * the trigger unconditionally made a short tooltip tall for the ordinary case, so the resting
 * tooltip is exactly what it always was plus a one-line hint, and Shift swaps that hint for the
 * answer.</p>
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
        // Nothing further when the server hasn't said why (a pre-update server, or a badge that has
        // outlived its cause) — not even the hint, since Shift would then reveal nothing.
        List<FreePlayCause> causes = FreePlayCauseClient.causes();
        if (causes.isEmpty()) return;
        if (!Screen.hasShiftDown()) {
            event.getTooltip().add(
                Component.translatable("effect.dungeontrain.free_play.trigger.hint")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            return;
        }
        for (FreePlayCause cause : causes) {
            event.getTooltip().add(
                Component.translatable("effect.dungeontrain.free_play.trigger", cause.cause())
                    .withStyle(ChatFormatting.GRAY));
            if (cause.detail() != null) {
                event.getTooltip().add(cause.detail().copy().withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }
}
