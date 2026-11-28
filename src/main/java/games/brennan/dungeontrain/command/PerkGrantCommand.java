package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.perks.PerkClass;
import games.brennan.dungeontrain.perks.PerkDef;
import games.brennan.dungeontrain.perks.PerkGrants;
import games.brennan.dungeontrain.perks.PerkRegistry;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.PerksGate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code /dungeontrain perks grant|revoke <targets> everything|only <perk>|class <class> [levels]},
 * shaped like {@code /advancement grant}. Free Play only: every target's run is put into Free Play
 * before anything changes, so a grant never reaches a saved profile ({@code PerkProfiles} saves
 * nothing in Free Play). Not suggested until the run is already Free Play
 * ({@link PerksCommandVisibility}). The rules for what changes are in {@link PerkGrants}.
 */
public final class PerkGrantCommand {

    private static final SuggestionProvider<CommandSourceStack> PERKS = (ctx, b) -> {
        PerkRegistry.bootstrap();
        return SharedSuggestionProvider.suggest(
            PerkRegistry.all().stream().map(PerkDef::id).filter(PerkRegistry::isBuilt), b);
    };

    private static final SuggestionProvider<CommandSourceStack> CLASSES = (ctx, b) ->
        SharedSuggestionProvider.suggest(Arrays.stream(PerkClass.values()).map(PerkClass::id), b);

    private PerkGrantCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("perks")
            .requires(PerksCommandVisibility::mayUse)
            .then(Commands.literal("grant").then(forms(true)))
            .then(Commands.literal("revoke").then(forms(false)));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> forms(boolean grant) {
        return Commands.argument("targets", EntityArgument.players())
            .then(withLevels(grant, Commands.literal("everything"), ctx -> Optional.of(new PerkGrants.Everything())))
            .then(Commands.literal("only").then(withLevels(grant,
                Commands.argument("perk", StringArgumentType.word()).suggests(PERKS),
                ctx -> perk(ctx, StringArgumentType.getString(ctx, "perk")))))
            .then(Commands.literal("class").then(withLevels(grant,
                Commands.argument("class", StringArgumentType.word()).suggests(CLASSES),
                ctx -> perkClass(ctx, StringArgumentType.getString(ctx, "class")))));
    }

    /** A form's leaf: runs at one level, and (grant only) takes an optional level count. */
    private static ArgumentBuilder<CommandSourceStack, ?> withLevels(
            boolean grant, ArgumentBuilder<CommandSourceStack, ?> node,
            Function<CommandContext<CommandSourceStack>, Optional<PerkGrants.Scope>> scope) {
        node.executes(ctx -> run(ctx, grant, scope.apply(ctx), 1));
        if (grant) {
            node.then(Commands.argument("levels", IntegerArgumentType.integer(1, 1000))
                .executes(ctx -> run(ctx, true, scope.apply(ctx), IntegerArgumentType.getInteger(ctx, "levels"))));
        }
        return node;
    }

    private static Optional<PerkGrants.Scope> perk(CommandContext<CommandSourceStack> ctx, String id) {
        PerkRegistry.bootstrap();
        if (PerkRegistry.isBuilt(id)) return Optional.of(new PerkGrants.Only(id));
        ctx.getSource().sendFailure(Component.translatable("commands.dungeontrain.perks.unknown_perk", id));
        return Optional.empty();
    }

    private static Optional<PerkGrants.Scope> perkClass(CommandContext<CommandSourceStack> ctx, String id) {
        Optional<PerkClass> found = PerkGrants.classOf(id);
        if (found.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("commands.dungeontrain.perks.unknown_class", id));
        }
        return found.map(PerkGrants.OfClass::new);
    }

    private static int run(CommandContext<CommandSourceStack> ctx, boolean grant,
                           Optional<PerkGrants.Scope> scope, int levels) throws CommandSyntaxException {
        if (scope.isEmpty()) return 0;
        CommandSourceStack src = ctx.getSource();
        if (!PerksGate.isOn()) {
            src.sendFailure(Component.translatable("commands.dungeontrain.perks.off"));
            return 0;
        }
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        PerkRegistry.bootstrap();
        List<PerkDef> covered = PerkGrants.covered(scope.get(), PerkRegistry.all(), PerkRegistry::isBuilt);
        int changed = 0;
        for (ServerPlayer target : targets) {
            // Free Play first, so PerkProfiles sees it and saves none of what follows.
            RunIntegrity.markCheated(target, Component.translatable("chat.dungeontrain.free_play.cause.perk_command"));
            Map<String, Integer> changes = PerkGrants.changes(grant, levels, covered,
                id -> Perks.state(target).unlockLevel(id));
            changes.forEach((id, level) -> Perks.setUnlockLevel(target, id, level));
            changed += changes.size();
        }
        report(src, grant, targets, changed);
        return changed;
    }

    private static void report(CommandSourceStack src, boolean grant, Collection<ServerPlayer> targets, int changed) {
        if (changed == 0) {
            src.sendFailure(Component.translatable("commands.dungeontrain.perks.none"));
            return;
        }
        Component who = targets.size() == 1
            ? targets.iterator().next().getDisplayName()
            : Component.translatable("commands.dungeontrain.perks.players", targets.size());
        String key = grant ? "commands.dungeontrain.perks.granted" : "commands.dungeontrain.perks.revoked";
        int count = changed;
        src.sendSuccess(() -> Component.translatable(key, who, count), true);
    }
}
