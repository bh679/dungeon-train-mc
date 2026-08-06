package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalAnchors;
import games.brennan.dungeontrain.portal.PortalBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.portal.PortalGeometry;
import games.brennan.dungeontrain.portal.PortalRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;

/**
 * {@code /dungeontrain portal …} — the prototype harness for the hallway portal system.
 *
 * <ul>
 *   <li>{@code build [length] [deltaY]} — stamps a portal pair starting just ahead of the player
 *       and drops them at its entrance.</li>
 *   <li>{@code list} — the built portals with their midpoint and copy heights, which is what you
 *       compare the F3 readout against while walking through.</li>
 *   <li>{@code tp [index]} — back to a portal's entrance.</li>
 *   <li>{@code clear} — forget every portal. The stamped blocks stay; only the swapping stops.</li>
 * </ul>
 *
 * <p>Registered as a subcommand node from {@link TrainCommand#register}, the same way
 * {@code EditorCommand} is.</p>
 */
public final class PortalCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Corridor length in blocks. Well clear of {@link PortalGeometry#MIN_LENGTH} for a real walk. */
    private static final int DEFAULT_LENGTH = 48;
    /** Vertical separation between the two copies — same chunk columns, so both stay loaded. */
    private static final int DEFAULT_DELTA_Y = 96;
    private static final int DEFAULT_WIDTH = 3;
    private static final int DEFAULT_HEIGHT = 3;

    /** How far ahead of the player (+X) the corridor's near door is placed. */
    private static final int BUILD_AHEAD = 6;

    /** Blocks below the build-height ceiling the upper copy must stay. */
    private static final int CEILING_MARGIN = 4;

    /** Yaw facing +X, the direction the corridor runs. Matches the train's travel direction. */
    private static final float FACE_EAST = -90.0f;

