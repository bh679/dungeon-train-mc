package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.ChunkPartEditor;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.portal.chunkparts.ChunkPartKind;
import games.brennan.dungeontrain.portal.chunkparts.ChunkPartRegistry;
import games.brennan.dungeontrain.portal.chunkparts.ChunkRoomPartsStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * {@code /dungeontrain editor chunkpart …} — authoring the frames a dimensional carriage can stand in
 * ({@link ChunkPartKind}) and naming which ones a room uses.
 *
 * <ul>
 *   <li>{@code list} — every part per kind, and which side of each is the outer layer.</li>
 *   <li>{@code enter <kind> <name> [copyOf]} — stamp the part's plot beside the Dimensions rooms and
 *       stand on it; a new name starts empty, or as a copy.</li>
 *   <li>{@code save} — save the plot you are in (or last entered).</li>
 *   <li>{@code show|add|remove <room> …} — a room's {@code .parts.json}. A frame is all four kinds, so
 *       a room is only framed once each kind has at least one part.</li>
 * </ul>
 *
 * <p>Feedback is plain text: these are author tools, reached only from the editor.</p>
 */
final class ChunkPartCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SuggestionProvider<CommandSourceStack> KINDS = (ctx, b) ->
        SharedSuggestionProvider.suggest(Arrays.stream(ChunkPartKind.values())
            .map(k -> k.name().toLowerCase(java.util.Locale.ROOT)), b);

    private static final SuggestionProvider<CommandSourceStack> PARTS = (ctx, b) -> {
        ChunkPartKind kind = ChunkPartKind.fromId(StringArgumentType.getString(ctx, "kind"));
        return kind == null ? b.buildFuture() : SharedSuggestionProvider.suggest(ChunkPartRegistry.names(kind), b);
    };

    private static final SuggestionProvider<CommandSourceStack> ROOMS = (ctx, b) ->
        SharedSuggestionProvider.suggest(TrackVariantRegistry.namesFor(TrackKind.PORTAL_ROOM), b);

    private ChunkPartCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("chunkpart")
            .executes(ctx -> list(ctx.getSource()))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("save").executes(ctx -> save(ctx.getSource())))
            .then(Commands.literal("enter")
                .then(Commands.argument("kind", StringArgumentType.word()).suggests(KINDS)
                    .then(Commands.argument("name", StringArgumentType.word()).suggests(PARTS)
                        .executes(ctx -> enter(ctx, null))
                        .then(Commands.argument("copyOf", StringArgumentType.word()).suggests(PARTS)
                            .executes(ctx -> enter(ctx, StringArgumentType.getString(ctx, "copyOf")))))))
            .then(Commands.literal("show")
                .then(Commands.argument("room", StringArgumentType.word()).suggests(ROOMS)
                    .executes(ctx -> show(ctx.getSource(), StringArgumentType.getString(ctx, "room")))))
            .then(Commands.literal("add")
                .then(Commands.argument("room", StringArgumentType.word()).suggests(ROOMS)
                    .then(Commands.argument("kind", StringArgumentType.word()).suggests(KINDS)
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(PARTS)
                            .executes(ctx -> add(ctx, 1))
                            .then(Commands.argument("weight", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> add(ctx, IntegerArgumentType.getInteger(ctx, "weight"))))))))
            .then(Commands.literal("remove")
                .then(Commands.argument("room", StringArgumentType.word()).suggests(ROOMS)
                    .then(Commands.argument("kind", StringArgumentType.word()).suggests(KINDS)
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(PARTS)
                            .executes(ChunkPartCommand::remove)))));
    }

    private static int list(CommandSourceStack source) {
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            var s = kind.size();
            String names = String.join(", ", ChunkPartRegistry.names(kind));
            source.sendSuccess(() -> Component.literal(kind.name().toLowerCase(java.util.Locale.ROOT)
                + " (" + s.getX() + "x" + s.getY() + "x" + s.getZ() + ", outer layer: " + outerSide(kind) + "): "
                + (names.isEmpty() ? "none yet" : names)), false);
        }
        return 1;
    }

    private static String outerSide(ChunkPartKind kind) {
        return switch (kind) {
            case DOORS -> "west face (low X)";
            case WALLS -> "north face (low Z)";
            case FLOOR -> "bottom layer";
            case ROOF -> "top layer";
        };
    }

    private static int enter(CommandContext<CommandSourceStack> ctx, String copyOf) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        ChunkPartKind kind = kindArg(ctx);
        String name = StringArgumentType.getString(ctx, "name");
        if (kind == null) return fail(source, "Unknown kind — use floor, walls, roof or doors.");
        if (!ChunkPartRegistry.NAME.matcher(name).matches()) {
            return fail(source, "Part names are lower-case letters, digits and _ (max 32).");
        }
        if (copyOf != null && !ChunkPartRegistry.names(kind).contains(copyOf)) {
            return fail(source, "No " + kind.name().toLowerCase(java.util.Locale.ROOT) + " part named " + copyOf + ".");
        }
        // The plots stand beside the Dimensions rooms and only while that category is resident.
        if (!EditorCommand.ensureCategoryResident(source, EditorCategory.PORTALS)) return 0;
        ChunkPartEditor.enter(player, source.getServer().overworld(), kind, name, copyOf);
        source.sendSuccess(() -> Component.literal("Editing chunk " + kind.name().toLowerCase(java.util.Locale.ROOT)
            + " '" + name + "'. Outer layer is the " + outerSide(kind) + ". Save with /dungeontrain editor chunkpart save."), false);
        return 1;
    }

    private static int save(CommandSourceStack source) {
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        Optional<ChunkPartEditor.Session> session = ChunkPartEditor.current(player);
        if (session.isEmpty()) return fail(source, "Stand in a chunk part plot, or enter one first.");
        try {
            boolean source2 = ChunkPartEditor.save(player, source.getServer().overworld(), session.get());
            String where = source2 ? " (and to the source tree)" : "";
            source.sendSuccess(() -> Component.literal("Saved chunk " + session.get().kind().name().toLowerCase(java.util.Locale.ROOT)
                + " '" + session.get().name() + "'" + where + "."), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Chunk part save failed", e);
            return fail(source, "Save failed: " + e.getMessage());
        }
    }

    private static int show(CommandSourceStack source, String room) {
        Optional<CarriagePartAssignment> a = ChunkRoomPartsStore.get(room);
        if (a.isEmpty()) {
            source.sendSuccess(() -> Component.literal(room + " has no frame — it stands in its lock skin."), false);
            return 1;
        }
        for (ChunkPartKind kind : ChunkPartKind.values()) {
            StringBuilder line = new StringBuilder(kind.name().toLowerCase(java.util.Locale.ROOT)).append(": ");
            for (CarriagePartAssignment.WeightedName e : a.get().entries(kind.carriageKind())) {
                line.append(e.name()).append(" ×").append(e.weight()).append("  ");
            }
            source.sendSuccess(() -> Component.literal(line.toString().trim()), false);
        }
        return 1;
    }

    private static int add(CommandContext<CommandSourceStack> ctx, int weight) {
        CommandSourceStack source = ctx.getSource();
        String room = StringArgumentType.getString(ctx, "room");
        ChunkPartKind kind = kindArg(ctx);
        String name = StringArgumentType.getString(ctx, "name");
        if (kind == null) return fail(source, "Unknown kind — use floor, walls, roof or doors.");
        if (!ChunkPartRegistry.names(kind).contains(name)) {
            return fail(source, "No saved " + kind.name().toLowerCase(java.util.Locale.ROOT) + " part named " + name + ".");
        }
        CarriagePartAssignment current = ChunkRoomPartsStore.get(room).orElse(CarriagePartAssignment.EMPTY);
        return write(source, room, current.withRemoved(kind.carriageKind(), name)
            .withAppended(kind.carriageKind(), name, weight));
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String room = StringArgumentType.getString(ctx, "room");
        ChunkPartKind kind = kindArg(ctx);
        if (kind == null) return fail(source, "Unknown kind — use floor, walls, roof or doors.");
        Optional<CarriagePartAssignment> current = ChunkRoomPartsStore.get(room);
        if (current.isEmpty()) return fail(source, room + " has no frame.");
        return write(source, room, current.get().withRemoved(kind.carriageKind(),
            StringArgumentType.getString(ctx, "name")));
    }

    private static int write(CommandSourceStack source, String room, CarriagePartAssignment assignment) {
        try {
            ChunkRoomPartsStore.save(room, assignment, EditorDevMode.isEnabled());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Chunk room parts save failed for {}", room, e);
            return fail(source, "Save failed: " + e.getMessage());
        }
        // Push the new assignment to the author's Chunk Parts screen, so it updates on the next frame.
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            games.brennan.dungeontrain.net.DungeonTrainNet.sendTo(player,
                games.brennan.dungeontrain.net.ChunkRoomPartsRequestPacket.build(room));
        }
        return show(source, room);
    }

    private static ChunkPartKind kindArg(CommandContext<CommandSourceStack> ctx) {
        return ChunkPartKind.fromId(StringArgumentType.getString(ctx, "kind"));
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
        return 0;
    }
}
