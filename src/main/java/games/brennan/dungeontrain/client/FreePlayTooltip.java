package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity.FreePlayCause;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
 * (the inventory). Mirrors {@link WarmthOfTheFireTooltip}; the two consequence lines load from the
 * {@code .desc.1} / {@code .desc.3} lang keys ({@code .desc.2} folded into the first and is no
 * longer rendered — translations still carry its longer wording until the next wave).
 *
 * <p>The trigger lines come from {@link FreePlayCauseClient} — the server is the only side that
 * knows why, and the chat notice that says so is shown once and then scrolls away. This is where a
 * player who logs into a tainted world days later can still find out.</p>
 *
 * <p>It sits <b>behind Shift</b> (the vanilla convention), appended to the end of the last line
 * rather than on a line of its own. "Why" is looked up once, when a player is surprised by the
 * badge; the consequences are what everyone reads every time — so the resting tooltip stays two
 * lines, ending in a dim hint, and Shift swaps that hint for the answer.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class FreePlayTooltip {

    private FreePlayTooltip() {}

    @SubscribeEvent
    public static void onGatherEffectTooltips(GatherEffectScreenTooltipsEvent event) {
        if (!event.getEffectInstance().getEffect().is(ModMobEffects.FREE_PLAY.getId())) return;
        event.getTooltip().add(
            Component.translatable("effect.dungeontrain.free_play.desc.1").withStyle(ChatFormatting.GRAY));

        // The trigger rides the end of the last line rather than opening a line of its own — two
        // lines is the whole budget here.
        MutableComponent lastLine =
            Component.translatable("effect.dungeontrain.free_play.desc.3").withStyle(ChatFormatting.GRAY);
        List<FreePlayCause> causes = FreePlayCauseClient.causes();
        if (causes.isEmpty()) {
            // The server hasn't said why (a pre-update server, or a badge that has outlived its
            // cause) — no hint either, since Shift would then reveal nothing.
            event.getTooltip().add(lastLine);
            return;
        }
        if (!Screen.hasShiftDown()) {
            event.getTooltip().add(lastLine
                .append(CommonComponents.SPACE)
                .append(Component.translatable("effect.dungeontrain.free_play.trigger.hint")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)));
            return;
        }
        event.getTooltip().add(lastLine
            .append(CommonComponents.SPACE)
            .append(triggerLine(causes.get(0))));
        // A second cause is real but rare — a creative switch made inside an already-tainted
        // session has two answers. Those get their own lines rather than a run-on first one.
        for (int i = 1; i < causes.size(); i++) {
            event.getTooltip().add(triggerLine(causes.get(i)));
        }
        for (FreePlayCause cause : causes) {
            if (cause.detail() != null) {
                event.getTooltip().add(cause.detail().copy().withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }

    private static MutableComponent triggerLine(FreePlayCause cause) {
        return Component.translatable("effect.dungeontrain.free_play.trigger", cause.cause())
            .withStyle(ChatFormatting.DARK_GRAY);
    }
}
