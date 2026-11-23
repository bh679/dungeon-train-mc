package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.VisitRules;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * {@code /dungeontrain editor portals group visits <parent> <child> …} — a dimensional sub-variant's
 * visit rules ({@link VisitRules}): its step in the order, whether it may be seen again, and the
 * weight it draws at after the Xth visit. The X menu's Order lines dispatch these.
 *
 * <pre>
 *   … visits &lt;parent&gt; &lt;child&gt; order inc|dec|&lt;0..9&gt;
 *   … visits &lt;parent&gt; &lt;child&gt; seen-again on|off
 *   … visits &lt;parent&gt; &lt;child&gt; after inc|dec|&lt;0..99&gt;
 *   … visits &lt;parent&gt; &lt;child&gt; later-weight inc|dec|&lt;0..100&gt;
 * </pre>
 *
 * <p>Rules change only what a roll draws, never where a plot sits, so a save here does not re-lay
 * the editor row out.</p>
 */
final class PortalRoomVisitRulesCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final TrackKind KIND = TrackKind.PORTAL_ROOM;

    private PortalRoomVisitRulesCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> node(SuggestionProvider<CommandSourceStack> rooms) {
        return Commands.literal("visits")
            .then(Commands.argument("parent", StringArgumentType.word()).suggests(rooms)
                .then(Commands.argument("child", StringArgumentType.word()).suggests(rooms)
                    .then(numberRule("order", VisitRules.MAX_ORDER, VisitRules::order, VisitRules::withOrder))
                    .then(Commands.literal("seen-again")
                        .then(Commands.literal("on").executes(c -> apply(c, r -> r.withSeenAgain(true))))
                        .then(Commands.literal("off").executes(c -> apply(c, r -> r.withSeenAgain(false)))))
                    .then(numberRule("after", VisitRules.MAX_AFTER_VISITS,
                        VisitRules::afterVisits, VisitRules::withAfterVisits))
                    .then(numberRule("later-weight", TrackVariantGroup.MAX_WEIGHT,
                        VisitRules::laterWeight, VisitRules::withLaterWeight))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> numberRule(
            String name, int max, ToIntFunction<VisitRules> get, BiFunction<VisitRules, Integer, VisitRules> set) {
        return Commands.literal(name)
            .then(Commands.literal("inc").executes(c -> apply(c, r -> set.apply(r, get.applyAsInt(r) + 1))))
            .then(Commands.literal("dec").executes(c -> apply(c, r -> set.apply(r, get.applyAsInt(r) - 1))))
            .then(Commands.argument("value", IntegerArgumentType.integer(0, max))
                .executes(c -> apply(c, r -> set.apply(r, IntegerArgumentType.getInteger(c, "value")))));
    }

    private static int apply(CommandContext<CommandSourceStack> ctx, UnaryOperator<VisitRules> op) {
        CommandSourceStack source = ctx.getSource();
        String parent = parseRoom(source, StringArgumentType.getString(ctx, "parent"));
        if (parent == null) return 0;
        String child = parseRoom(source, StringArgumentType.getString(ctx, "child"));
        if (child == null) return 0;
        Optional<TrackVariantGroup> group = TrackVariantGroupStore.get(KIND, parent);
        if (group.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.dimensional_carriage_has_no", parent)
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        Optional<TrackVariantGroup.Member> member = group.get().member(child);
        if (member.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.not_sub_variant", child, parent)
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        VisitRules next = op.apply(member.get().visits());
        try {
            TrackVariantGroupStore.save(KIND, parent, group.get().withMember(member.get().withVisits(next)));
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] editor portals group visits save failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.group_save_failed", e.toString())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.editor.room_visit_rules_set",
            child, next.order(), next.seenAgain() ? "yes" : "no", next.afterVisits(), next.laterWeight())
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static String parseRoom(CommandSourceStack source, String raw) {
        Optional<String> found = TrackVariantRegistry.find(KIND, raw);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_dimensional_carriage", raw)
                .withStyle(ChatFormatting.RED));
            return null;
        }
        return found.get();
    }
}
