package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.editor.TunnelTemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * {@code /dungeontrain editor ...} subtree — enter, save, exit, list, reset.
 * Accepts both carriage variants ({@code standard}, {@code windowed},
 * {@code solid_roof}, {@code flatbed}) and tunnel variants
 * ({@code tunnel_section}, {@code tunnel_portal}). Wired into the root
 * {@code dungeontrain} command from {@link TrainCommand#register}.
 */
public final class EditorCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TUNNEL_PREFIX = "tunnel_";

    private static final SuggestionProvider<CommandSourceStack> VARIANT_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriageType t : CarriageType.values()) {
                builder.suggest(t.name().toLowerCase(Locale.ROOT));
            }
            for (TunnelVariant v : TunnelVariant.values()) {
                builder.suggest(TUNNEL_PREFIX + v.name().toLowerCase(Locale.ROOT));
            }
            return builder.buildFuture();
        };

    /**
     * Parsed editor target — either a carriage variant or a tunnel variant.
     * Exactly one field is non-null.
     */
    private record EditorTarget(CarriageType carriage, TunnelVariant tunnel) {
        boolean isCarriage() { return carriage != null; }
        boolean isTunnel() { return tunnel != null; }
        String displayName() {
            return isCarriage()
                ? carriage.name().toLowerCase(Locale.ROOT)
                : TUNNEL_PREFIX + tunnel.name().toLowerCase(Locale.ROOT);
        }
    }

    private EditorCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("editor")
            .then(Commands.literal("enter")
                .executes(ctx -> runEnter(ctx.getSource(), new EditorTarget(CarriageType.STANDARD, null)))
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(VARIANT_SUGGESTIONS)
                    .executes(ctx -> {
                        EditorTarget target = parseVariant(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"));
                        if (target == null) return 0;
                        return runEnter(ctx.getSource(), target);
                    })))
            .then(Commands.literal("save").executes(ctx -> runSave(ctx.getSource())))
            .then(Commands.literal("exit").executes(ctx -> runExit(ctx.getSource())))
            .then(Commands.literal("list").executes(ctx -> runList(ctx.getSource())))
            .then(Commands.literal("reset")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(VARIANT_SUGGESTIONS)
                    .executes(ctx -> {
                        EditorTarget target = parseVariant(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"));
                        if (target == null) return 0;
                        return runReset(ctx.getSource(), target);
                    })));
    }

    private static EditorTarget parseVariant(CommandSourceStack source, String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith(TUNNEL_PREFIX)) {
            String tunnelName = lower.substring(TUNNEL_PREFIX.length());
            try {
                return new EditorTarget(null, TunnelVariant.valueOf(tunnelName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                source.sendFailure(Component.literal(
                    "Unknown tunnel variant '" + raw + "'. Valid: tunnel_section, tunnel_portal"
                ));
                return null;
            }
        }
        try {
            return new EditorTarget(CarriageType.valueOf(lower.toUpperCase(Locale.ROOT)), null);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(
                "Unknown variant '" + raw + "'. Valid: standard, windowed, solid_roof, flatbed, tunnel_section, tunnel_portal"
            ));
            return null;
        }
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return null;
        }
    }

    private static int runEnter(CommandSourceStack source, EditorTarget target) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        try {
            if (target.isCarriage()) {
                CarriageEditor.enter(player, target.carriage());
                source.sendSuccess(() -> Component.literal(
                    "Editor: entered '" + target.displayName()
                        + "' plot at " + CarriageEditor.plotOrigin(target.carriage())
                ), true);
            } else {
                TunnelEditor.enter(player, target.tunnel());
                source.sendSuccess(() -> Component.literal(
                    "Editor: entered '" + target.displayName()
                        + "' plot at " + TunnelEditor.plotOrigin(target.tunnel())
                ), true);
            }
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor enter failed", t);
            source.sendFailure(Component.literal("enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runSave(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        CarriageType carriage = CarriageEditor.plotContaining(player.blockPosition(), dims);
        TunnelVariant tunnel = (carriage == null)
            ? TunnelEditor.plotContaining(player.blockPosition())
            : null;
        if (carriage == null && tunnel == null) {
            source.sendFailure(Component.literal(
                "Not in an editor plot. Use '/dungeontrain editor enter <variant>' first."
            ));
            return 0;
        }
        try {
            String name;
            if (carriage != null) {
                CarriageEditor.save(player, carriage);
                name = carriage.name().toLowerCase(Locale.ROOT);
            } else {
                TunnelEditor.save(player, tunnel);
                name = TUNNEL_PREFIX + tunnel.name().toLowerCase(Locale.ROOT);
            }
            final String nameCopy = name;
            source.sendSuccess(() -> Component.literal(
                "Editor: saved '" + nameCopy + "' template."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor save failed", t);
            source.sendFailure(Component.literal("save failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runExit(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        // Both editors share EditorSessions, so either one's exit clears the
        // same map. Call via CarriageEditor for backward-compat logging.
        if (!CarriageEditor.exit(player)) {
            source.sendFailure(Component.literal(
                "No saved editor session — nothing to exit to."
            ));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "Editor: exited, returned to previous location."
        ), true);
        return 1;
    }

    private static int runList(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("Editor templates:");
        for (CarriageType t : CarriageType.values()) {
            boolean saved = CarriageTemplateStore.exists(t);
            sb.append("\n  ").append(t.name().toLowerCase(Locale.ROOT))
                .append(" — ").append(saved ? "saved" : "fallback (hardcoded)");
        }
        for (TunnelVariant v : TunnelVariant.values()) {
            boolean saved = TunnelTemplateStore.exists(v);
            sb.append("\n  ").append(TUNNEL_PREFIX).append(v.name().toLowerCase(Locale.ROOT))
                .append(" — ").append(saved ? "saved" : "fallback (procedural)");
        }
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int runReset(CommandSourceStack source, EditorTarget target) {
        try {
            boolean deleted;
            if (target.isCarriage()) {
                deleted = CarriageTemplateStore.delete(target.carriage());
            } else {
                deleted = TunnelTemplateStore.delete(target.tunnel());
            }
            final boolean deletedCopy = deleted;
            source.sendSuccess(() -> Component.literal(
                deletedCopy
                    ? "Editor: deleted '" + target.displayName() + "' template."
                    : "Editor: no '" + target.displayName() + "' template to delete."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor reset failed", t);
            source.sendFailure(Component.literal("reset failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }
}
