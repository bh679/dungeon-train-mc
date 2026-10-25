package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.PerkDef;
import games.brennan.dungeontrain.perks.PerkRegistry;
import games.brennan.dungeontrain.perks.PerkState;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /dungeontrain debug perk set <id> <rank> | unlock <id> | clear | list}: dev-build only
 * (a release server neither lists nor runs it, same gate as {@code workbench}). {@code set}
 * also unlocks the perk so one command is enough to see an effect; {@code clear} forgets
 * everything, unlocked set included.
 */
public final class PerkDebug {

    private static final SuggestionProvider<CommandSourceStack> PERK_IDS = (ctx, b) -> {
        PerkRegistry.bootstrap();
        return SharedSuggestionProvider.suggest(PerkRegistry.ids().stream().sorted(), b);
    };

    private PerkDebug() {}

    /** Whether the command is offered at all; split out so the gate is unit-testable. */
    public static boolean available() {
        return DungeonTrain.isDevBuild();
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("perk")
            .requires(src -> available())
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())))
            .then(Commands.literal("unlock")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(PERK_IDS)
                    .executes(ctx -> unlock(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("set")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(PERK_IDS)
                    .then(Commands.argument("rank", IntegerArgumentType.integer(0))
                        .executes(ctx -> set(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                            IntegerArgumentType.getInteger(ctx, "rank"))))));
    }

    private static int list(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        PerkRegistry.bootstrap();
        PerkState state = Perks.state(player);
        src.sendSuccess(() -> Component.literal("Perks for " + player.getScoreboardName() + ":")
            .withStyle(ChatFormatting.GOLD), false);
        for (PerkDef def : PerkRegistry.all()) {
            boolean unlocked = state.isUnlocked(def.id());
            int pts = state.activePoints(def.id());
            String line = String.format("  %-24s %-9s t%d  %s  %d/%d",
                def.id(), def.perkClass().id(), def.tier(),
                unlocked ? "unlocked" : "locked  ", pts, def.maxRank());
            ChatFormatting colour = !unlocked ? ChatFormatting.DARK_GRAY
                : pts > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY;
            src.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
        }
        return PerkRegistry.all().size();
    }

    private static int clear(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        Perks.clearAll(player);
        src.sendSuccess(() -> Component.literal("Perks cleared (unlocked and active).")
            .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int unlock(CommandSourceStack src, String id) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        if (!known(src, id)) return 0;
        Perks.unlock(player, id);
        src.sendSuccess(() -> Component.literal("Unlocked " + id + ".").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int set(CommandSourceStack src, String id, int rank) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        if (!known(src, id)) return 0;
        Perks.unlock(player, id);
        Perks.setActive(player, id, rank);
        int applied = Perks.rank(player, id);
        src.sendSuccess(() -> Component.literal(id + " rank " + applied
                + (applied != rank ? " (clamped from " + rank + ")" : ""))
            .withStyle(ChatFormatting.GREEN), true);
        return applied;
    }

    private static boolean known(CommandSourceStack src, String id) {
        PerkRegistry.bootstrap();
        if (PerkRegistry.isKnown(id)) return true;
        src.sendFailure(Component.literal("Unknown perk '" + id + "'. Known: "
            + String.join(", ", PerkRegistry.ids().stream().sorted().toList())));
        return false;
    }
}
