package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.event.PortalCarriageEvents;
import games.brennan.dungeontrain.cheat.PortalTuningIntegrity;
import games.brennan.dungeontrain.portal.PortalAnchors;
import games.brennan.dungeontrain.portal.PortalBuilder;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageRole;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.portal.PortalCorridorKind;
import games.brennan.dungeontrain.portal.PortalCorridorSize;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
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
 *   <li>{@code test} — spawn a dimensional carriage here and land at its door
 *       ({@link PortalTestCommand}).</li>
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

    /** How far above the player the template capture is stamped, clear of anything they are standing in. */
    private static final int SCRATCH_Y_OFFSET = 40;

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
                // Counted in GROUPS, not carriages: a portal is a whole group (entry, one cart,
                // exit), so 1 means every group holds one. Survival draws one group in <every> by
                // lottery; creative takes it as an exact period instead — see
                // PortalCarriageSelection.rateFor. Twins no longer collide at close rates because
                // each group takes its own Y lane — see PortalCarriageEvents.twinFloorY.
                .then(Commands.argument("every", IntegerArgumentType.integer(1, 64))
                    .executes(ctx -> runCarriage(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "every")))))
            // Put one here and walk into it, without touching the world's stored rate — see
            // PortalTestCommand for why that last part matters.
            .then(PortalTestCommand.build())
            .then(Commands.literal("diagnose").executes(ctx -> runDiagnose(ctx.getSource())))
            .then(Commands.literal("severed")
                .executes(ctx -> runSeveredList(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> runSeveredList(ctx.getSource())))
                .then(Commands.literal("clear").executes(ctx -> runSeveredClear(ctx.getSource()))))
            // Which corridor to capture. Bare defaults to the long one, which is what this command
            // captured before the short kind existed and what most authoring still means.
            .then(Commands.literal("savetemplate")
                .executes(ctx -> runSaveTemplate(ctx.getSource(), PortalCorridorKind.LONG))
                .then(Commands.literal("long")
                    .executes(ctx -> runSaveTemplate(ctx.getSource(), PortalCorridorKind.LONG)))
                .then(Commands.literal("short")
                    .executes(ctx -> runSaveTemplate(ctx.getSource(), PortalCorridorKind.SHORT))))
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
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
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
            source.sendFailure(Component.translatable("chat.dungeontrain.portal.invalid_portal", e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        // The upper copy has to fit under the build height, and the lower one above the floor —
        // the whole trick depends on both copies living in the same chunk columns.
        if (geo.highestBlockY() > level.getMaxBuildHeight() - CEILING_MARGIN) {
            source.sendFailure(Component.translatable("chat.dungeontrain.portal.upper_copy_would_reach", geo.highestBlockY(), level.getMaxBuildHeight())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (floorY < level.getMinBuildHeight() + 1) {
            source.sendFailure(Component.translatable("chat.dungeontrain.portal.too_close_world_floor")
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        PortalBuilder.build(level, geo);
        PortalRegistry.get(level).add(geo);

        LOGGER.info("[DungeonTrain] Built hallway portal originX={} floorY={} originZ={} length={} deltaY={}",
            originX, floorY, originZ, length, deltaY);

        teleportToEntrance(player, level, geo);

        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.built_hallway_portal_corridor", geo.originX(), geo.farDoorX(), geo.midX(), geo.floorYOf(PortalGeometry.COPY_NEAR), geo.floorYOf(PortalGeometry.COPY_FAR)), true);
        return 1;
    }

    private static int runAuto(CommandSourceStack source, int spacing) {
        PortalRegistry.get(source.getLevel()).setAutoSpacing(spacing);

        if (spacing == PortalAnchors.SPACING_OFF) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.auto_spawning_off_portals"), true);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.auto_spawning_every_blocks", spacing), true);
        return 1;
    }

    /**
     * Write the built-in corridor out as {@code user/templates/portal.nbt} so it becomes an ordinary
     * editable variant.
     *
     * <p>The variant registry finds customs by scanning for {@code .nbt} files, so {@code portal} is
     * invisible to the editor until a file exists — this is what creates it. Afterwards
     * {@code /dungeontrain editor carriage portal} opens it like any other variant, and because every
     * corridor is stamped from that one template, an edit lands on the carriage and its twin
     * alike.</p>
     *
     * <p>Captured from a scratch stamp well above the player rather than from a live carriage: a
     * carriage's blocks live in a Sable sub-level, not the world, so {@code fillFromWorld} cannot see
     * them.</p>
     */
    private static int runSaveTemplate(CommandSourceStack source, PortalCorridorKind kind) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }

        ServerLevel level = source.getLevel();
        CarriageDims dims = DungeonTrainWorldData.get(source.getServer().overworld()).dims();

        BlockPos scratch = new BlockPos(
            player.blockPosition().getX(),
            Math.min(player.blockPosition().getY() + SCRATCH_Y_OFFSET,
                level.getMaxBuildHeight() - dims.height() - 2),
            player.blockPosition().getZ());

        try {
            PortalCarriageBuilder.stampBuiltInForCapture(level, scratch, dims, kind);

            // This KIND's corridor box, not the carriage's — a LONG corridor runs past its slot into
            // the cart between a portal's pair, and capturing dims.length() would save a truncated
            // one that the size gate then rejects, silently dropping every corridor back to the
            // built-in.
            CarriageDims corridor = PortalCorridorSize.corridorDims(dims, kind);
            StructureTemplate template = new StructureTemplate();
            template.fillFromWorld(level, scratch,
                new Vec3i(corridor.length(), corridor.height(), corridor.width()),
                /*withEntities*/ false, /*toIgnore*/ null);

            CarriageVariant variant = PortalCarriageBuilder.portalVariant(kind);
            CarriageTemplateStore.save(variant, template);
            CarriageVariantRegistry.reload();

            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.saved_edit_it_with", CarriageTemplateStore.fileFor(variant), variant.id()), true);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] portal savetemplate failed", e);
            source.sendFailure(Component.translatable("chat.dungeontrain.portal.failed_save_portal_template", e.getClass().getSimpleName(), e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        } finally {
            PortalCarriageBuilder.clearBox(level, scratch, dims, kind);
        }
    }

    private static int runCarriage(CommandSourceStack source, int every) {
        PortalRegistry.get(source.getLevel()).setCarriageEvery(every);
        PortalTuningIntegrity.markTuned(source.getLevel());

        if (every == PortalCarriageSelection.CARRIAGE_EVERY_OFF) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.portal_carriages_off_carriages"), true);
            return 1;
        }

        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.entry_corridor_one_cart", (every == 1 ? Component.translatable("chat.dungeontrain.portal.every_group_portal") : Component.translatable("chat.dungeontrain.portal.one_in_groups_portal", every))), true);

        if (PortalCarriageSelection.isGapClamped(every)) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.that_denser_than_group", PortalCarriageSelection.MIN_GROUP_GAP, PortalCarriageSelection.MIN_GROUP_GAP)
                .withStyle(ChatFormatting.YELLOW), false);
        } else if (every > 1) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.survival_never_closer_than", PortalCarriageSelection.MIN_GROUP_GAP, every)
                .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int runList(CommandSourceStack source) {
        PortalRegistry registry = PortalRegistry.get(source.getLevel());
        int spacing = registry.autoSpacing();
        int every = registry.carriageEvery();
        source.sendSuccess(() -> (every == PortalCarriageSelection.CARRIAGE_EVERY_OFF ? Component.translatable("chat.dungeontrain.portal.portal_carriages_off") : Component.translatable("chat.dungeontrain.portal.portal_carriages_1_carriage", every)), false);
        // Says so out loud while it is in force, because otherwise portals arriving on a metronome
        // reads as the "at random" above being broken.
        if (every != PortalCarriageSelection.CARRIAGE_EVERY_OFF
                && PortalCarriageSelection.isAllCreative(source.getLevel())) {
            int creative = PortalCarriageSelection.isDevCreative(source.getLevel())
                    && !registry.isCarriageEverySet()
                ? PortalCarriageSelection.DEV_CREATIVE_EVERY : every;
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.everyone_here_creative_so", creative)
                .withStyle(ChatFormatting.YELLOW), false);
        }
        source.sendSuccess(() -> (spacing == PortalAnchors.SPACING_OFF ? Component.translatable("chat.dungeontrain.portal.auto_spawning_off") : Component.translatable("chat.dungeontrain.portal.auto_spawning_every_blocks_2", spacing)), false);

        List<PortalGeometry> portals = registry.all();
        if (portals.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.no_hallway_portals_built"), false);
            return 0;
        }

        for (int i = 0; i < portals.size(); i++) {
            PortalGeometry geo = portals.get(i);
            final int index = i;
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.x_z_midpoint_x", index, geo.originX(), geo.farDoorX(), geo.originZ(), geo.midX(), geo.floorYOf(PortalGeometry.COPY_NEAR), geo.floorYOf(PortalGeometry.COPY_FAR)), false);
        }
        return portals.size();
    }

    /**
     * Say why the nearest portal is or is not working, from where the caller is standing.
     *
     * <p>The in-game half of {@link games.brennan.dungeontrain.portal.PortalSwapDiagnostics}: that
     * names a refusal <i>after</i> it happens, in the log; this answers the same question while the
     * player is still standing in the corridor, and covers the states that produce no refusal at all
     * because the swap was never attempted — a group culled, a structure never placed, or a stretch of
     * track that was never eligible for a portal in the first place.</p>
     *
     * <p>The report itself is assembled by
     * {@link games.brennan.dungeontrain.event.PortalCarriageEvents#diagnose}, which owns the live
     * state it describes.</p>
     */
    private static int runDiagnose(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.portal.command_must_be_run"));
            return 0;
        }

        List<String> report = PortalCarriageEvents.diagnose(source.getLevel(), player);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.portal_diagnosis")
            .withStyle(ChatFormatting.AQUA), false);
        for (String line : report) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        // Also to the log, where it can be pasted into a bug report alongside the refusal lines the
        // swap itself writes — the two together are the whole picture, and a screenshot of chat is
        // not something anyone can grep.
        LOGGER.info("[DungeonTrain] Portal diagnosis for {}:\n  {}",
            player.getName().getString(), String.join("\n  ", report));
        return report.size();
    }

    /**
     * The pairs whose way in has been broken open, listed by pair key — the group's anchor, which is
     * also its entry corridor's index. One line per pair rather than per corridor, because severing
     * is a pair-level fact: both ends stop taking people in together.
     *
     * <p>Reports both directions explicitly, because "severed" reads as fully dead and it is not —
     * the way out of a severed pair still works, at both ends. It is also not forever: the record is
     * dropped when the rolling window next re-stamps the group, which restores the broken shell.</p>
     */
    private static int runSeveredList(CommandSourceStack source) {
        List<Integer> severed = PortalRegistry.get(source.getLevel()).severed();
        if (severed.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.no_severed_portal_pairs"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.severed_portal_pair_no", severed.size(), Component.translatable(severed.size() == 1 ? "chat.dungeontrain.common.noun.pair.singular" : "chat.dungeontrain.common.noun.pair.plural")), false);
        for (int pairKey : severed) {
            source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.pair_carriages", pairKey, pairKey, PortalCarriageRole.partnerIndex(pairKey, DungeonTrainConfig.getGroupSize())),
                false);
        }
        return severed.size();
    }

    private static int runSeveredClear(CommandSourceStack source) {
        int restored = PortalRegistry.get(source.getLevel()).clearSevered();
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.restored_severed_portal_pair", restored, Component.translatable(restored == 1 ? "chat.dungeontrain.common.noun.pair.singular" : "chat.dungeontrain.common.noun.pair.plural")), true);
        return restored;
    }

    private static int runClear(CommandSourceStack source) {
        int removed = PortalRegistry.get(source.getLevel()).clear();
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.cleared_hallway_portal_blocks", removed, Component.translatable(removed == 1 ? "chat.dungeontrain.common.noun.portal.singular" : "chat.dungeontrain.common.noun.portal.plural")), true);
        return removed;
    }

    private static int runTp(CommandSourceStack source, int index) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return 0;
        }

        ServerLevel level = source.getLevel();
        List<PortalGeometry> portals = PortalRegistry.get(level).all();
        if (portals.isEmpty()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.portal.no_hallway_portals_dimension")
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        int resolved = index < 0 ? portals.size() - 1 : index;
        if (resolved >= portals.size()) {
            source.sendFailure(Component.translatable("chat.dungeontrain.portal.no_portal_there_are", resolved, portals.size())
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        teleportToEntrance(player, level, portals.get(resolved));
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.teleported_hallway_portal", resolved), false);
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
