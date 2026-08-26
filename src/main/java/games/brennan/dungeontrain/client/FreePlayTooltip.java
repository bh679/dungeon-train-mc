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

import java.util.List;

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

    /** What vanilla renders as an infinite effect's duration ({@code MobEffectUtil.formatDuration}). */
    private static final String INFINITE_DURATION = "\u221e";

    private FreePlayTooltip() {}

    @SubscribeEvent
    public static void onGatherEffectTooltips(GatherEffectScreenTooltipsEvent event) {
        if (!event.getEffectInstance().getEffect().is(ModMobEffects.FREE_PLAY.getId())) return;
        foldDurationIntoTitle(event.getTooltip());
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

    /**
     * Vanilla gives an effect two header lines — the name, then the duration on its own row. For an
     * effect that lasts the whole run that second row is one glyph, and spending a line on it pushes
     * the reason further from the name it explains. Folded onto the title row instead: {@code Free
     * Play ∞}.
     *
     * <p>Recognises the row by that glyph rather than by its position, so if a future version (or
     * another mod's tooltip event) puts something else second, the tooltip is left alone rather than
     * silently swallowing a line.</p>
     */
    private static void foldDurationIntoTitle(List<Component> tooltip) {
        if (tooltip.size() < 2 || !INFINITE_DURATION.equals(tooltip.get(1).getString().trim())) return;
        tooltip.set(0, tooltip.get(0).copy()
            .append(CommonComponents.SPACE)
            .append(tooltip.get(1)));
        tooltip.remove(1);
    }
}
