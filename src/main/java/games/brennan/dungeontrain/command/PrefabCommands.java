package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.block.prefab.PrefabAnchorBlockEntity;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.PrefabEditor;
import games.brennan.dungeontrain.editor.PrefabTemplateStore;
import games.brennan.dungeontrain.registry.ModBlockEntities;
import games.brennan.dungeontrain.registry.ModBlocks;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;

/**
 * {@code /dt editor prefabs …} — the prefab category's own verbs.
 *
 * <pre>
 *   prefabs                      enter the category
 *   prefabs enter &lt;name&gt;         teleport to one prefab's plot
 *   prefabs new &lt;name&gt; [L H W]   create an empty prefab (default 3×3×3, or the standing plot's size)
 *   prefabs size &lt;L&gt; &lt;H&gt; &lt;W&gt;     resize the plot the player is standing in
 *   prefabs anchor &lt;name&gt;        hand the player an anchor already bound to a prefab
 *   prefabs delete &lt;name&gt;        remove a prefab and its sidecars
 * </pre>
 *
 * <p>Kept out of {@link EditorCommand} so the handlers sit together rather than a thousand lines
 * apart in that file. Weight / gate / rename still reach a prefab through the generic track-side
 * verbs ({@code /dt editor tracks rename prefab <name> <new>}) because a prefab is a
 * {@link TrackKind} under the hood.</p>
 */
final class PrefabCommands {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_AXIS = 64;

    private static final SuggestionProvider<CommandSourceStack> NAME_SUGGESTIONS = (ctx, builder) -> {
        for (String n : TrackVariantRegistry.namesFor(TrackKind.PREFAB)) builder.suggest(n);
        return builder.buildFuture();
    };

