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
 * tunnelgroups weight &lt;id&gt; &lt;0-100|inc|dec&gt;
 * tunnelgroups ungrouped &lt;0-100|inc|dec&gt;
 * tunnelgroups rename &lt;id&gt; &lt;new_id&gt;
 * tunnelgroups member &lt;tunnel_section|tunnel_portal&gt; &lt;name&gt; &lt;id&gt; &lt;0-100|inc|dec&gt;
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
                    .then(Commands.literal("inc").executes(ctx -> runWeightStep(ctx.getSource(),
                        StringArgumentType.getString(ctx, "id"), +1)))
                    .then(Commands.literal("dec").executes(ctx -> runWeightStep(ctx.getSource(),
                        StringArgumentType.getString(ctx, "id"), -1)))
                    .then(Commands.argument("value", IntegerArgumentType.integer(TunnelGroupStore.MIN, TunnelGroupStore.MAX))
                        .executes(ctx -> runWeight(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                            IntegerArgumentType.getInteger(ctx, "value"))))))
            .then(Commands.literal("ungrouped")
                .then(Commands.literal("inc").executes(ctx -> runUngrouped(ctx.getSource(),
                    TunnelGroupStore.current().ungroupedWeight() + 1)))
                .then(Commands.literal("dec").executes(ctx -> runUngrouped(ctx.getSource(),
                    TunnelGroupStore.current().ungroupedWeight() - 1)))
                .then(Commands.argument("value", IntegerArgumentType.integer(TunnelGroupStore.MIN, TunnelGroupStore.MAX))
                    .executes(ctx -> runUngrouped(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "value")))))
            .then(Commands.literal("rename")
                .then(Commands.argument("id", StringArgumentType.word()).suggests(GROUP_IDS)
                    .then(Commands.argument("new_id", StringArgumentType.word())
                        .executes(ctx -> runRename(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                            StringArgumentType.getString(ctx, "new_id"))))))
            .then(Commands.literal("member")
                .then(Commands.argument("kind", StringArgumentType.word()).suggests(KINDS)
                    .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(GROUP_IDS)
                            .then(Commands.literal("inc").executes(ctx -> runMember(ctx, +1, null)))
                            .then(Commands.literal("dec").executes(ctx -> runMember(ctx, -1, null)))
                            .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> runMember(ctx, 0, IntegerArgumentType.getInteger(ctx, "value"))))))))
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

    private static int runWeightStep(CommandSourceStack source, String raw, int step) {
        String id = idOrFail(source, raw);
        if (id == null) return 0;
        return runWeight(source, id, TunnelGroupStore.clamp(TunnelGroupEditing.snapshot().weights()
            .getOrDefault(id, TunnelGroupStore.DEFAULT_WEIGHT) + step));
    }

    private static int runRename(CommandSourceStack source, String rawFrom, String rawTo) {
        String from = idOrFail(source, rawFrom);
        String to = from == null ? null : idOrFail(source, rawTo);
        if (to == null) return 0;
        if (!TunnelGroupEditing.snapshot().weights().containsKey(from)) {
            source.sendFailure(Component.literal("No tunnel group '" + from + "'."));
            return 0;
        }
        try {
            int moved = TunnelGroupEditing.rename(from, to);
            if (moved < 0) {
                source.sendFailure(Component.literal("Tunnel group '" + to + "' already exists."));
                return 0;
            }
            return ok(source, "Renamed tunnel group '" + from + "' to '" + to + "' (" + moved + " template"
                + (moved == 1 ? "" : "s") + ").");
        } catch (IOException e) {
            return failed(source, "rename", from, e);
        }
    }

    /** {@code member … inc|dec|<value>}: a template's weight inside one group. */
    private static int runMember(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                 int step, Integer value) {
        CommandSourceStack source = ctx.getSource();
        TrackKind kind = TrackKind.fromId(StringArgumentType.getString(ctx, "kind"));
        if (kind == null || !TunnelGroupEditing.isGroupable(kind)) {
            source.sendFailure(Component.literal("Only tunnel_section and tunnel_portal templates take groups."));
            return 0;
        }
        Optional<String> name = TrackVariantRegistry.find(kind, StringArgumentType.getString(ctx, "name"));
        if (name.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown", kind.id(),
                StringArgumentType.getString(ctx, "name")));
            return 0;
        }
        String id = idOrFail(source, StringArgumentType.getString(ctx, "id"));
        if (id == null) return 0;
        int target = value != null ? value
            : games.brennan.dungeontrain.track.variant.TrackVariantWeights.groupWeightFor(kind, name.get(), id) + step;
        try {
            int stored = games.brennan.dungeontrain.track.variant.TrackVariantWeights
                .setGroupWeight(kind, name.get(), id, target);
            return ok(source, kind.id() + " '" + name.get() + "' weighs " + stored + " in tunnel group '" + id + "'.");
        } catch (IOException e) {
            return failed(source, "member weight", id, e);
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
