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
import games.brennan.dungeontrain.net.ChunkRoomFramesRequestPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrame;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameRegistry;
import games.brennan.dungeontrain.portal.chunkframe.ChunkRoomFrames;
import games.brennan.dungeontrain.portal.chunkframe.ChunkRoomFramesStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
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
 *   <li>{@code show|add|remove <room> …} — a room's {@code .frames.json}. {@code add} with a weight
 *       sets the weight of a frame already on the list.</li>
 * </ul>
 *
 * <p>Feedback is plain text: these are author tools, reached only from the editor.</p>
 */
final class ChunkFrameCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SuggestionProvider<CommandSourceStack> FRAMES = (ctx, b) ->
        SharedSuggestionProvider.suggest(ChunkFrameRegistry.names(), b);

    private static final SuggestionProvider<CommandSourceStack> ROOMS = (ctx, b) ->
        SharedSuggestionProvider.suggest(TrackVariantRegistry.namesFor(TrackKind.PORTAL_ROOM), b);

    private ChunkFrameCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("chunkframe")
            .executes(ctx -> list(ctx.getSource()))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("save").executes(ctx -> save(ctx.getSource())))
            .then(Commands.literal("enter")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(FRAMES)
                    .executes(ctx -> enter(ctx, null))
                    .then(Commands.argument("copyOf", StringArgumentType.word()).suggests(FRAMES)
                        .executes(ctx -> enter(ctx, StringArgumentType.getString(ctx, "copyOf"))))))
            .then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(FRAMES)
                    .executes(ctx -> delete(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("rename")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(FRAMES)
                    .then(Commands.argument("newName", StringArgumentType.word())
                        .executes(ctx -> rename(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                            StringArgumentType.getString(ctx, "newName"))))))
            .then(Commands.literal("show")
                .then(Commands.argument("room", StringArgumentType.word()).suggests(ROOMS)
                    .executes(ctx -> show(ctx.getSource(), StringArgumentType.getString(ctx, "room")))))
            .then(Commands.literal("add")
                .then(Commands.argument("room", StringArgumentType.word()).suggests(ROOMS)
                    .then(Commands.argument("frame", StringArgumentType.word()).suggests(FRAMES)
                        .executes(ctx -> add(ctx, ChunkRoomFrames.MIN_WEIGHT))
                        .then(Commands.argument("weight",
                                IntegerArgumentType.integer(ChunkRoomFrames.MIN_WEIGHT, ChunkRoomFrames.MAX_WEIGHT))
                            .executes(ctx -> add(ctx, IntegerArgumentType.getInteger(ctx, "weight")))))))
            .then(Commands.literal("remove")
                .then(Commands.argument("room", StringArgumentType.word()).suggests(ROOMS)
                    .then(Commands.argument("frame", StringArgumentType.word()).suggests(FRAMES)
                        .executes(ChunkFrameCommand::remove))));
    }

    private static int list(CommandSourceStack source) {
        String names = String.join(", ", ChunkFrameRegistry.names());
        var s = ChunkFrame.SIZE;
        source.sendSuccess(() -> Component.literal("Chunk frames (" + s.getX() + "x" + s.getY() + "x" + s.getZ()
            + ", the room plus a one-block shell): " + (names.isEmpty() ? "none yet" : names)), false);
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

    private static int save(CommandSourceStack source) {
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        Optional<String> name = ChunkFrameEditor.current(player);
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

    private static int show(CommandSourceStack source, String room) {
        ChunkRoomFrames frames = ChunkRoomFramesStore.get(room);
        if (frames.isEmpty()) {
            source.sendSuccess(() -> Component.literal(room + " has no frame — it stands in its lock skin."), false);
            return 1;
        }
        StringBuilder line = new StringBuilder(room).append(" frames: ");
        for (ChunkRoomFrames.Entry e : frames.entries()) line.append(e.name()).append(" ×").append(e.weight()).append("  ");
        source.sendSuccess(() -> Component.literal(line.toString().trim()), false);
        return 1;
    }

    private static int add(CommandContext<CommandSourceStack> ctx, int weight) {
        CommandSourceStack source = ctx.getSource();
        String room = StringArgumentType.getString(ctx, "room");
        String frame = StringArgumentType.getString(ctx, "frame");
        if (!ChunkFrameRegistry.names().contains(frame)) return fail(source, "No saved frame named " + frame + ".");
        return write(source, room, ChunkRoomFramesStore.get(room).with(frame, weight));
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String room = StringArgumentType.getString(ctx, "room");
        return write(source, room, ChunkRoomFramesStore.get(room).without(StringArgumentType.getString(ctx, "frame")));
    }

    private static int write(CommandSourceStack source, String room, ChunkRoomFrames frames) {
        try {
            ChunkRoomFramesStore.save(room, frames, EditorDevMode.isEnabled());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Chunk frames save failed for {}", room, e);
            return fail(source, "Save failed: " + e.getMessage());
        }
        // Push the new list to the author's Frames screen, so it updates on the next frame.
        ServerPlayer player = source.getPlayer();
        if (player != null) DungeonTrainNet.sendTo(player, ChunkRoomFramesRequestPacket.build(room));
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
        return 0;
    }
}
