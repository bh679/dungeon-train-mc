package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageEditor.SaveResult;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.editor.TunnelTemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Set;

/**
 * {@code /dungeontrain editor ...} subtree — enter, save (with optional
 * rename), exit, list, reset, new, devmode, promote. Accepts both carriage
 * variants (resolved via {@link CarriageVariantRegistry}) and tunnel
 * variants ({@code tunnel_section}, {@code tunnel_portal}) for enter/save/
 * exit/list/reset. The {@code new}, {@code devmode}, and {@code promote}
 * subcommands are carriage-only. Wired into the root {@code dungeontrain}
 * command from {@link TrainCommand#register}.
 */
public final class EditorCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Prefix that distinguishes tunnel variants from carriage variants in command input. */
    private static final String TUNNEL_PREFIX = "tunnel_";

    /** Built-ins that cannot be renamed via {@code save <new_name>}. */
    private static final Set<String> PROTECTED_BUILTINS = Set.of("standard", "flatbed");

    private static final SuggestionProvider<CommandSourceStack> VARIANT_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
                builder.suggest(v.id());
            }
            for (TunnelVariant v : TunnelVariant.values()) {
                builder.suggest(TUNNEL_PREFIX + v.name().toLowerCase(Locale.ROOT));
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> BUILTIN_SUGGESTIONS =
        (ctx, builder) -> {
            for (CarriageType t : CarriageType.values()) {
                builder.suggest(t.name().toLowerCase(Locale.ROOT));
            }
            return builder.buildFuture();
        };

    private EditorCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("editor")
            .then(Commands.literal("enter")
                .executes(ctx -> runEnterCarriage(ctx.getSource(), CarriageVariant.of(CarriageType.STANDARD)))
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(VARIANT_SUGGESTIONS)
                    .executes(ctx -> runEnter(ctx.getSource(),
                        StringArgumentType.getString(ctx, "variant")))))
            .then(Commands.literal("save")
                .executes(ctx -> runSave(ctx.getSource(), null))
                .then(Commands.argument("new_name", StringArgumentType.word())
                    .executes(ctx -> runSave(ctx.getSource(),
                        StringArgumentType.getString(ctx, "new_name")))))
            .then(Commands.literal("exit").executes(ctx -> runExit(ctx.getSource())))
            .then(Commands.literal("list").executes(ctx -> runList(ctx.getSource())))
            .then(Commands.literal("reset")
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(VARIANT_SUGGESTIONS)
                    .executes(ctx -> runReset(ctx.getSource(),
                        StringArgumentType.getString(ctx, "variant")))))
            .then(Commands.literal("new")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> runNew(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name"),
                        CarriageVariant.of(CarriageType.STANDARD)))
                    .then(Commands.argument("source", StringArgumentType.word())
                        .suggests(VARIANT_SUGGESTIONS)
                        .executes(ctx -> {
                            CarriageVariant src = parseVariant(ctx.getSource(),
                                StringArgumentType.getString(ctx, "source"));
                            if (src == null) return 0;
                            return runNew(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"), src);
                        }))))
            .then(Commands.literal("devmode")
                .then(Commands.literal("on").executes(ctx -> runDevMode(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> runDevMode(ctx.getSource(), false))))
            .then(Commands.literal("promote")
                .then(Commands.literal("all").executes(ctx -> runPromoteAll(ctx.getSource())))
                .then(Commands.argument("variant", StringArgumentType.word())
                    .suggests(BUILTIN_SUGGESTIONS)
                    .executes(ctx -> {
                        CarriageType type = parseBuiltin(ctx.getSource(),
                            StringArgumentType.getString(ctx, "variant"));
                        if (type == null) return 0;
                        return runPromote(ctx.getSource(), type);
                    })));
    }

    /** True when the raw input targets a tunnel variant (prefix {@code tunnel_}). */
    private static boolean isTunnelInput(String raw) {
        return raw != null && raw.toLowerCase(Locale.ROOT).startsWith(TUNNEL_PREFIX);
    }

    /** Parse a tunnel variant argument or return {@code null} (and send an error). */
    private static TunnelVariant parseTunnelVariant(CommandSourceStack source, String raw) {
        String body = raw.toLowerCase(Locale.ROOT).substring(TUNNEL_PREFIX.length());
        try {
            return TunnelVariant.valueOf(body.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(
                "Unknown tunnel variant '" + raw + "'. Valid: tunnel_section, tunnel_portal"
            ));
            return null;
        }
    }

    private static CarriageVariant parseVariant(CommandSourceStack source, String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        return CarriageVariantRegistry.find(id).orElseGet(() -> {
            source.sendFailure(Component.literal(
                "Unknown variant '" + raw + "'. Valid: " + listIds()
            ));
            return null;
        });
    }

    /** Parse a built-in enum name for commands that only accept built-ins (promote). */
    private static CarriageType parseBuiltin(CommandSourceStack source, String raw) {
        try {
            return CarriageType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(
                "Unknown built-in '" + raw + "'. Valid: standard, windowed, solid_roof, flatbed"
            ));
            return null;
        }
    }

    private static String listIds() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            if (!first) sb.append(", ");
            sb.append(v.id());
            first = false;
        }
        for (TunnelVariant v : TunnelVariant.values()) {
            sb.append(", ").append(TUNNEL_PREFIX).append(v.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return null;
        }
    }

    private static int runEnter(CommandSourceStack source, String raw) {
        if (isTunnelInput(raw)) {
            TunnelVariant v = parseTunnelVariant(source, raw);
            if (v == null) return 0;
            return runEnterTunnel(source, v);
        }
        CarriageVariant v = parseVariant(source, raw);
        if (v == null) return 0;
        return runEnterCarriage(source, v);
    }

    private static int runEnterCarriage(CommandSourceStack source, CarriageVariant variant) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        try {
            CarriageEditor.enter(player, variant);
            source.sendSuccess(() -> Component.literal(
                "Editor: entered '" + variant.id()
                    + "' plot at " + CarriageEditor.plotOrigin(variant)
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor enter failed", t);
            source.sendFailure(Component.literal("enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runEnterTunnel(CommandSourceStack source, TunnelVariant variant) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        try {
            TunnelEditor.enter(player, variant);
            source.sendSuccess(() -> Component.literal(
                "Editor: entered '" + TUNNEL_PREFIX + variant.name().toLowerCase(Locale.ROOT)
                    + "' plot at " + TunnelEditor.plotOrigin(variant)
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor enter (tunnel) failed", t);
            source.sendFailure(Component.literal("enter failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runSave(CommandSourceStack source, String newName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        // Tunnel plots take priority — if the player is inside a tunnel plot,
        // save the tunnel template (ignoring any new_name argument; tunnel
        // templates don't support rename).
        TunnelVariant tunnel = TunnelEditor.plotContaining(player.blockPosition());
        if (tunnel != null) {
            if (newName != null) {
                source.sendFailure(Component.literal(
                    "Tunnel templates don't support rename; run '/dungeontrain editor save' without arguments."
                ));
                return 0;
            }
            try {
                TunnelEditor.save(player, tunnel);
                final TunnelVariant t = tunnel;
                source.sendSuccess(() -> Component.literal(
                    "Editor: saved '" + TUNNEL_PREFIX + t.name().toLowerCase(Locale.ROOT) + "' template."
                ), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor save (tunnel) failed", t);
                source.sendFailure(Component.literal("save failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();
        CarriageVariant current = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (current == null) {
            source.sendFailure(Component.literal(
                "Not in an editor plot. Use '/dungeontrain editor enter <variant>' first."
            ));
            return 0;
        }

        if (newName == null) {
            try {
                SaveResult result = CarriageEditor.save(player, current);
                source.sendSuccess(() -> Component.literal(
                    "Editor: saved '" + current.id() + "' template (config-dir)."
                ), true);
                if (result.sourceAttempted()) {
                    if (result.sourceWritten()) {
                        source.sendSuccess(() -> Component.literal(
                            "Editor: also wrote bundled copy to source tree (will ship with next build)."
                        ).withStyle(ChatFormatting.GREEN), true);
                    } else {
                        source.sendFailure(Component.literal(
                            "Editor: source-tree write failed: " + result.sourceError()
                        ).withStyle(ChatFormatting.YELLOW));
                    }
                }
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor save failed", t);
                source.sendFailure(Component.literal("save failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        // Rename path.
        if (PROTECTED_BUILTINS.contains(current.id())) {
            source.sendFailure(Component.literal(
                "Cannot rename '" + current.id() + "' — it is a protected built-in."
            ));
            return 0;
        }
        String newId = newName.toLowerCase(Locale.ROOT);
        if (!CarriageVariant.NAME_PATTERN.matcher(newId).matches()) {
            source.sendFailure(Component.literal(
                "Invalid name '" + newName + "'. Use lowercase letters, digits or underscore (1-32 chars)."
            ));
            return 0;
        }
        if (CarriageVariant.isReservedBuiltinName(newId)) {
            source.sendFailure(Component.literal(
                "Name '" + newId + "' is reserved for a built-in."
            ));
            return 0;
        }
        if (CarriageVariantRegistry.find(newId).isPresent()) {
            source.sendFailure(Component.literal(
                "Name '" + newId + "' is already taken."
            ));
            return 0;
        }
        try {
            CarriageVariant.Custom renamed = (CarriageVariant.Custom) CarriageVariant.custom(newId);
            CarriageEditor.saveAs(player, current, renamed);
            source.sendSuccess(() -> Component.literal(
                "Editor: saved and renamed '" + current.id() + "' → '" + renamed.id() + "'."
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor save-rename failed", t);
            source.sendFailure(Component.literal("save failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runExit(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        // Try tunnel-session first (separate session map from CarriageEditor).
        // A user who entered a carriage plot, then a tunnel plot, needs to run
        // exit twice to unwind both sessions.
        boolean exited = TunnelEditor.exit(player) || CarriageEditor.exit(player);
        if (!exited) {
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
        StringBuilder sb = new StringBuilder("Carriage variants:");
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            boolean config = CarriageTemplateStore.exists(v);
            boolean bundled = CarriageTemplateStore.bundled(v);
            String kind = v.isBuiltin() ? "builtin" : "custom";
            String status;
            if (config) status = "config override";
            else if (bundled) status = "bundled default";
            else status = v.isBuiltin() ? "fallback (hardcoded)" : "missing (no file)";
            sb.append("\n  ").append(v.id())
                .append(" — ").append(kind).append(" | ").append(status)
                .append(" [config: ").append(config ? "yes" : "no")
                .append(", bundled: ").append(bundled ? "yes" : "no").append("]");
        }
        sb.append("\nTunnel variants:");
        for (TunnelVariant v : TunnelVariant.values()) {
            boolean saved = TunnelTemplateStore.exists(v);
            sb.append("\n  ").append(TUNNEL_PREFIX).append(v.name().toLowerCase(Locale.ROOT))
                .append(" — ").append(saved ? "saved" : "fallback (procedural)");
        }
        sb.append("\nDev mode: ").append(EditorDevMode.isEnabled() ? "ON" : "off");
        sb.append("\nSource tree writable: ").append(CarriageTemplateStore.sourceTreeAvailable() ? "yes" : "no");
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int runReset(CommandSourceStack source, String raw) {
        if (isTunnelInput(raw)) {
            TunnelVariant v = parseTunnelVariant(source, raw);
            if (v == null) return 0;
            try {
                boolean deleted = TunnelTemplateStore.delete(v);
                final TunnelVariant tv = v;
                source.sendSuccess(() -> Component.literal(
                    deleted
                        ? "Editor: deleted '" + TUNNEL_PREFIX + tv.name().toLowerCase(Locale.ROOT) + "' template."
                        : "Editor: no '" + TUNNEL_PREFIX + tv.name().toLowerCase(Locale.ROOT) + "' template to delete."
                ), true);
                return 1;
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] editor reset (tunnel) failed", t);
                source.sendFailure(Component.literal("reset failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                ).withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        CarriageVariant variant = parseVariant(source, raw);
        if (variant == null) return 0;
        try {
            boolean deleted = CarriageTemplateStore.delete(variant);
            boolean wasCustom = !variant.isBuiltin();
            if (wasCustom) CarriageVariantRegistry.unregister(variant.id());
            source.sendSuccess(() -> Component.literal(
                deleted
                    ? ("Editor: deleted '" + variant.id() + "' template"
                        + (wasCustom ? " and removed from registry." : "."))
                    : ("Editor: no '" + variant.id() + "' template to delete.")
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

    private static int runNew(CommandSourceStack source, String rawName, CarriageVariant sourceVariant) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;

        String name = rawName.toLowerCase(Locale.ROOT);
        if (!CarriageVariant.NAME_PATTERN.matcher(name).matches()) {
            source.sendFailure(Component.literal(
                "Invalid name '" + rawName + "'. Use lowercase letters, digits or underscore (1-32 chars)."
            ));
            return 0;
        }
        if (CarriageVariant.isReservedBuiltinName(name)) {
            source.sendFailure(Component.literal(
                "Name '" + name + "' is reserved for a built-in."
            ));
            return 0;
        }
        if (CarriageVariantRegistry.find(name).isPresent()) {
            source.sendFailure(Component.literal(
                "Name '" + name + "' is already taken."
            ));
            return 0;
        }

        try {
            CarriageVariant.Custom target = (CarriageVariant.Custom) CarriageVariant.custom(name);
            var origin = CarriageEditor.duplicate(player, sourceVariant, target);
            source.sendSuccess(() -> Component.literal(
                "Editor: created '" + target.id() + "' from '" + sourceVariant.id()
                    + "' at plot " + origin
            ), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor new failed", t);
            source.sendFailure(Component.literal("new failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runDevMode(CommandSourceStack source, boolean on) {
        EditorDevMode.set(on);
        boolean writable = CarriageTemplateStore.sourceTreeAvailable();
        if (on) {
            if (writable) {
                source.sendSuccess(() -> Component.literal(
                    "Editor dev mode: ON — '/editor save' will also write to source tree."
                ).withStyle(ChatFormatting.GREEN), true);
            } else {
                source.sendSuccess(() -> Component.literal(
                    "Editor dev mode: ON — but source tree is NOT writable. Are you running ./gradlew runClient?"
                ).withStyle(ChatFormatting.YELLOW), true);
            }
        } else {
            source.sendSuccess(() -> Component.literal(
                "Editor dev mode: off — '/editor save' writes to config-dir only."
            ), true);
        }
        return 1;
    }

    private static int runPromote(CommandSourceStack source, CarriageType type) {
        try {
            CarriageTemplateStore.promote(type);
            source.sendSuccess(() -> Component.literal(
                "Editor: promoted '" + type.name().toLowerCase(Locale.ROOT)
                    + "' template to source tree (will ship with next build)."
            ).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] editor promote failed for {}", type, t);
            source.sendFailure(Component.literal("promote failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage()
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int runPromoteAll(CommandSourceStack source) {
        if (!CarriageTemplateStore.sourceTreeAvailable()) {
            source.sendFailure(Component.literal(
                "promote all failed: source tree not writable. Are you running ./gradlew runClient?"
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
        int promoted = 0;
        int skipped = 0;
        StringBuilder errors = new StringBuilder();
        for (CarriageType type : CarriageType.values()) {
            CarriageVariant variant = CarriageVariant.of(type);
            if (!CarriageTemplateStore.exists(variant)) {
                skipped++;
                continue;
            }
            try {
                CarriageTemplateStore.promote(type);
                promoted++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] editor promote-all failed for {}", type, e);
                errors.append("\n  ").append(type.name().toLowerCase(Locale.ROOT))
                    .append(": ").append(e.getMessage());
            }
        }
        final int p = promoted;
        final int s = skipped;
        final String errStr = errors.toString();
        source.sendSuccess(() -> Component.literal(
            "Editor: promote all — " + p + " promoted, " + s + " skipped (no config copy)."
                + (errStr.isEmpty() ? "" : "\nErrors:" + errStr)
        ).withStyle(errStr.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return p > 0 ? 1 : 0;
    }
}
