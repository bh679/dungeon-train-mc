package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalTestSessionPacket;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.portal.PortalTwinLanes;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageTestSession;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.ContentsShellPicker;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.WholeCarriageEditor;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupPlacer;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.train.StagePlacementScope;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.train.WholeKind;
import games.brennan.dungeontrain.train.WholeOverlay;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Optional;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackTestBand;
import games.brennan.dungeontrain.track.TrackTestLayout;
import games.brennan.dungeontrain.track.TrackTestPiece;
import games.brennan.dungeontrain.track.TrackTestScene;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageGenerationConfig;
import java.util.Arrays;
import java.util.List;

/**
 * {@code /dungeontrain editor test carriages|contents <id>} — Test the Carriage for a carriage or a
 * contents template: stand on a flatbed facing two copies of it, rolled the way the train rolls a
 * carriage — the second on a different seed, so two rolls can be compared side by side. A whole
 * group already spans several carriages, so it is stood up once, between two flatbeds. Laid out
 * along {@code +X}:
 *
 * <pre>
 *   carriage / contents / whole room:  [flatbed | roll A | roll B]
 *   whole group:                       [flatbed | group  | flatbed]
 * </pre>
 *
 * <p>The same shape as {@link PortalTestCommand}, for the same reasons: <b>no train.</b> The copy is
 * ordinary world blocks in the sealed basement under the world, so nothing about it can fail the way
 * a Sable sub-level can, and the question it answers — "does this look right when a player meets
 * it" — needs none of that machinery.</p>
 *
 * <ul>
 *   <li><b>A carriage</b> is stood up with whatever contents the train would put in it: the pick
 *       runs through its own contents allow-list and the template weights, so a shell that only ever
 *       carries a library is tested holding a library.</li>
 *   <li><b>Contents</b> are stood up inside a carriage that has them enabled, drawn by carriage
 *       weight — never a shell the train would not put them in. A group parent
 *       rolls one of its members exactly as a carriage would; naming a member tests that member.</li>
 * </ul>
 *
 * <p>Both roll block variants, loot and mob cells at {@link CarriageTestSession#TEST_INDEX} with the
 * world's seed — salted afresh when the world's reseed-on-test switch is on, or when the author asks
 * for a reseed, the switch the dimensional-carriage test already uses.</p>
 */
