package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.tunnel.TunnelGroupEditing;
import games.brennan.dungeontrain.tunnel.TunnelGroupStore;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * {@code /dungeontrain editor tracks tunnelgroups …} — tunnel template groups. A tunnel rolls one
 * group (by the weights set here) and builds every section and entrance between its ends from that
 * group's members; a template may be in any number of groups.
 *
 * <pre>
 * tunnelgroups list
 * tunnelgroups new &lt;id&gt;
 * tunnelgroups weight &lt;id&gt; &lt;0-100&gt;
 * tunnelgroups ungrouped &lt;0-100&gt;
 * tunnelgroups delete &lt;id&gt;
 * tunnelgroups toggle &lt;tunnel_section|tunnel_portal&gt; &lt;name&gt; &lt;id&gt;
 * </pre>
 */
final class TunnelGroupCommands {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SuggestionProvider<CommandSourceStack> GROUP_IDS = (ctx, builder) -> {
        for (String id : TunnelGroupEditing.snapshot().ids()) builder.suggest(id);
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> KINDS = (ctx, builder) -> {
        for (TrackKind k : TunnelGroupEditing.KINDS) builder.suggest(k.id());
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, builder) -> {
        TrackKind kind = TrackKind.fromId(StringArgumentType.getString(ctx, "kind"));
        if (kind != null) for (String n : TrackVariantRegistry.namesFor(kind)) builder.suggest(n);
        return builder.buildFuture();
    };

    private TunnelGroupCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("tunnelgroups")
            .then(Commands.literal("list").executes(ctx -> runList(ctx.getSource())))
            .then(Commands.literal("new")
                .then(Commands.argument("id", StringArgumentType.word())
                    .executes(ctx -> runNew(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("weight")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(GROUP_IDS)
                    .then(Commands.argument("value", IntegerArgumentType.integer(TunnelGroupStore.MIN, TunnelGroupStore.MAX))
                        .executes(ctx -> runWeight(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                            IntegerArgumentType.getInteger(ctx, "value"))))))
            .then(Commands.literal("ungrouped")
                .then(Commands.argument("value", IntegerArgumentType.integer(TunnelGroupStore.MIN, TunnelGroupStore.MAX))
                    .executes(ctx -> runUngrouped(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "value")))))
            .then(Commands.literal("delete")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(GROUP_IDS)
                    .executes(ctx -> runDelete(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("toggle")
                .then(Commands.argument("kind", StringArgumentType.word()).suggests(KINDS)
                    .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(GROUP_IDS)
                            .executes(ctx -> runToggle(ctx.getSource(),
                                StringArgumentType.getString(ctx, "kind"),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "id")))))));
    }

    private static int runList(CommandSourceStack source) {
        EditorRosterPacket.TunnelGroups groups = TunnelGroupEditing.snapshot();
        StringBuilder line = new StringBuilder("Tunnel groups: ungrouped ×" + groups.ungroupedWeight());
        for (String id : groups.ids()) line.append(", ").append(id).append(" ×").append(groups.weights().get(id));
        source.sendSuccess(() -> Component.literal(line.toString()).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int runNew(CommandSourceStack source, String raw) {
        String id = idOrFail(source, raw);
        if (id == null) return 0;
        try {
            if (!TunnelGroupStore.create(id)) {
                source.sendFailure(Component.literal("Tunnel group '" + id + "' already exists."));
                return 0;
            }
            return ok(source, "Created tunnel group '" + id + "'.");
        } catch (IOException e) {
            return failed(source, "create", id, e);
        }
    }

    private static int runWeight(CommandSourceStack source, String raw, int value) {
        String id = idOrFail(source, raw);
        if (id == null) return 0;
        try {
            int stored = TunnelGroupStore.setWeight(id, value);
            return ok(source, "Tunnel group '" + id + "' weight is now " + stored + ".");
        } catch (IOException e) {
            return failed(source, "weight", id, e);
        }
    }

    private static int runUngrouped(CommandSourceStack source, int value) {
        try {
            int stored = TunnelGroupStore.setUngroupedWeight(value);
            return ok(source, "Ungrouped tunnel weight is now " + stored + ".");
        } catch (IOException e) {
            return failed(source, "ungrouped weight", "", e);
        }
    }

    private static int runDelete(CommandSourceStack source, String raw) {
        String id = idOrFail(source, raw);
        if (id == null) return 0;
        try {
            int stripped = TunnelGroupEditing.delete(id);
            return ok(source, "Deleted tunnel group '" + id + "' (removed from " + stripped + " template"
                + (stripped == 1 ? "" : "s") + ").");
        } catch (IOException e) {
            return failed(source, "delete", id, e);
        }
    }

    private static int runToggle(CommandSourceStack source, String rawKind, String rawName, String raw) {
        TrackKind kind = TrackKind.fromId(rawKind);
        if (kind == null || !TunnelGroupEditing.isGroupable(kind)) {
            source.sendFailure(Component.literal("Only tunnel_section and tunnel_portal templates take groups."));
            return 0;
        }
        Optional<String> name = TrackVariantRegistry.find(kind, rawName);
        if (name.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown", kind.id(), rawName));
            return 0;
        }
        String id = idOrFail(source, raw);
        if (id == null) return 0;
        try {
            boolean joined = TunnelGroupEditing.toggle(kind, name.get(), id);
            return ok(source, kind.id() + " '" + name.get() + "' " + (joined ? "joined" : "left")
                + " tunnel group '" + id + "'.");
        } catch (IOException e) {
            return failed(source, "toggle", id, e);
        }
    }

    private static String idOrFail(CommandSourceStack source, String raw) {
        String id = TunnelGroupEditing.parseId(raw);
        if (id == null) {
            source.sendFailure(Component.literal("Group ids are 1-32 characters of a-z, 0-9 and _."));
        }
        return id;
    }

    private static int ok(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int failed(CommandSourceStack source, String action, String id, IOException e) {
        LOGGER.error("[DungeonTrain] tunnel group {} '{}' failed", action, id, e);
        source.sendFailure(Component.literal("Could not save tunnel groups: " + e.getMessage())
            .withStyle(ChatFormatting.RED));
        return 0;
    }
}
