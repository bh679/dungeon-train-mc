package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.building.BuildingMeta;
import games.brennan.dungeontrain.building.BuildingRegistry;
import games.brennan.dungeontrain.building.BuildingStore;
import games.brennan.dungeontrain.building.BuildingWorldgen;
import games.brennan.dungeontrain.building.Buildings;
import games.brennan.dungeontrain.editor.BuildingEditor;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorDevMode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * {@code /dungeontrain editor buildings …} — the Buildings tab: DT's Lost City buildings to edit, and new
 * ones that join the Lost City and WWOO stretch as built.
 *
 * <ul>
 *   <li>(no argument) — open the tab: every building's plot, landing on the first.</li>
 *   <li>{@code list} — every building, shipped and new.</li>
 *   <li>{@code enter <name> [copyOf]} — stamp a building's plot and stand on it; a new name starts as an
 *       empty pad, or as a copy.</li>
 *   <li>{@code goto <name> [centre]} — walk to a plot as it stands, unsaved edits kept.</li>
 *   <li>{@code save [name]} — save the plot you are in (or last entered).</li>
 *   <li>{@code size <x|y|z> <inc|dec|n>} — grow or shrink the plot you are in, up to
 *       {@link Buildings#MAX_SIZE}.</li>
 *   <li>{@code weight <name> <inc|dec|n>} — how often a new building is picked against the others.</li>
 *   <li>{@code delete <name>} — a new building leaves; a shipped one goes back to the jar's.</li>
 * </ul>
 */
final class BuildingCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SuggestionProvider<CommandSourceStack> BUILDINGS = (ctx, b) ->
        SharedSuggestionProvider.suggest(BuildingRegistry.names(), b);

    private BuildingCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("buildings")
            .executes(ctx -> EditorCommand.enterCategory(ctx.getSource(), EditorCategory.BUILDINGS))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(Commands.literal("save").executes(ctx -> save(ctx.getSource(), null))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(BUILDINGS)
                    .executes(ctx -> save(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("enter")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(BUILDINGS)
                    .executes(ctx -> enter(ctx, null))
                    .then(Commands.argument("copyOf", StringArgumentType.word()).suggests(BUILDINGS)
                        .executes(ctx -> enter(ctx, StringArgumentType.getString(ctx, "copyOf"))))))
            .then(Commands.literal("goto")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(BUILDINGS)
                    .executes(ctx -> walkTo(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))
                    .then(Commands.literal("centre")
                        .executes(ctx -> walkTo(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)))))
            .then(Commands.literal("size")
                .then(sizeAxis("x", Direction.Axis.X))
                .then(sizeAxis("y", Direction.Axis.Y))
                .then(sizeAxis("z", Direction.Axis.Z)))
            .then(Commands.literal("weight")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(BUILDINGS)
                    .then(Commands.literal("inc").executes(ctx -> weight(ctx, +1, false)))
                    .then(Commands.literal("dec").executes(ctx -> weight(ctx, -1, false)))
                    .then(Commands.argument("weight",
                            IntegerArgumentType.integer(BuildingMeta.MIN_WEIGHT, BuildingMeta.MAX_WEIGHT))
                        .executes(ctx -> weight(ctx, IntegerArgumentType.getInteger(ctx, "weight"), true)))))
            .then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(BUILDINGS)
                    .executes(ctx -> delete(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> sizeAxis(String literal, Direction.Axis axis) {
        int max = Buildings.MAX_SIZE.getY();
        return Commands.literal(literal)
            .then(Commands.literal("inc").executes(ctx -> resize(ctx.getSource(), axis, +1)))
            .then(Commands.literal("dec").executes(ctx -> resize(ctx.getSource(), axis, -1)))
            .then(Commands.argument("blocks", IntegerArgumentType.integer(-max, max))
                .executes(ctx -> resize(ctx.getSource(), axis, IntegerArgumentType.getInteger(ctx, "blocks"))));
    }

    private static int list(CommandSourceStack source) {
        String shipped = String.join(", ", BuildingRegistry.names().stream().filter(BuildingStore::isShipped).toList());
        String added = String.join(", ", BuildingWorldgen.newBuildingNames());
        source.sendSuccess(() -> Component.literal("Shipped buildings: " + shipped
            + "\nNew buildings in the roster: " + (added.isEmpty() ? "none yet" : added)), false);
        return 1;
    }

    private static int enter(CommandContext<CommandSourceStack> ctx, String copyOf) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        String name = StringArgumentType.getString(ctx, "name");
        if (!Buildings.NAME.matcher(name).matches()) {
            return fail(source, "Building names are lower-case letters, digits and _ (max 32).");
        }
        if (copyOf != null && !BuildingRegistry.contains(copyOf)) return fail(source, "No building named " + copyOf + ".");
        if (copyOf != null && BuildingRegistry.contains(name)) {
            return fail(source, "A building named " + name + " already exists — pick a new name for the copy.");
        }
        if (!EditorCommand.ensureCategoryResident(source, EditorCategory.BUILDINGS)) return 0;
        BuildingEditor.enter(player, source.getServer().overworld(), name, copyOf);
        boolean shipped = BuildingStore.isShipped(name);
        source.sendSuccess(() -> Component.literal(shipped
            ? "Editing '" + name + "'. Saving replaces it in the Lost City and WWOO stretch of your worlds."
            : "Building '" + name + "'. The bottom layer is its pad; save to add it to the Lost City and WWOO stretch."),
            false);
        return 1;
    }

    private static int walkTo(CommandSourceStack source, String name, boolean centre) {
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        if (!BuildingRegistry.contains(name)) return fail(source, "No building named " + name + ".");
        if (!EditorCommand.ensureCategoryResident(source, EditorCategory.BUILDINGS)) return 0;
        BuildingEditor.walkTo(player, source.getServer().overworld(), name, centre);
        return 1;
    }

    /** Save {@code named}, or the building the player stands in (or last entered) when it is null. */
    private static int save(CommandSourceStack source, String named) {
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        if (named != null && !BuildingRegistry.contains(named)) return fail(source, "No building named " + named + ".");
        Optional<String> name = named != null ? Optional.of(named) : BuildingEditor.current(player);
        if (name.isEmpty()) return fail(source, "Stand in a building plot, or enter one first.");
        try {
            boolean toSource = BuildingEditor.save(player, source.getServer().overworld(), name.get());
            source.sendSuccess(() -> Component.literal("Saved building '" + name.get() + "'"
                + (toSource ? " (and to the source tree)." : ". New Lost City chunks will place it.")), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Building save failed", e);
            return fail(source, "Save failed: " + e.getMessage());
        }
    }

    private static int resize(CommandSourceStack source, Direction.Axis axis, int delta) {
        ServerPlayer player = EditorCommand.playerOrNull(source);
        if (player == null) return 0;
        Optional<String> name = BuildingEditor.current(player);
        if (name.isEmpty()) return fail(source, "Stand in a building plot, or enter one first.");
        Vec3i size = BuildingEditor.resize(source.getServer().overworld(), name.get(), axis, delta);
        source.sendSuccess(() -> Component.literal("'" + name.get() + "' is now " + size.getX() + " x " + size.getY()
            + " x " + size.getZ() + " (unsaved)."), false);
        return 1;
    }

    private static int weight(CommandContext<CommandSourceStack> ctx, int value, boolean absolute) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (!BuildingRegistry.contains(name)) return fail(source, "No building named " + name + ".");
        try {
            int target = absolute ? value : BuildingMeta.load(name).weight() + value;
            int set = BuildingEditor.setWeight(source.getServer().overworld(), name, target);
            source.sendSuccess(() -> Component.literal("'" + name + "' weight " + set + "."), false);
            return 1;
        } catch (IOException e) {
            return fail(source, e.getMessage());
        }
    }

    private static int delete(CommandSourceStack source, String name) {
        if (!BuildingRegistry.contains(name)) return fail(source, "No building named " + name + ".");
        try {
            boolean gone = BuildingEditor.delete(source.getServer().overworld(), name, EditorDevMode.isEnabled());
            if (!gone) {
                return fail(source, "'" + name + "' ships with the mod and you haven't changed it — nothing to delete.");
            }
            source.sendSuccess(() -> Component.literal(BuildingStore.isShipped(name)
                ? "'" + name + "' is back to the shipped building." : "Deleted building '" + name + "'."), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Building delete failed for {}", name, e);
            return fail(source, "Delete failed: " + e.getMessage());
        }
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
        return 0;
    }
}