    private PortalCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("portal")
            .then(Commands.literal("build")
                .executes(ctx -> runBuild(ctx.getSource(), DEFAULT_LENGTH, DEFAULT_DELTA_Y))
                .then(Commands.argument("length", IntegerArgumentType.integer(PortalGeometry.MIN_LENGTH, 512))
                    .executes(ctx -> runBuild(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "length"), DEFAULT_DELTA_Y))
                    .then(Commands.argument("deltaY", IntegerArgumentType.integer(16, 384))
                        .executes(ctx -> runBuild(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "length"),
                            IntegerArgumentType.getInteger(ctx, "deltaY"))))))
            .then(Commands.literal("auto")
                .then(Commands.literal("off").executes(ctx -> runAuto(ctx.getSource(), PortalAnchors.SPACING_OFF)))
                .then(Commands.argument("spacing", IntegerArgumentType.integer(PortalAnchors.MIN_SPACING, 100_000))
                    .executes(ctx -> runAuto(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "spacing")))))
            .then(Commands.literal("carriage")
                .then(Commands.literal("off")
                    .executes(ctx -> runCarriage(ctx.getSource(), PortalCarriageSelection.CARRIAGE_EVERY_OFF)))
                // Minimum 3: each twin is its corridor plus a pocket room, about 24 blocks, so
                // portals closer than 3 carriages (27 blocks) apart would stamp twins into each other.
                .then(Commands.argument("every", IntegerArgumentType.integer(3, 64))
                    .executes(ctx -> runCarriage(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "every")))))
            .then(Commands.literal("list").executes(ctx -> runList(ctx.getSource())))
            .then(Commands.literal("clear").executes(ctx -> runClear(ctx.getSource())))
            .then(Commands.literal("tp")
                .executes(ctx -> runTp(ctx.getSource(), -1))
                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                    .executes(ctx -> runTp(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "index")))));
    }

    private static int runBuild(CommandSourceStack source, int length, int deltaY) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        int originX = player.blockPosition().getX() + BUILD_AHEAD;
        int floorY = player.blockPosition().getY() - 1;
        int originZ = player.blockPosition().getZ() - DEFAULT_WIDTH / 2;

        PortalGeometry geo;
        try {
            geo = new PortalGeometry(originX, floorY, originZ, length, DEFAULT_WIDTH, DEFAULT_HEIGHT, deltaY);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid portal: " + e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        // The upper copy has to fit under the build height, and the lower one above the floor —
        // the whole trick depends on both copies living in the same chunk columns.
        if (geo.highestBlockY() > level.getMaxBuildHeight() - CEILING_MARGIN) {
            source.sendFailure(Component.literal(
                "Upper copy would reach Y " + geo.highestBlockY() + ", above the build ceiling ("
                    + level.getMaxBuildHeight() + "). Stand lower or use a smaller deltaY.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (floorY < level.getMinBuildHeight() + 1) {
            source.sendFailure(Component.literal("Too close to the world floor to stamp a corridor here.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        PortalBuilder.build(level, geo);
        PortalRegistry.get(level).add(geo);

        LOGGER.info("[DungeonTrain] Built hallway portal originX={} floorY={} originZ={} length={} deltaY={}",
            originX, floorY, originZ, length, deltaY);

        teleportToEntrance(player, level, geo);

        source.sendSuccess(() -> Component.literal(
            "Built hallway portal: corridor X " + geo.originX() + "→" + geo.farDoorX()
                + ", midpoint X " + geo.midX()
                + ", near copy Y " + geo.floorYOf(PortalGeometry.COPY_NEAR)
                + ", far copy Y " + geo.floorYOf(PortalGeometry.COPY_FAR)
                + ". Walk east through both doors — watch F3's Y."), true);
        return 1;
    }

    private static int runAuto(CommandSourceStack source, int spacing) {
        PortalRegistry.get(source.getLevel()).setAutoSpacing(spacing);

        if (spacing == PortalAnchors.SPACING_OFF) {
            source.sendSuccess(() -> Component.literal(
                "Auto-spawning off. Portals already built stay where they are."), true);
            return 1;
        }

        source.sendSuccess(() -> Component.literal(
            "Auto-spawning every " + spacing + " blocks along +X, beside the track. "
                + "Ride east and they will appear as chunks load."), true);
        return 1;
    }

    private static int runCarriage(CommandSourceStack source, int every) {
        PortalRegistry.get(source.getLevel()).setCarriageEvery(every);

        if (every == PortalCarriageSelection.CARRIAGE_EVERY_OFF) {
            source.sendSuccess(() -> Component.literal(
                "Portal carriages off. Carriages already stamped keep their corridor until the "
                    + "rolling window re-places them."), true);
            return 1;
        }

        source.sendSuccess(() -> Component.literal(
            "Every " + every + (every == 1 ? "st" : "th") + " carriage is now a portal corridor. "
                + "Walk the train to find one — the twin is stamped as you approach."), true);
        return 1;
    }

    private static int runList(CommandSourceStack source) {
        PortalRegistry registry = PortalRegistry.get(source.getLevel());
        int spacing = registry.autoSpacing();
        int every = registry.carriageEvery();
        source.sendSuccess(() -> Component.literal(every == PortalCarriageSelection.CARRIAGE_EVERY_OFF
            ? "Portal carriages: off"
            : "Portal carriages: every " + every + " carriages"), false);
        source.sendSuccess(() -> Component.literal(spacing == PortalAnchors.SPACING_OFF
            ? "Auto-spawning: off"
            : "Auto-spawning: every " + spacing + " blocks"), false);

        List<PortalGeometry> portals = registry.all();
        if (portals.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No hallway portals built in this dimension yet."), false);
            return 0;
        }

        for (int i = 0; i < portals.size(); i++) {
            PortalGeometry geo = portals.get(i);
            final int index = i;
            source.sendSuccess(() -> Component.literal(
                "[" + index + "] X " + geo.originX() + "→" + geo.farDoorX()
                    + " Z " + geo.originZ()
                    + " | midpoint X " + geo.midX()
                    + " | near Y " + geo.floorYOf(PortalGeometry.COPY_NEAR)
                    + " far Y " + geo.floorYOf(PortalGeometry.COPY_FAR)), false);
        }
        return portals.size();
    }

    private static int runClear(CommandSourceStack source) {
        int removed = PortalRegistry.get(source.getLevel()).clear();
        source.sendSuccess(() -> Component.literal(
            "Cleared " + removed + " hallway portal" + (removed == 1 ? "" : "s")
                + " (blocks left in place; swapping stops)."), true);
        return removed;
    }

    private static int runTp(CommandSourceStack source, int index) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        List<PortalGeometry> portals = PortalRegistry.get(level).all();
        if (portals.isEmpty()) {
            source.sendFailure(Component.literal("No hallway portals in this dimension.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        int resolved = index < 0 ? portals.size() - 1 : index;
        if (resolved >= portals.size()) {
            source.sendFailure(Component.literal(
                "No portal " + resolved + " — there are " + portals.size() + ".")
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        teleportToEntrance(player, level, portals.get(resolved));
        source.sendSuccess(() -> Component.literal("Teleported to hallway portal " + resolved + "."), false);
        return 1;
    }

    /** Drop the player in the open-air approach, facing the near door down the corridor. */
    private static void teleportToEntrance(ServerPlayer player, ServerLevel level, PortalGeometry geo) {
        player.teleportTo(level,
            geo.originX() - 2 + 0.5,
            geo.floorYOf(PortalGeometry.COPY_NEAR) + 1,
            geo.doorZ() + 0.5,
            FACE_EAST, 0.0f);
    }
}
