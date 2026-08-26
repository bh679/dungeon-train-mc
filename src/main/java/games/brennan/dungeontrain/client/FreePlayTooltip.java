package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity.FreePlayCause;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.GatherEffectScreenTooltipsEvent;

/**
 * Fills in the {@code Free Play} effect's inventory hover tooltip: what turned it on, then what it
 * costs. In-world the mouse is captured so the top-right HUD icon can't be hovered; this is the
 * surface where Minecraft natively supports hovering an effect icon (the inventory). Mirrors
 * {@link WarmthOfTheFireTooltip}.
 *
 * <p>The trigger comes from {@link FreePlayCauseClient} — the server is the only side that knows
 * why, and the chat notice that says so is shown once and then scrolls away. This is where a player
 * who logs into a tainted world days later can still find out, so it leads: the badge's surprise is
 * "why is this on?", not "what does it cost?".</p>
 *
 * <p>Both halves are one line each, which is what lets the trigger show unconditionally — the
 * consequences fold into a single {@code .desc.1} line ({@code .desc.2} / {@code .desc.3} are no
 * longer rendered; translations still carry their older, longer wording until the next wave).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class FreePlayTooltip {

    private FreePlayTooltip() {}

    @SubscribeEvent
    public static void onGatherEffectTooltips(GatherEffectScreenTooltipsEvent event) {
        if (!event.getEffectInstance().getEffect().is(ModMobEffects.FREE_PLAY.getId())) return;
        // Nothing to lead with when the server hasn't said why — a pre-update server, or a badge
        // that has outlived its cause. The consequence line below still stands on its own.
        for (FreePlayCause cause : FreePlayCauseClient.causes()) {
            MutableComponent line = cause.cause().copy().withStyle(ChatFormatting.GRAY);
            if (cause.detail() != null) {
                line.append(CommonComponents.SPACE)
                    .append(cause.detail().copy().withStyle(ChatFormatting.DARK_GRAY));
            }
            event.getTooltip().add(line);
        }
        event.getTooltip().add(
            Component.translatable("effect.dungeontrain.free_play.desc.1").withStyle(ChatFormatting.GRAY));
    }
}
