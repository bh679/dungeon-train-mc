package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import games.brennan.dungeontrain.editor.WholeTemplateNew;
import games.brennan.dungeontrain.editor.pool.Pool;
import games.brennan.dungeontrain.editor.pool.PoolMove;
import games.brennan.dungeontrain.editor.pool.PoolRef;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.WholeKind;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Optional;

/**
 * {@code /dungeontrain editor pool-move <carriage|whole|contents> <id> <carriage|whole|contents> [name]}
 * — move a template between the three pools at its own size tier. See
 * {@link games.brennan.dungeontrain.editor.pool.PoolMove}.
 */
public final class PoolMoveCommand {

    private static final String KEYS = "chat.dungeontrain.editor.pool_move.";

    private PoolMoveCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("pool-move");
        for (Pool from : Pool.values()) {
            LiteralArgumentBuilder<CommandSourceStack> fromNode = Commands.literal(from.key());
            var idArg = Commands.argument("id", StringArgumentType.word()).suggests(idsOf(from));
            for (Pool to : Pool.values()) {
                if (to == from) continue;
                idArg.then(Commands.literal(to.key())
                    .executes(ctx -> run(ctx.getSource(), from, StringArgumentType.getString(ctx, "id"), to, null))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> run(ctx.getSource(), from, StringArgumentType.getString(ctx, "id"), to,
                            StringArgumentType.getString(ctx, "name")))));
            }
            root.then(fromNode.then(idArg));
        }
        return root;
    }

    private static SuggestionProvider<CommandSourceStack> idsOf(Pool pool) {
        return (ctx, builder) -> {
            switch (pool) {
                case CARRIAGE -> {
                    for (CarriageVariant v : CarriageVariantRegistry.allVariants()) builder.suggest(v.id());
                }
                case CONTENTS -> {
                    for (CarriageContents c : CarriageContentsRegistry.allContents()) builder.suggest(c.id());
                }
                case WHOLE -> {
                    for (WholeKind kind : WholeKind.values()) {
                        for (String id : WholeTemplateNew.ids(kind)) builder.suggest(id);
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    private static int run(CommandSourceStack source, Pool fromPool, String rawId, Pool to, String name) {
        Optional<PoolRef> from = PoolRef.resolve(fromPool, rawId);
        if (from.isEmpty()) {
            // Either nothing of that name, or a shell that keeps its shape (flatbed, portal).
            boolean exists = fromPool == Pool.CARRIAGE && CarriageVariantRegistry.find(rawId).isPresent();
            source.sendFailure(exists
                ? Component.translatable(KEYS + "not_movable", rawId)
                : Component.translatable(KEYS + "not_found", poolName(fromPool), rawId));
            return 0;
        }
        PoolMove.Result result = PoolMove.move(source.getServer().overworld(), from.get(), to, name);
        if (!result.moved()) {
            source.sendFailure(failureLine(from.get(), to, name, result).withStyle(ChatFormatting.RED));
            return 0;
        }
        PoolRef landed = result.landed();
        MutableComponent line = Component.translatable(KEYS + "moved", from.get().id(), poolName(to), landed.id());
        if (!result.dropped().isEmpty()) {
            line.append(" ").append(Component.translatable(KEYS + "dropped", String.join(", ", result.dropped())));
        }
        source.sendSuccess(() -> line.withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** The one line each refusal gets. */
    static MutableComponent failureLine(PoolRef from, Pool to, String name, PoolMove.Result result) {
        String target = name == null || name.isBlank() ? from.id() : name;
        return switch (result.outcome()) {
            case SAME_POOL -> Component.translatable(KEYS + "same_pool", from.id(), poolName(to));
            case NOT_FOUND -> Component.translatable(KEYS + "not_found", poolName(from.pool()), from.id());
            case INVALID_NAME -> Component.translatable("chat.dungeontrain.editor.invalid_name_use_lowercase", target);
            case NAME_TAKEN -> Component.translatable(KEYS + "name_taken", target, poolName(to));
            case NOT_MOVABLE -> Component.translatable(KEYS + "not_movable", from.id());
            case PROTECTED -> Component.translatable(KEYS + "protected", from.id());
            case UNSAVED_EDITS -> Component.translatable(KEYS + "unsaved", from.id());
            case BOX_MISMATCH -> Component.translatable(KEYS + "box_mismatch", from.id(), tierName(from), result.detail());
            case GROUP_SIZE_MISMATCH -> Component.translatable(KEYS + "group_size", from.id(), result.detail());
            case NO_SHELL -> Component.translatable(KEYS + "no_shell", result.detail());
            default -> Component.translatable(KEYS + "failed", result.detail());
        };
    }

    static Component poolName(Pool pool) {
        return Component.translatable("gui.dungeontrain.editor_screen.pool." + pool.key());
    }

    private static Component tierName(PoolRef ref) {
        return Component.translatable("gui.dungeontrain.editor_menu.size." + ref.tier().contentsSize().key());
    }
}