    private PrefabCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("prefabs")
            .executes(ctx -> EditorCommand.runEnterCategory(ctx.getSource(), EditorCategory.PREFABS))
            .then(Commands.literal("enter")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(NAME_SUGGESTIONS)
                    .executes(ctx -> runEnter(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("new")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> runNew(ctx.getSource(), StringArgumentType.getString(ctx, "name"), null))
                    .then(Commands.argument("length", IntegerArgumentType.integer(1, MAX_AXIS))
                        .then(Commands.argument("height", IntegerArgumentType.integer(1, MAX_AXIS))
                            .then(Commands.argument("width", IntegerArgumentType.integer(1, MAX_AXIS))
                                .executes(ctx -> runNew(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "name"),
                                    new Vec3i(IntegerArgumentType.getInteger(ctx, "length"),
                                        IntegerArgumentType.getInteger(ctx, "height"),
                                        IntegerArgumentType.getInteger(ctx, "width")))))))))
            .then(Commands.literal("size")
                .then(Commands.argument("length", IntegerArgumentType.integer(1, MAX_AXIS))
                    .then(Commands.argument("height", IntegerArgumentType.integer(1, MAX_AXIS))
                        .then(Commands.argument("width", IntegerArgumentType.integer(1, MAX_AXIS))
                            .executes(ctx -> runSize(ctx.getSource(),
                                new Vec3i(IntegerArgumentType.getInteger(ctx, "length"),
                                    IntegerArgumentType.getInteger(ctx, "height"),
                                    IntegerArgumentType.getInteger(ctx, "width"))))))))
            .then(Commands.literal("anchor")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(NAME_SUGGESTIONS)
                    .executes(ctx -> runAnchor(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(NAME_SUGGESTIONS)
                    .executes(ctx -> EditorCommand.runTrackResetNamedVariant(ctx.getSource(),
                        TrackKind.PREFAB.id(), StringArgumentType.getString(ctx, "name"), null))));
    }

    private static int runEnter(CommandSourceStack source, String raw) {
        ServerPlayer player = EditorCommand.requirePlayer(source);
        if (player == null) return 0;
        String name = raw.toLowerCase(Locale.ROOT);
        if (TrackVariantRegistry.find(TrackKind.PREFAB, name).isEmpty()) {
            source.sendFailure(Component.literal("Unknown prefab '" + raw + "'."));
            return 0;
        }
        if (!EditorCommand.ensureCategory(source, EditorCategory.PREFABS)) return 0;
        PrefabEditor.enter(player, name);
        source.sendSuccess(() -> Component.literal("Editor: prefab '" + name + "'."), true);
        return 1;
    }

    private static int runNew(CommandSourceStack source, String raw, Vec3i requested) {
        ServerPlayer player = EditorCommand.requirePlayer(source);
        if (player == null) return 0;
        String name = raw.toLowerCase(Locale.ROOT);
        if (!TrackVariantRegistry.NAME_PATTERN.matcher(name).matches()) {
            source.sendFailure(Component.literal("Invalid prefab name '" + raw
                + "'. Allowed: lowercase letters, digits, underscore (1..32 chars)."));
            return 0;
        }
        if (TrackKind.DEFAULT_NAME.equals(name)) {
            source.sendFailure(Component.literal("'default' is reserved — pick another name."));
            return 0;
        }
        if (TrackVariantRegistry.contains(TrackKind.PREFAB, name) || PrefabTemplateStore.available(name)) {
            source.sendFailure(Component.literal("A prefab named '" + name + "' already exists."));
            return 0;
        }
        if (!EditorCommand.ensureCategory(source, EditorCategory.PREFABS)) return 0;

        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        // No size given: the plot the player is standing in sets it, else the kind's default.
        Vec3i size = requested;
        if (size == null) {
            String standing = PrefabEditor.plotContaining(player.blockPosition(), dims);
            size = standing != null ? PrefabEditor.plotSize(standing) : TrackKind.PREFAB_DEFAULT_SIZE;
        }
        final Vec3i chosen = size;
        try {
            PrefabEditor.createNew(overworld, name, chosen, dims);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] prefab new '{}' failed", name, e);
            source.sendFailure(Component.literal("Create failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
        EditorCommand.teleportToPlot(player, overworld, TrackKind.PREFAB, name, dims);
        source.sendSuccess(() -> Component.literal("Created prefab '" + name + "' ("
            + chosen.getX() + "x" + chosen.getY() + "x" + chosen.getZ() + ") — teleported to the new plot.")
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int runSize(CommandSourceStack source, Vec3i wanted) {
        ServerPlayer player = EditorCommand.requirePlayer(source);
        if (player == null) return 0;
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        String name = PrefabEditor.plotContaining(player.blockPosition(), dims);
        if (name == null) {
            source.sendFailure(Component.literal("Stand in a prefab plot to resize it."));
            return 0;
        }
        Vec3i size = PrefabEditor.setSize(overworld, name, wanted, dims);
        EditorCommand.teleportToPlot(player, overworld, TrackKind.PREFAB, name, dims);
        source.sendSuccess(() -> Component.literal("Prefab '" + name + "' is now "
            + size.getX() + "x" + size.getY() + "x" + size.getZ() + " — save to keep it.")
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** Hand the player a prefab anchor already bound to {@code name}. */
    private static int runAnchor(CommandSourceStack source, String raw) {
        ServerPlayer player = EditorCommand.requirePlayer(source);
        if (player == null) return 0;
        String name = raw.toLowerCase(Locale.ROOT);
        if (!TrackVariantRegistry.contains(TrackKind.PREFAB, name)) {
            source.sendFailure(Component.literal("Unknown prefab '" + raw + "'."));
            return 0;
        }
        ItemStack stack = new ItemStack(ModBlocks.PREFAB_ANCHOR_ITEM.get());
        CompoundTag tag = new CompoundTag();
        tag.putString(PrefabAnchorBlockEntity.TAG_PREFAB, name);
        BlockItem.setBlockEntityData(stack, ModBlockEntities.PREFAB_ANCHOR.get(), tag);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            Component.literal("Prefab Anchor → " + name));
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        source.sendSuccess(() -> Component.literal("Prefab anchor bound to '" + name + "'.")
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
