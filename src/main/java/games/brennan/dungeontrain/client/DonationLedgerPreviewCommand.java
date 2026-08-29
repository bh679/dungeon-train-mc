package games.brennan.dungeontrain.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.support.FundingGoals;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient.Entry;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient.Goal;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient.Summary;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.List;

/**
 * Dev-only preview of the engine-room ledger's funding states — {@code /dt-ledger-preview}.
 *
 * <p>The DONATE page's left grid re-orders as the ladder is climbed: the server bill leads until it
 * is paid, then the next goal leads with the bill ticked off below it, and once that goal is funded
 * too the hours tile leads with the goal ticked off below. Which state you see depends on figures
 * the <b>relay</b> serves, so the later two are unreachable in a dev client — there is no way to
 * look at them before they ship to players. This stuffs a synthetic summary into
 * {@link DonationSummaryCache} so each state can be opened, screenshotted and reviewed.</p>
 *
 * <ul>
 *   <li>{@code /dt-ledger-preview live}      — drop the fake; the next fetch fills the real one back in</li>
 *   <li>{@code /dt-ledger-preview costs-met} — running costs paid, the next goal part-funded</li>
 *   <li>{@code /dt-ledger-preview goal-met}  — both funded: the hours tile leads</li>
 * </ul>
 *
 * <p><b>Dev builds only.</b> Registered only when {@link DungeonTrain#isDevBuild()} — the same
 * branch-ref signal the dev title screen and version HUD use — so it cannot exist on a release jar
 * and cannot show a player invented donation figures.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DonationLedgerPreviewCommand {

    private DonationLedgerPreviewCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!DungeonTrain.isDevBuild()) return;
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dt-ledger-preview")
                .executes(ctx -> usage(ctx.getSource()))
                .then(Commands.literal("live").executes(ctx -> apply(ctx.getSource(), null, "live")))
                .then(Commands.literal("costs-met")
                        .executes(ctx -> apply(ctx.getSource(), costsMet(), "costs-met")))
                .then(Commands.literal("goal-met")
                        .executes(ctx -> apply(ctx.getSource(), goalMet(), "goal-met")));
        dispatcher.register(root);
    }

    private static int apply(CommandSourceStack source, Summary summary, String name) {
        DonationSummaryCache.set(summary);
        source.sendSuccess(() -> Component.literal("Ledger preview: " + name
                + (summary == null ? " (real summary restored on the next fetch)" : ""))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int usage(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("/dt-ledger-preview <live|costs-met|goal-met>")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** Running costs settled; the next rung part-funded — the state the page shows today. */
    private static Summary costsMet() {
        return summary(List.of(
                new Goal(FundingGoals.RUNNING_COSTS, "Running Costs", 120, 120, 100, true),
                new Goal("dev_tools", "Dev Tools", 200, 90, 45, false)), "dev_tools");
    }

    /** Both rungs funded — the hours tile leads and Dev Tools drops to the settled slot. */
    private static Summary goalMet() {
        return summary(List.of(
                new Goal(FundingGoals.RUNNING_COSTS, "Running Costs", 120, 120, 100, true),
                new Goal("dev_tools", "Dev Tools", 200, 210, 100, true)), "dev_tools");
    }

    private static Summary summary(List<Goal> goals, String activeGoalId) {
        List<Entry> board = List.of(
                new Entry("PreviewPatron", 25, "patreon"),
                new Entry("PreviewDonor", 10, "revolut"));
        return new DonationSummaryClient.Summary(
                210, 1450, 120, 175, 3, 45,
                board, board, false, 0, 0, goals, activeGoalId);
    }
}
