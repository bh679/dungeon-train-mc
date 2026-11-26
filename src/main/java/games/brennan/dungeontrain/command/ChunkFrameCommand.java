package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.ChunkFrameEditor;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.net.ChunkFrameRoomsRequestPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrame;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameRegistry;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMeta;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMetaStore;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * {@code /dungeontrain editor chunkframe …} — authoring the frames a dimensional carriage room can be
 * dressed in ({@link ChunkFrame}) and naming which ones a room draws from.
 *
 * <ul>
 *   <li>{@code list} — every frame.</li>
 *   <li>{@code enter <name> [copyOf]} — stamp the frame's plot beside the Dimensions rooms and stand
 *       on it; a new name starts empty, or as a copy.</li>
 *   <li>{@code save} — save the plot you are in (or last entered).</li>
 *   <li>{@code show <frame>}, {@code rooms <frame> all|none}, {@code room <frame> <room> on|off},
 *       {@code weight <frame> <n>} — which chunk dimensions the frame dresses, and how often it is
 *       picked against the other frames there ({@code <frame>.frame.json}).</li>
 * </ul>
 *
 * <p>Feedback is plain text: these are author tools, reached only from the editor.</p>
 */
final class ChunkFrameCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SuggestionProvider<CommandSourceStack> FRAMES = (ctx, b) ->
        SharedSuggestionProvider.suggest(ChunkFrameRegistry.names(), b);

    private static final SuggestionProvider<CommandSourceStack> CHUNK_ROOMS = (ctx, b) ->
        SharedSuggestionProvider.suggest(ChunkFrame.chunkRooms(), b);

    private ChunkFrameCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("chunkframe")
            .executes(ctx -> list(ctx.getSource()))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("save").executes(ctx -> save(ctx.getSource(), null))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(FRAMES)
                    .executes(ctx -> save(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            // New blank frame at a size: 1x1 (one chunk) or 3x3 (a 3x3 room's), the New menu's two blanks.
            .then(Commands.literal("new")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.literal("1x1").executes(ctx -> newFrame(ctx, 1)))
                    .then(Commands.literal("3x3").executes(ctx -> newFrame(ctx, 3)))))
            .then(Commands.literal("enter")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(FRAMES)
                    .executes(ctx -> enter(ctx, null))
                    .then(Commands.argument("copyOf", StringArgumentType.word()).suggests(FRAMES)
                        .executes(ctx -> enter(ctx, StringArgumentType.getString(ctx, "copyOf"))))))
            // Go here / Enter: walk to a frame's plot as it stands, without restamping it — unsaved
            // edits stay. In front of its menu, or into the middle with "centre".
            .then(Commands.literal("goto")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(FRAMES)
                    .executes(ctx -> walkTo(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))
                    .then(Commands.literal("centre")
                        .executes(ctx -> walkTo(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)))))
            .then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(FRAMES)
                    .executes(ctx -> delete(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("rename")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(FRAMES)
                    .then(Commands.argument("newName", StringArgumentType.word())
                        .executes(ctx -> rename(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                            StringArgumentType.getString(ctx, "newName"))))))
            .then(Commands.literal("show")
                .then(Commands.argument("frame", StringArgumentType.word()).suggests(FRAMES)
                    .executes(ctx -> show(ctx.getSource(), StringArgumentType.getString(ctx, "frame")))))
            .then(Commands.literal("rooms")
                .then(Commands.argument("frame", StringArgumentType.word()).suggests(FRAMES)
                    .then(Commands.literal("all").executes(ctx -> editMeta(ctx, ChunkFrameMeta::withAllRooms)))
                    .then(Commands.literal("none").executes(ctx -> editMeta(ctx, ChunkFrameMeta::withNoRooms)))))
            .then(Commands.literal("room")
                .then(Commands.argument("frame", StringArgumentType.word()).suggests(FRAMES)
                    .then(Commands.argument("room", StringArgumentType.word()).suggests(CHUNK_ROOMS)
                        .then(Commands.literal("on").executes(ctx -> toggleRoom(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> toggleRoom(ctx, false))))))
            .then(Commands.literal("weight")
                .then(Commands.argument("frame", StringArgumentType.word()).suggests(FRAMES)
                    .then(Commands.literal("inc").executes(ctx -> editMeta(ctx, m -> m.withWeight(m.weight() + 1))))
                    .then(Commands.literal("dec").executes(ctx -> editMeta(ctx, m -> m.withWeight(m.weight() - 1))))
                    .then(Commands.argument("weight",
                            IntegerArgumentType.integer(ChunkFrameMeta.MIN_WEIGHT, ChunkFrameMeta.MAX_WEIGHT))
                        .executes(ctx -> editMeta(ctx, m -> m.withWeight(IntegerArgumentType.getInteger(ctx, "weight")))))))
            // How the rooms this frame dresses are enclosed: bedrock (sealed), void, world_border, endless_open
            // or plank_bridge.
            .then(Commands.literal("dimension")
                .then(Commands.argument("frame", StringArgumentType.word()).suggests(FRAMES)
                    .then(Commands.literal("bedrock").executes(ctx -> editMeta(ctx,
                        m -> m.withDimension(games.brennan.dungeontrain.portal.chunkframe.ChunkFrameDimension.BEDROCK))))
                    .then(Commands.literal("void").executes(ctx -> editMeta(ctx,
                        m -> m.withDimension(games.brennan.dungeontrain.portal.chunkframe.ChunkFrameDimension.VOID))))
                    .then(Commands.literal("world_border").executes(ctx -> editMeta(ctx,
                        m -> m.withDimension(games.brennan.dungeontrain.portal.chunkframe.ChunkFrameDimension.WORLD_BORDER))))
                    .then(Commands.literal("endless_open").executes(ctx -> editMeta(ctx,
                        m -> m.withDimension(games.brennan.dungeontrain.portal.chunkframe.ChunkFrameDimension.ENDLESS_OPEN))))
                    .then(Commands.literal("plank_bridge").executes(ctx -> editMeta(ctx,
                        m -> m.withDimension(games.brennan.dungeontrain.portal.chunkframe.ChunkFrameDimension.PLANK_BRIDGE))))))
            // An Endless open frame's doors: how many blocks out along the plain each stands from the chunk.
            .then(Commands.literal("doordistance")
                .then(Commands.argument("frame", StringArgumentType.word()).suggests(FRAMES)
                    .then(Commands.literal("inc").executes(ctx -> editMeta(ctx, m -> m.withDoorDistance(m.doorDistance() + 1))))
                    .then(Commands.literal("dec").executes(ctx -> editMeta(ctx, m -> m.withDoorDistance(m.doorDistance() - 1))))
                    .then(Commands.argument("distance",
                            IntegerArgumentType.integer(0, games.brennan.dungeontrain.portal.PortalRoomDoorDistance.MAX))
                        .executes(ctx -> editMeta(ctx, m -> m.withDoorDistance(IntegerArgumentType.getInteger(ctx, "distance")))))))
            // An Endless open frame's ground: the held block (or variant clipboard), the Block Variant
            // menu on it, or a typed block id.
            .then(Commands.literal("ground")
                .then(Commands.argument("frame", StringArgumentType.word()).suggests(FRAMES)
                    .then(Commands.literal("held").executes(ChunkFrameCommand::groundHeld))
                    .then(Commands.literal("edit").executes(ChunkFrameCommand::groundEdit))
                    .then(Commands.literal("default").executes(ctx -> editMeta(ctx, m -> m.withGround(java.util.List.of()))))
                    .then(Commands.argument("block", StringArgumentType.greedyString())
                        .executes(ChunkFrameCommand::groundBlock))))
            // Spawn gate, the same subcommands every template's gate takes: where along the train the
            // frame may appear. The editor's gate rows send these (PlotCategory.CHUNK_FRAMES).
            .then(EditorCommand.minLevelSingle(FRAMES, ChunkFrameCommand::editGate))
            .then(EditorCommand.maxLevelSingle(FRAMES, ChunkFrameCommand::editGate))
            .then(EditorCommand.phaseSingle(FRAMES, ChunkFrameCommand::editGate))
            .then(EditorCommand.periodSingle(FRAMES, ChunkFrameCommand::editGate))
            // Link to a Stage preset (it then owns the levels and bands), or "custom" for the frame's own.
            .then(Commands.literal("stage")
                .then(Commands.argument("frame", StringArgumentType.word()).suggests(FRAMES)
                    .then(Commands.argument("stage", StringArgumentType.word())
                        .executes(ctx -> {
                            String token = StringArgumentType.getString(ctx, "stage");
                            String stage = "custom".equalsIgnoreCase(token) ? null : token;
                            return editMeta(ctx, m -> m.withStage(stage));
                        }))));
    }

    private static int list(CommandSourceStack source) {
        String names = ChunkFrameRegistry.names().stream().map(n -> {
            var s = games.brennan.dungeontrain.portal.chunkframe.ChunkFrameStore.sizeOf(n);
            return n + " (" + s.getX() + "x" + s.getY() + "x" + s.getZ() + ")";
        }).collect(java.util.stream.Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("Chunk frames (each the room it fits plus a one-block shell): "
            + (names.isEmpty() ? "none yet" : names)), false);
        return 1;
    }

    private static int newFrame(CommandContext<CommandSourceStack> ctx, int span) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        String name = StringArgumentType.getString(ctx, "name");
        if (!ChunkFrameRegistry.NAME.matcher(name).matches()) {
            return fail(source, "Frame names are lower-case letters, digits and _ (max 32).");
        }
        if (ChunkFrameRegistry.names().contains(name)) return fail(source, "There is already a frame named " + name + ".");
        if (!EditorCommand.ensureCategoryResident(source, EditorCategory.PORTALS)) return 0;
        int width = span * games.brennan.dungeontrain.portal.PortalChunkTerrain.SIZE;
        net.minecraft.core.Vec3i size = ChunkFrame.sizeFor(new net.minecraft.core.Vec3i(
            width, games.brennan.dungeontrain.portal.PortalChunkTerrain.HEIGHT, width));
        ChunkFrameEditor.enterNew(player, source.getServer().overworld(), name, size);
        source.sendSuccess(() -> Component.literal("New " + span + "x" + span + " frame '" + name + "'."), false);
        return 1;
    }

    private static int enter(CommandContext<CommandSourceStack> ctx, String copyOf) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        String name = StringArgumentType.getString(ctx, "name");
        if (!ChunkFrameRegistry.NAME.matcher(name).matches()) {
            return fail(source, "Frame names are lower-case letters, digits and _ (max 32).");
        }
        if (copyOf != null && !ChunkFrameRegistry.names().contains(copyOf)) {
            return fail(source, "No frame named " + copyOf + ".");
        }
        // The plots stand beside the Dimensions rooms and only while that category is resident.
        if (!EditorCommand.ensureCategoryResident(source, EditorCategory.PORTALS)) return 0;
        ChunkFrameEditor.enter(player, source.getServer().overworld(), name, copyOf);
        source.sendSuccess(() -> Component.literal("Editing frame '" + name + "'. The outermost layer is the "
            + "shell where the skybox would be; everything inside it is the room."), false);
        return 1;
    }

    private static int walkTo(CommandSourceStack source, String name, boolean centre) {
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        if (!ChunkFrameRegistry.names().contains(name)) return fail(source, "No frame named " + name + ".");
        if (!EditorCommand.ensureCategoryResident(source, EditorCategory.PORTALS)) return 0;
        ChunkFrameEditor.walkTo(player, source.getServer().overworld(), name, centre);
        return 1;
    }

    /** Save {@code named}, or the frame the player stands in (or last entered) when it is null. */
    private static int save(CommandSourceStack source, String named) {
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        if (named != null && !ChunkFrameRegistry.names().contains(named)) return fail(source, "No frame named " + named + ".");
        Optional<String> name = named != null ? Optional.of(named) : ChunkFrameEditor.current(player);
        if (name.isEmpty()) return fail(source, "Stand in a frame plot, or enter one first.");
        try {
            boolean toSource = ChunkFrameEditor.save(player, source.getServer().overworld(), name.get());
            source.sendSuccess(() -> Component.literal("Saved frame '" + name.get() + "'"
                + (toSource ? " (and to the source tree)." : ".")), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Chunk frame save failed", e);
            return fail(source, "Save failed: " + e.getMessage());
        }
    }

    private static int delete(CommandSourceStack source, String name) {
        if (!ChunkFrameRegistry.names().contains(name)) return fail(source, "No frame named " + name + ".");
        try {
            boolean gone = ChunkFrameEditor.delete(source.getServer().overworld(), name, EditorDevMode.isEnabled());
            if (!gone) {
                return fail(source, "'" + name + "' ships with the mod and can't be deleted — Reset puts it back instead.");
            }
            source.sendSuccess(() -> Component.literal("Deleted frame '" + name + "'."), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Chunk frame delete failed for {}", name, e);
            return fail(source, "Delete failed: " + e.getMessage());
        }
    }

    private static int rename(CommandSourceStack source, String name, String newName) {
        if (!ChunkFrameRegistry.names().contains(name)) return fail(source, "No frame named " + name + ".");
        if (!ChunkFrameRegistry.NAME.matcher(newName).matches()) {
            return fail(source, "Frame names are lower-case letters, digits and _ (max 32).");
        }
        if (ChunkFrameRegistry.names().contains(newName)) return fail(source, "A frame named " + newName + " already exists.");
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        try {
            ChunkFrameEditor.rename(player, source.getServer().overworld(), name, newName, EditorDevMode.isEnabled());
            source.sendSuccess(() -> Component.literal("Renamed frame '" + name + "' to '" + newName + "'."), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Chunk frame rename failed for {}", name, e);
            return fail(source, "Rename failed: " + e.getMessage());
        }
    }

    private static int show(CommandSourceStack source, String frame) {
        if (!ChunkFrameRegistry.names().contains(frame)) return fail(source, "No frame named " + frame + ".");
        ChunkFrameMeta meta = ChunkFrameMetaStore.get(frame);
        String rooms = meta.allRooms() ? "every chunk dimension"
            : meta.rooms().isEmpty() ? "no chunk dimension" : String.join(", ", meta.rooms());
        String enclosure = meta.dimension().label()
            + (meta.dimension() == games.brennan.dungeontrain.portal.chunkframe.ChunkFrameDimension.ENDLESS_OPEN
                || meta.dimension() == games.brennan.dungeontrain.portal.chunkframe.ChunkFrameDimension.PLANK_BRIDGE
                ? ", doors " + meta.doorDistance() + " out" : "");
        source.sendSuccess(() -> Component.literal("Frame '" + frame + "' (weight " + meta.weight() + ", "
            + enclosure + ") dresses: " + rooms), false);
        return 1;
    }

    private static int groundHeld(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return fail(ctx.getSource(), "Only a player can set the ground from their hand.");
        java.util.List<games.brennan.dungeontrain.editor.VariantState> states =
            EditorCommand.statesFromHeld(ctx.getSource(), player.getMainHandItem());
        if (states == null) return 0;
        return editMeta(ctx, m -> m.withGround(states));
    }

    private static int groundEdit(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return fail(ctx.getSource(), "Only a player can open the Block Variant menu.");
        String frame = StringArgumentType.getString(ctx, "frame");
        if (!ChunkFrameRegistry.names().contains(frame)) return fail(ctx.getSource(), "No frame named " + frame + ".");
        games.brennan.dungeontrain.editor.BlockVariantMenuController.openForFrameGround(player, frame);
        return 1;
    }

    private static int groundBlock(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "block");
        java.util.Optional<net.minecraft.world.level.block.state.BlockState> state =
            games.brennan.dungeontrain.portal.PortalRoomSinglePlanes.stateFor(raw);
        if (state.isEmpty()) return fail(ctx.getSource(), raw + " is not a block this world knows.");
        return editMeta(ctx, m -> m.withGround(java.util.List.of(
            new games.brennan.dungeontrain.editor.VariantState(state.get(), null))));
    }

    private static int toggleRoom(CommandContext<CommandSourceStack> ctx, boolean on) {
        String room = StringArgumentType.getString(ctx, "room");
        java.util.List<String> all = ChunkFrame.chunkRooms(StringArgumentType.getString(ctx, "frame"));
        if (!all.contains(room)) return fail(ctx.getSource(), room + " is not a chunk dimension this frame fits.");
        return editMeta(ctx, m -> m.toggled(room, on, all));
    }

    private static int editMeta(CommandContext<CommandSourceStack> ctx, java.util.function.UnaryOperator<ChunkFrameMeta> edit) {
        return editMetaNamed(ctx.getSource(), StringArgumentType.getString(ctx, "frame"), edit);
    }

    private static int editMetaNamed(CommandSourceStack source, String frame,
                                     java.util.function.UnaryOperator<ChunkFrameMeta> edit) {
        if (!ChunkFrameRegistry.names().contains(frame)) return fail(source, "No frame named " + frame + ".");
        try {
            ChunkFrameMetaStore.save(frame, edit.apply(ChunkFrameMetaStore.get(frame)), EditorDevMode.isEnabled());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Chunk frame meta save failed for {}", frame, e);
            return fail(source, "Save failed: " + e.getMessage());
        }
        // Push the new selection to the author's Chunk dimensions screen, so it updates on the next frame.
        ServerPlayer player = source.getPlayer();
        if (player != null) DungeonTrainNet.sendTo(player, ChunkFrameRoomsRequestPacket.build(frame));
        return 1;
    }

    /** The gate subcommands' apply step for a frame: {@code op} over its meta's own gate. */
    private static int editGate(CommandSourceStack source, String frame,
                                java.util.function.UnaryOperator<games.brennan.dungeontrain.template.TemplateGate> op) {
        return editMetaNamed(source, frame, m -> m.withGate(op.apply(m.gate())));
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
        return 0;
    }
}
