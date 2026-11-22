package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import games.brennan.dungeontrain.template.dateperiod.DatePeriod;
import games.brennan.dungeontrain.template.dateperiod.DatePeriods;
import games.brennan.dungeontrain.template.dateperiod.DateToday;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Dev commands for seasonal templates, under {@code /dungeontrain debug}:
 * <ul>
 *   <li>{@code date <yyyy-mm-dd>} gates on that date from now on (templates already planned keep
 *       theirs; new carriages and re-planned rooms follow it). {@code date clear} goes back to the
 *       real date.</li>
 *   <li>{@code date-periods} lists the periods on today and the next window of every period.</li>
 * </ul>
 * Dev-only text, so plain literals rather than lang keys, like the rest of {@code debug}.
 */
final class DatePeriodDebugCommand {

    private DatePeriodDebugCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> date() {
        return Commands.literal("date")
            .then(Commands.literal("clear").executes(c -> {
                DateToday.setOverride(null);
                return report(c.getSource());
            }))
            .then(Commands.argument("day", StringArgumentType.word()).executes(c -> {
                String raw = StringArgumentType.getString(c, "day");
                LocalDate day;
                try {
                    day = LocalDate.parse(raw);
                } catch (DateTimeParseException e) {
                    c.getSource().sendFailure(Component.literal("Not a date (use yyyy-mm-dd): " + raw));
                    return 0;
                }
                DateToday.setOverride(day);
                return report(c.getSource());
            }));
    }

    static LiteralArgumentBuilder<CommandSourceStack> list() {
        return Commands.literal("date-periods").executes(c -> {
            CommandSourceStack src = c.getSource();
            report(src);
            LocalDate today = DateToday.today() == null ? LocalDate.now() : DateToday.today();
            for (DatePeriod p : DatePeriods.all()) {
                Optional<DatePeriod.Window> w = p.nextFrom(today);
                String when = w.map(x -> x.from() + " to " + x.to()).orElse("no date in the table");
                boolean on = DateToday.activePeriods().contains(p.id());
                src.sendSuccess(() -> Component.literal((on ? "* " : "  ") + p.id() + ": " + when)
                    .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
            }
            reportSeasonalRooms(src);
            return DatePeriods.all().size();
        });
    }

    /**
     * Every dated portal room with its share of the live room draw at level 0 in the overworld, as
     * {@code PortalCarriageBuilder} would draw it. 0% outside its periods is the gate working.
     */
    private static void reportSeasonalRooms(CommandSourceStack src) {
        var kind = games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM;
        var ctx = new games.brennan.dungeontrain.template.GateContext(0,
            games.brennan.dungeontrain.worldgen.TrainPhase.OVERWORLD);
        java.util.Map<String, Double> odds = games.brennan.dungeontrain.track.variant.TrackVariantRegistry.leafOdds(kind, ctx);
        for (String name : games.brennan.dungeontrain.track.variant.TrackVariantRegistry.namesFor(kind)) {
            var gate = games.brennan.dungeontrain.track.variant.TrackVariantWeights.gateFor(kind, name);
            if (!gate.isSeasonal()) continue;
            double share = odds.getOrDefault(name, 0.0) * 100.0;
            String line = String.format(java.util.Locale.ROOT, "room %s %s: %.2f%% of the room draw",
                name, gate.periods(), share);
            src.sendSuccess(() -> Component.literal(line)
                .withStyle(share > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
        }
    }

    private static int report(CommandSourceStack src) {
        String day = String.valueOf(DateToday.today());
        String source = DateToday.isOverridden() ? " (dev override)" : " (server date)";
        String active = DateToday.activePeriods().isEmpty() ? "none" : String.join(", ", DateToday.activePeriods());
        src.sendSuccess(() -> Component.literal("Date periods for " + day + source + ": " + active)
            .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }
}