public final class CarriageTestCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How far off the track band the copy is stamped, in blocks of {@code +Z}. Clear of the track for
     * the reason {@link PortalTestCommand} gives, and clear of that command's own band too, so the
     * two tests can never be stamped over each other.
     */
    private static final int TEST_Z_OFFSET = 640;

    /** Facing +X, down the carriage, the way a player walks through one on the train. */
    private static final float FACE_EAST = -90.0f;

    private CarriageTestCommand() {}

    /** The {@code test} node under {@code /dungeontrain editor}. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("test")
            .then(Commands.literal(CarriageTestSession.Kind.CARRIAGE.literal())
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                        CarriageVariantRegistry.allVariants().stream().map(CarriageVariant::id), b))
                    .executes(ctx -> runTest(ctx.getSource(), CarriageTestSession.Kind.CARRIAGE,
                        StringArgumentType.getString(ctx, "name"), false))))
            .then(Commands.literal(CarriageTestSession.Kind.CONTENTS.literal())
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                        CarriageContentsRegistry.allContents().stream().map(CarriageContents::id), b))
                    .executes(ctx -> runTest(ctx.getSource(), CarriageTestSession.Kind.CONTENTS,
                        StringArgumentType.getString(ctx, "name"), false))))
            .then(Commands.literal(CarriageTestSession.Kind.WHOLE.literal())
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(WholeCarriageRegistry.ids(), b))
                    .executes(ctx -> runTest(ctx.getSource(), CarriageTestSession.Kind.WHOLE,
                        StringArgumentType.getString(ctx, "name"), false))))
            .then(Commands.literal(CarriageTestSession.Kind.WHOLE_GROUP.literal())
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(CarriageGroupRegistry.ids(), b))
                    .executes(ctx -> runTest(ctx.getSource(), CarriageTestSession.Kind.WHOLE_GROUP,
                        StringArgumentType.getString(ctx, "name"), false))))
            .then(Commands.literal(CarriageTestSession.Kind.TRACKS.literal())
                // A whole tunnel group: every section and entrance drawn from it.
                .then(Commands.literal("tunnelgroup")
                    .then(Commands.argument("group", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            java.util.List<String> ids = new java.util.ArrayList<>(
                                games.brennan.dungeontrain.tunnel.TunnelGroupEditing.snapshot().ids());
                            ids.add(games.brennan.dungeontrain.tunnel.TunnelGroupEditing.UNGROUPED_TOKEN);
                            return SharedSuggestionProvider.suggest(ids, b);
                        })
                        .executes(ctx -> runTunnelGroupCommand(ctx.getSource(),
                            StringArgumentType.getString(ctx, "group")))))
                .then(Commands.argument("model", StringArgumentType.word())
                    .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                        Arrays.stream(TrackTestPiece.values()).map(TrackTestPiece::modelId), b))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                            TrackTestPiece.ofModelId(StringArgumentType.getString(ctx, "model"))
                                .map(p -> TrackVariantRegistry.namesFor(p.kind()))
                                .orElse(List.of()), b))
                        .executes(ctx -> runTrackCommand(ctx.getSource(),
                            StringArgumentType.getString(ctx, "model"),
                            StringArgumentType.getString(ctx, "name"))))))
            .then(Commands.literal("back").executes(ctx -> runBack(ctx.getSource())))
            .then(Commands.literal("reseed").executes(ctx -> runReseedNow(ctx.getSource(), false))
                .then(Commands.literal("focus").executes(ctx -> runReseedNow(ctx.getSource(), true))));
    }

    /** What a test stands up: the shell, and what goes in it. */
    private record Plan(CarriageVariant shell, CarriageContents contents) {}

    /**
     * @param freshRoll salt the rolls whatever the world switch says — a reseed of the copy the
     *                  author is already standing in
     */
    static int runTest(CommandSourceStack source, CarriageTestSession.Kind kind, String id,
                       boolean freshRoll) {
        return runTest(source, kind, id, freshRoll, false);
    }

    /**
     * @param focus re-roll only the template under test, keeping the rest of the copy: a carriage
     *              test keeps its contents' seed, a contents test keeps its carriage's
     */
    static int runTest(CommandSourceStack source, CarriageTestSession.Kind kind, String id,
                       boolean freshRoll, boolean focus) {
        ServerPlayer player = playerOf(source);
        if (player == null) return 0;

        ServerLevel overworld = source.getServer().overworld();
        DungeonTrainWorldData worldData = DungeonTrainWorldData.get(overworld);
        CarriageDims dims = worldData.dims();

        // Resolved before anything else moves: a request that is refused (an unknown id, a portal
        // part) must leave the author standing in whatever test they are already in.
        long shellSeed = seedFor(worldData, overworld, freshRoll);
        long contentsSeed = seedFor(worldData, overworld, freshRoll);
        CarriageTestSession.Session current = CarriageTestSession.get(player.getUUID());
        if (focus && current != null) {
            // The other half keeps the seed it stood on; only the tested template rolls afresh.
            // A track test's shell seed is the piece's roll and its contents seed the line's, so
            // it keeps the line the way a carriage test keeps its contents.
            if (kind == CarriageTestSession.Kind.CARRIAGE || kind == CarriageTestSession.Kind.TRACKS) {
                contentsSeed = current.contentsSeed();
            } else {
                shellSeed = current.shellSeed();
            }
        }
        if (kind.isWhole()) return runWholeTest(source, player, overworld, kind, id, shellSeed, contentsSeed);
        if (kind == CarriageTestSession.Kind.TRACKS) {
            return runTrackTest(source, player, overworld, worldData, id, shellSeed, contentsSeed);
        }
        Plan plan = planFor(source, kind, id, shellSeed, contentsSeed);
        if (plan == null) return 0;
        // The second copy: the same template on the next roll. Derived from the first, so a reseed
        // re-rolls both and a focus reseed keeps the untested half of both.
        long shellSeedB = nextRoll(shellSeed);
        long contentsSeedB = nextRoll(contentsSeed);
        Plan planB = planFor(source, kind, id, shellSeedB, contentsSeedB);
        if (planB == null) return 0;

        leaveCurrentTests(source, player);

        BlockPos origin = new BlockPos(player.blockPosition().getX(),
            PortalTwinLanes.floorY(overworld.getMinBuildHeight()), TEST_Z_OFFSET);
        CarriageDims dimsA = CarriagePlacer.variantDims(plan.shell(), dims);
        CarriageDims dimsB = CarriagePlacer.variantDims(planB.shell(), dims);
        BlockPos originA = origin.offset(dims.length(), 0, 0);
        BlockPos originB = originA.offset(dimsA.length(), 0, 0);
        BoundingBox box = spanBox(origin, dims.length() + dimsA.length() + dimsB.length(),
            Math.max(dims.height(), Math.max(dimsA.height(), dimsB.height())),
            Math.max(dims.width(), Math.max(dimsA.width(), dimsB.width())));
        GameType previous = player.gameMode.getGameModeForPlayer();
        // Registered BEFORE the stamp: the mob cells ask CarriageTestSession.isTestStamp while they
        // are placed, and a test is the one carriage stamp that spawns its hostiles as authored.
        CarriageTestSession.put(player.getUUID(), new CarriageTestSession.Session(
            player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
            previous, kind, id, box, shellSeed, contentsSeed));

        stampFlatbed(overworld, origin, dims, shellSeed);
        CarriagePlacer.placeForTest(overworld, originA, plan.shell(), plan.contents(), dims, shellSeed,
            contentsSeed, CarriageTestSession.TEST_INDEX, /*flatbedAtBack*/ true, /*flatbedAtFront*/ false);
        CarriagePlacer.placeForTest(overworld, originB, planB.shell(), planB.contents(), dims, shellSeedB,
            contentsSeedB, CarriageTestSession.TEST_INDEX, /*flatbedAtBack*/ false, /*flatbedAtFront*/ false);

        arrive(overworld, player, origin, sizeOf(dims), previous, id);

        String contentsId = plan.contents() == null ? "none" : plan.contents().id();
        String contentsIdB = planB.contents() == null ? "none" : planB.contents().id();
        LOGGER.info("[DungeonTrain] carriage test: stamped {} '{}' behind a flatbed at {} for {} — "
                + "A (shell={}, contents={}, seeds={}/{}), B (shell={}, contents={}, seeds={}/{})",
            kind.literal(), id, origin, player.getName().getString(),
            plan.shell().id(), contentsId, shellSeed, contentsSeed,
            planB.shell().id(), contentsIdB, shellSeedB, contentsSeedB);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.carriage_test.standing_in",
            id, plan.shell().id(), contentsId).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Session template id prefix for a whole-group test — {@code tunnel_group:<id|ungrouped>}. */
    static final String TUNNEL_GROUP_PREFIX = "tunnel_group:";

    /** {@code editor test tracks tunnelgroup <id|ungrouped>}: refuse a group with no section to build from. */
    private static int runTunnelGroupCommand(CommandSourceStack source, String raw) {
        String token = raw.toLowerCase(java.util.Locale.ROOT);
        if (firstSectionOf(token) == null) {
            source.sendFailure(Component.literal("Tunnel group '" + token + "' has no tunnel section to test.")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        return runTest(source, CarriageTestSession.Kind.TRACKS, TUNNEL_GROUP_PREFIX + token, false);
    }

    /** {@code token} ({@code ungrouped} = the ungrouped pool) as the group a test tunnel is forced to. */
    private static games.brennan.dungeontrain.template.TemplateGroup groupOf(String token) {
        return games.brennan.dungeontrain.tunnel.TunnelGroupEditing.UNGROUPED_TOKEN.equals(token)
            ? games.brennan.dungeontrain.template.TemplateGroup.UNGROUPED
            : games.brennan.dungeontrain.template.TemplateGroup.of(token);
    }

    /** The first tunnel section in the group named by {@code token}, or null when it has none. */
    private static String firstSectionOf(String token) {
        games.brennan.dungeontrain.template.TemplateGroup group = groupOf(token);
        for (String n : games.brennan.dungeontrain.editor.TrackVariantGroupStore.topLevelNames(
                games.brennan.dungeontrain.track.variant.TrackKind.TUNNEL_SECTION)) {
            if (group.matches(TrackVariantWeights.groupsFor(
                    games.brennan.dungeontrain.track.variant.TrackKind.TUNNEL_SECTION, n))) return n;
        }
        return null;
    }

    /** {@code editor test tracks <model> <name>}: refuse a model id that is not a piece of the line. */
    private static int runTrackCommand(CommandSourceStack source, String modelId, String name) {
        Optional<TrackTestPiece> piece = TrackTestPiece.ofModelId(modelId);
        if (piece.isEmpty()) return failCode(source, "chat.dungeontrain.track_test.unknown_piece", modelId);
        // A name the kind doesn't have would stamp the built-in fallback and look like a test of it.
        List<String> names = TrackVariantRegistry.namesFor(piece.get().kind());
        if (!names.contains(name)) {
            source.sendFailure(Component.translatable("chat.dungeontrain.editor.unknown_variant_valid",
                name, String.join(", ", names)).withStyle(ChatFormatting.RED));
            return 0;
        }
        return runTest(source, CarriageTestSession.Kind.TRACKS, piece.get().templateId(name), false);
    }

    /**
     * A piece of the line, in context: two rolls of a stretch of track — columns, a staircase, a
     * tunnel with a shaft — with a carriage standing on the first between half flatbeds, the way a
     * one-carriage sub-level stands on the real line. The author arrives on the back pad facing it.
     *
     * @param testSeed  what the piece under test rolls its block variants on
     * @param sceneSeed what everything else — the line's other pieces and the carriage — rolls on
     */
    private static int runTrackTest(CommandSourceStack source, ServerPlayer player, ServerLevel overworld,
                                    DungeonTrainWorldData worldData, String id, long testSeed, long sceneSeed) {
        // A whole-group test builds its tunnel from the group alone; the section piece stands in for
        // the layout, and the group's first section for the band and the log.
        games.brennan.dungeontrain.template.TemplateGroup forced = null;
        TrackTestPiece piece;
        String name;
        if (id.startsWith(TUNNEL_GROUP_PREFIX)) {
            String token = id.substring(TUNNEL_GROUP_PREFIX.length());
            name = firstSectionOf(token);
            if (name == null) return failCode(source, "chat.dungeontrain.track_test.unknown_piece", id);
            forced = groupOf(token);
            piece = TrackTestPiece.TUNNEL_SECTION;
        } else {
            Optional<TrackTestPiece.Named> named = TrackTestPiece.parseTemplateId(id);
            if (named.isEmpty()) return failCode(source, "chat.dungeontrain.track_test.unknown_piece", id);
            piece = named.get().piece();
            name = named.get().name();
        }
        final games.brennan.dungeontrain.template.TemplateGroup forcedGroup = forced;

        // A band the piece could really appear in, drawn with the line around it: a reseed can land
        // in another, a focused one keeps it with the line.
        games.brennan.dungeontrain.template.TemplateGate bandGate = TrackVariantWeights.gateFor(piece.kind(), name);
        if (forcedGroup != null && !forcedGroup.isUngrouped()) {
            // A gated group is tested where it can spawn: its own gate decides the band.
            games.brennan.dungeontrain.template.TemplateGate groupGate =
                games.brennan.dungeontrain.tunnel.TunnelGroupStore.current().gateOf(forcedGroup.id());
            if (!groupGate.isDefault()) bandGate = groupGate;
        }
        TrackTestBand band = TrackTestBand.pick(bandGate, sceneSeed);
        CarriageDims dims = worldData.dims();
        CarriageGenerationConfig config = worldData.getGenerationConfig();
        CarriageVariant shell = CarriagePlacer.enclosedVariantForIndex(CarriageTestSession.TEST_INDEX,
            new CarriageGenerationConfig(config.mode(), config.groupSize(), sceneSeed), band.context());
        CarriageContents contents = contentsFor(shell, sceneSeed, band.context());
        CarriageDims shellDims = CarriagePlacer.variantDims(shell, dims);
        int spacing = TrackGenerator.computeSpacing(TrackTestLayout.COLUMN_HEIGHT);
        TrackTestLayout layout = new TrackTestLayout(shellDims.length(), CarriagePlacer.halfPadLen(dims),
            shellDims.height(), dims.width(), spacing, TrackGenerator.computeThickness(spacing));

        leaveCurrentTests(source, player);

        // On the tile grid, so the tiles' block variants key the way the line's own do.
        int cornerX = Math.floorDiv(player.blockPosition().getX(), TrackTestLayout.TILE_LENGTH)
            * TrackTestLayout.TILE_LENGTH;
        BlockPos corner = new BlockPos(cornerX,
            PortalTwinLanes.floorY(overworld.getMinBuildHeight()) + 1, TEST_Z_OFFSET);
        BoundingBox box = new BoundingBox(
            corner.getX(), corner.getY() - 1, corner.getZ() + TrackTestLayout.minZ(),
            corner.getX() + layout.sceneLength() - 1, corner.getY() + layout.topY(),
            corner.getZ() + layout.maxZ());
        GameType previous = player.gameMode.getGameModeForPlayer();
        CarriageTestSession.put(player.getUUID(), new CarriageTestSession.Session(
            player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
            previous, CarriageTestSession.Kind.TRACKS, id, box, testSeed, sceneSeed));

        CarriageStampGuard.run(() -> {
            TrackTestScene.stampStretch(overworld, corner, dims, layout,
                new TrackTestScene.Roll(piece, name, testSeed, sceneSeed, band, forcedGroup));
            TrackTestScene.stampStretch(overworld, corner.offset(layout.stretchLength(), 0, 0), dims, layout,
                new TrackTestScene.Roll(piece, name, nextRoll(testSeed), nextRoll(sceneSeed), band, forcedGroup));
        });
        // The train stands on the section under test, so arriving on its back pad starts the author there.
        BlockPos backPad = corner.offset(layout.backPadX(piece), TrackTestLayout.trainY(), 0);
        stampCarriageOnTrack(overworld, corner, layout, piece, dims, shell, contents, sceneSeed);

        arrive(overworld, player, backPad, new Vec3i(layout.halfPad(), dims.height(), dims.width()), previous, name);

        String contentsId = contents == null ? "none" : contents.id();
        LOGGER.info("[DungeonTrain] track test: stamped {} '{}' at {} for {} — carriage {} (contents={}), "
                + "band={} (level {}), seeds={}/{}", piece.modelId(), name, corner, player.getName().getString(),
            shell.id(), contentsId, band.phase(), band.level(), testSeed, sceneSeed);
        Component bandName = Component.translatable(
            "gui.dungeontrain.editor_menu.phase." + band.phase().name().toLowerCase(java.util.Locale.ROOT));
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.track_test.standing_in", name, bandName)
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /**
     * The carriage between its two half pads, standing on the line's rails — the layout a
     * one-carriage sub-level has on the train ({@code TrainAssembler}), so the author sees the
     * clearance a real carriage gets.
     */
    private static void stampCarriageOnTrack(ServerLevel level, BlockPos corner, TrackTestLayout layout,
                                             TrackTestPiece piece, CarriageDims dims, CarriageVariant shell,
                                             CarriageContents contents, long seed) {
        int y = TrackTestLayout.trainY();
        BlockPos backPad = corner.offset(layout.backPadX(piece), y, 0);
        BlockPos frontPad = corner.offset(layout.frontPadX(piece), y, 0);
        String stage = games.brennan.dungeontrain.editor.EditorStageSelection.effective();
        CarriageStampGuard.run(() -> StagePlacementScope.run(stage, () -> {
            CarriagePlacer.placeHalfFlatbedPad(level, backPad, CarriagePlacer.HalfPadSide.BACK, dims);
            CarriagePlacer.placeHalfFlatbedPad(level, frontPad, CarriagePlacer.HalfPadSide.FRONT, dims);
        }));
        CarriagePlacer.placeForTest(level, corner.offset(layout.carriageX(piece), y, 0), shell, contents, dims, seed,
            seed, CarriageTestSession.TEST_INDEX, /*flatbedAtBack*/ true, /*flatbedAtFront*/ true);
    }

    /**
     * Already inside a test — of any kind: stamping a second would leave the first standing and lose
     * the way home. Send them back first, then in again, so the button is idempotent. A
     * dimensional-carriage press still waiting on its chunk sample is superseded too — the author
     * should not be pulled into that room a moment later.
     */
    private static void leaveCurrentTests(CommandSourceStack source, ServerPlayer player) {
        if (CarriageTestSession.has(player.getUUID())) runBack(source);
        if (PortalTestSession.has(player.getUUID())) PortalTestCommand.runBack(source);
        games.brennan.dungeontrain.portal.PortalTestPending.cancel(player.getUUID());
    }

    /** Creative, into the copy facing down it, and tell the client a test is running. */
    private static void arrive(ServerLevel overworld, ServerPlayer player, BlockPos origin, Vec3i size,
                               GameType previous, String id) {
        BlockPos arrival = findArrival(overworld, player, origin, size);
        if (previous != GameType.CREATIVE) player.setGameMode(GameType.CREATIVE);
        player.teleportTo(overworld, arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5,
            FACE_EAST, 0.0f);
        DungeonTrainNet.sendTo(player, new PortalTestSessionPacket(true, id,
            DungeonTrainWorldData.get(overworld).isPortalTestReseed()));
    }

    /** A carriage footprint as a size: x = length, y = height, z = width. */
    private static Vec3i sizeOf(CarriageDims dims) {
        return new Vec3i(dims.length(), dims.height(), dims.width());
    }

    /**
     * Test the Carriage for a whole room or group: the saved template stood up in the basement and
     * rolled the way the train rolls one — its block variants and container loot through
     * {@link WholeOverlay} at {@link CarriageTestSession#TEST_INDEX}. A whole template has no shell
     * or contents pass, so the shell seed is the only one that matters; a focus reseed is a full one.
     *
     * <p>Stamped with the editor's {@code placeAt} rather than the train's {@code placeForTrain}: the
     * test copy is never lifted onto Sable, so it needs relighting and its saved decor and entities
     * put back now, both of which the train defers to the lift.</p>
     */
    private static int runWholeTest(CommandSourceStack source, ServerPlayer player, ServerLevel overworld,
                                    CarriageTestSession.Kind kind, String id, long shellSeed, long contentsSeed) {
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        boolean room = kind == CarriageTestSession.Kind.WHOLE;
        Vec3i size;
        WholeKind wholeKind = room ? WholeKind.ROOM : WholeKind.GROUP;
        java.util.function.Predicate<BlockPos> place;
        if (room) {
            Optional<WholeCarriage> wc = WholeCarriageRegistry.find(id);
            if (wc.isEmpty()) return failCode(source, "chat.dungeontrain.editor.unknown_whole", id);
            if (WholeCarriageTemplateStore.get(overworld, wc.get(), dims).isEmpty()) {
                return failCode(source, "chat.dungeontrain.carriage_test.no_whole_template", id);
            }
            size = sizeOf(dims);
            place = at -> WholeCarriagePlacer.placeAt(overworld, at, wc.get(), dims);
        } else {
            Optional<CarriageGroup> group = CarriageGroupRegistry.find(id);
            if (group.isEmpty()) return failCode(source, "chat.dungeontrain.editor.unknown_whole_group", id);
            int carriages = WholeCarriageEditor.groupSize();
            if (CarriageGroupTemplateStore.carriagesIn(overworld, group.get(), dims) != carriages) {
                return failCode(source, "chat.dungeontrain.carriage_test.no_whole_template", id);
            }
            size = CarriageGroupPlacer.sizeOf(dims, carriages);
            place = at -> CarriageGroupPlacer.placeAt(overworld, at, group.get(), dims, carriages);
        }

        leaveCurrentTests(source, player);

        // A room, like a carriage: [flatbed | roll A | roll B]. A group already spans several
        // carriages, so it stands once between flatbeds: [flatbed | group | flatbed].
        BlockPos origin = new BlockPos(player.blockPosition().getX(),
            PortalTwinLanes.floorY(overworld.getMinBuildHeight()), TEST_Z_OFFSET);
        BlockPos wholeOrigin = origin.offset(dims.length(), 0, 0);
        BlockPos after = wholeOrigin.offset(size.getX(), 0, 0);
        long seedB = nextRoll(shellSeed);
        BoundingBox box = spanBox(origin, dims.length() + size.getX() + (room ? size.getX() : dims.length()),
            Math.max(dims.height(), size.getY()), Math.max(dims.width(), size.getZ()));
        GameType previous = player.gameMode.getGameModeForPlayer();
        CarriageTestSession.put(player.getUUID(), new CarriageTestSession.Session(
            player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
            previous, kind, id, box, shellSeed, contentsSeed));

        boolean[] placed = {false};
        // Placeholders resolve for the stage the editor is previewing, as in CarriagePlacer#placeForTest.
        String stage = games.brennan.dungeontrain.editor.EditorStageSelection.effective();
        CarriageStampGuard.run(() -> StagePlacementScope.run(stage, () -> {
            placed[0] = stampWhole(overworld, place, wholeOrigin, wholeKind, id, size, shellSeed)
                && (!room || stampWhole(overworld, place, after, wholeKind, id, size, seedB));
        }));
        if (!placed[0]) {
            CarriageTestSession.take(player.getUUID());
            PortalClear.clearBox(overworld, box, PortalCorridorMask.NONE);
            return failCode(source, "chat.dungeontrain.carriage_test.no_whole_template", id);
        }
        stampFlatbed(overworld, origin, dims, shellSeed);
        if (!room) stampFlatbed(overworld, after, dims, shellSeed);

        arrive(overworld, player, origin, sizeOf(dims), previous, id);
        LOGGER.info("[DungeonTrain] carriage test: stamped {} '{}' behind a flatbed at {} for {}, seed={}{}",
            kind.literal(), id, origin, player.getName().getString(), shellSeed,
            room ? ", second roll seed=" + seedB : "");
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.carriage_test.standing_in",
            id, id, "none").withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** One copy of a whole room or group at {@code at}, rolled on {@code seed}; false if it would not place. */
    private static boolean stampWhole(ServerLevel level, java.util.function.Predicate<BlockPos> place,
                                      BlockPos at, WholeKind kind, String id, Vec3i size, long seed) {
        if (!place.test(at)) return false;
        WholeOverlay.apply(level, at, kind, id, size, seed, CarriageTestSession.TEST_INDEX);
        return true;
    }

    /** A flatbed at {@code at} — where the author arrives, and what follows a whole group. */
    private static void stampFlatbed(ServerLevel level, BlockPos at, CarriageDims dims, long seed) {
        CarriagePlacer.placeForTest(level, at, CarriagePlacer.flatbedVariant(), null, dims, seed, seed,
            CarriageTestSession.TEST_INDEX);
    }

    /** Everything a test stamps: {@code length × height × width} from {@code origin}, along +X. */
    private static BoundingBox spanBox(BlockPos origin, int length, int height, int width) {
        return new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + length - 1, origin.getY() + height - 1, origin.getZ() + width - 1);
    }

    /** The second copy's seed: a different roll, but fixed by the first so reseeds stay coherent. */
    private static long nextRoll(long seed) {
        return (seed ^ 0x632BE59BD9B4E019L) * 0x9E3779B97F4A7C15L;
    }

    private static int failCode(CommandSourceStack source, String key, String id) {
        fail(source, key, id);
        return 0;
    }

    /** Resolve the shell and the contents to stamp, or tell the author why there is none. */
    private static Plan planFor(CommandSourceStack source, CarriageTestSession.Kind kind, String id,
                                long shellSeed, long seed) {
        if (kind == CarriageTestSession.Kind.CARRIAGE) {
            Optional<CarriageVariant> variant = CarriageVariantRegistry.find(id);
            if (variant.isEmpty()) return fail(source, "chat.dungeontrain.editor.unknown_carriage", id);
            return new Plan(variant.get(), contentsFor(variant.get(), seed));
        }
        Optional<CarriageContents> contents = CarriageContentsRegistry.find(id);
        if (contents.isEmpty()) return fail(source, "chat.dungeontrain.editor.unknown_contents", id);
        // A corridor's furnishing is authored to the corridor's box and only ever stands in one, so
        // it is tested in its corridor — the shell its editor plot uses.
        if (CarriageContentsPlacer.portalCorridorKindOf(contents.get()) != null) {
            CarriageContents rolled = CarriageContentsRegistry.resolveSubVariant(
                contents.get(), seed ^ CarriageTestSession.TEST_INDEX, null);
            return new Plan(games.brennan.dungeontrain.editor.CarriageContentsEditor.shellFor(rolled), rolled);
        }
        // Only a carriage that would actually carry these contents on the train stands around them:
        // one whose allow-list has them enabled and that spawns at all. A member is allowed through
        // its group's top parent — the allow-list is only ever consulted at the top-level pick.
        String topId = ContentsShellPicker.topParentOf(contents.get().id());
        CarriageVariant shell = ContentsShellPicker.pick(topId, shellSeed).orElse(null);
        if (shell == null) return fail(source, "chat.dungeontrain.carriage_test.no_shell_allows", topId);
        // A group parent rolls a member, as it would in a carriage; a member named outright is used.
        CarriageContents rolled = CarriageContentsRegistry.resolveSubVariant(
            contents.get(), seed ^ CarriageTestSession.TEST_INDEX, null);
        return new Plan(shell, rolled);
    }

    /**
     * What the train would furnish {@code shell} with. An ordinary carriage runs the train's own
     * pick — allow-list, weights and groups — ungated, since a test is at no place on the track for
     * a band gate to read. A portal corridor holds its kind's corridor contents, rolled through its
     * group; the cart between the corridors and a flatbed hold none.
     */
    private static CarriageContents contentsFor(CarriageVariant shell, long seed) {
        return contentsFor(shell, seed, null);
    }

    /** {@link #contentsFor(CarriageVariant, long)} picked within a band — {@code null} for ungated. */
    private static CarriageContents contentsFor(CarriageVariant shell, long seed,
                                                games.brennan.dungeontrain.template.GateContext gateCtx) {
        if (ContentsShellPicker.isFlatbed(shell)) return null;
        for (games.brennan.dungeontrain.portal.PortalCorridorKind k
                : games.brennan.dungeontrain.portal.PortalCorridorKind.values()) {
            if (!shell.equals(games.brennan.dungeontrain.portal.PortalCarriageBuilder.portalVariant(k))) continue;
            return CarriageContentsRegistry.resolveSubVariant(
                games.brennan.dungeontrain.portal.PortalCarriageBuilder.portalContents(k),
                seed ^ CarriageTestSession.TEST_INDEX, null);
        }
        if (ContentsShellPicker.isPortalPart(shell)) return null;
        return CarriageContentsRegistry.pick(seed, CarriageTestSession.TEST_INDEX, shell, gateCtx);
    }

    private static Plan fail(CommandSourceStack source, String key, String id) {
        source.sendFailure(Component.translatable(key, id).withStyle(ChatFormatting.RED));
        return null;
    }

    /**
     * The world's carriage seed, or a fresh one while reseeding. Never the unsalted seed when
     * salted — that would silently repeat the last roll.
     */
    private static long seedFor(DungeonTrainWorldData worldData, ServerLevel level, boolean freshRoll) {
        long base = worldData.getGenerationConfig().seed();
        if (!freshRoll && !worldData.isPortalTestReseed()) return base;
        return base ^ ((level.random.nextLong() | 1L) * 0x9E3779B97F4A7C15L);
    }

    /** Just inside the back wall, on the floor, halfway across. */
    private static BlockPos defaultArrival(BlockPos origin, Vec3i size) {
        return origin.offset(1, 1, size.getZ() / 2);
    }

    /**
     * The first open standing spot walking forward down the middle of the carriage, then outward
     * across it — contents can fill any cell, and a player teleported into a block suffocates.
     * Falls back to the plain default when the whole floor is full; creative can dig out.
     */
    private static BlockPos findArrival(ServerLevel level, ServerPlayer player, BlockPos origin,
                                        Vec3i size) {
        int mid = size.getZ() / 2;
        for (int x = 1; x < size.getX() - 1; x++) {
            for (int off = 0; off <= mid; off++) {
                for (int z : new int[] {mid - off, mid + off}) {
                    if (z < 1 || z > size.getZ() - 2) continue;
                    BlockPos at = origin.offset(x, 1, z);
                    Vec3 feet = new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                    AABB body = player.getBoundingBox().move(feet.subtract(player.position()));
                    if (level.noCollision(player, body)) return at;
                }
            }
        }
        return defaultArrival(origin, size);
    }

    /**
     * {@code editor test reseed} — re-roll the copy the author is standing in and leave them where
     * they stood, if that was within three chunks of the copy and the new roll left it open. The same rule
     * {@code PortalTestCommand.runReseedNow} follows.
     */
    static int runReseedNow(CommandSourceStack source, boolean focus) {
        ServerPlayer player = playerOf(source);
        if (player == null) return 0;
        CarriageTestSession.Session session = CarriageTestSession.get(player.getUUID());
        if (session == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.portal.not_test_carriage_test")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        ServerLevel overworld = source.getServer().overworld();
        boolean wasHere = player.level() == overworld;
        Vec3 stood = player.position();
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        int result = runTest(source, session.kind(), session.templateId(), true, focus);
        if (result == 0 || !wasHere) return result;

        CarriageTestSession.Session fresh = CarriageTestSession.get(player.getUUID());
        if (fresh == null || !PortalTestCommand.keepsPlaceOnReseed(fresh.box(), BlockPos.containing(stood))) {
            return result;
        }
        if (!overworld.noCollision(player, player.getBoundingBox().move(stood.subtract(player.position())))) {
            return result;
        }
        player.teleportTo(overworld, stood.x, stood.y, stood.z, yaw, pitch);
        return result;
    }

    /** {@code editor test back} — return the author to where they were and sweep the copy away. */
    static int runBack(CommandSourceStack source) {
        ServerPlayer player = playerOf(source);
        if (player == null) return 0;
        CarriageTestSession.Session session = CarriageTestSession.take(player.getUUID());
        if (session == null) {
            source.sendFailure(Component.translatable("chat.dungeontrain.portal.you_aren_t_test"));
            return 0;
        }
        ServerLevel overworld = source.getServer().overworld();

        ServerLevel home = source.getServer().getLevel(session.dimension());
        if (home != null) {
            player.teleportTo(home, session.pos().x, session.pos().y, session.pos().z,
                session.yaw(), session.pitch());
        }
        if (player.gameMode.getGameModeForPlayer() != session.previousGameType()) {
            player.setGameMode(session.previousGameType());
        }
        DungeonTrainNet.sendTo(player, PortalTestSessionPacket.none(
            DungeonTrainWorldData.get(overworld).isPortalTestReseed()));

        int discarded = discardEntities(overworld, session.box());
        int cleared = PortalClear.clearBox(overworld, session.box(), PortalCorridorMask.NONE);
        LOGGER.info("[DungeonTrain] carriage test back: returned {} and cleared {} block(s), {} entit(ies) of {} '{}'",
            player.getName().getString(), cleared, discarded, session.kind().literal(), session.templateId());
        // A piece of the line is filed as model:name; the author knows it by its name.
        String shown = TrackTestPiece.parseTemplateId(session.templateId())
            .map(TrackTestPiece.Named::name).orElse(session.templateId());
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.back_plot_test_has",
            shown).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /**
     * Every non-player entity the copy put in its box: the contents' stands and frames, and the mobs
     * its cells spawned — some of which will have wandered, so the box is grown a little to catch
     * them. The basement band holds nothing else to protect.
     */
    private static int discardEntities(ServerLevel level, BoundingBox box) {
        AABB area = AABB.of(box).inflate(4.0);
        int n = 0;
        for (Entity e : level.getEntities((Entity) null, area, e -> !(e instanceof Player))) {
            e.discard();
            n++;
        }
        return n;
    }

    private static ServerPlayer playerOf(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("chat.dungeontrain.save.command_must_be_run"));
            return null;
        }
    }
}
