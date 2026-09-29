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

/**
 * {@code /dungeontrain editor test carriages|contents <id>} — Test the Carriage for a carriage or a
 * contents template: stand on a flatbed facing two copies of it, rolled the way the train rolls a
 * carriage — the second on a different seed, so two rolls can be compared side by side. A whole room
 * or group is stood up once, between two flatbeds. Laid out along {@code +X}:
 *
 * <pre>
 *   carriage / contents:  [flatbed | roll A | roll B]
 *   whole room / group:   [flatbed | whole  | flatbed]
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
            if (kind == CarriageTestSession.Kind.CARRIAGE) contentsSeed = current.contentsSeed();
            else shellSeed = current.shellSeed();
        }
        if (kind.isWhole()) return runWholeTest(source, player, overworld, kind, id, shellSeed, contentsSeed);
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

        // [flatbed | whole | flatbed] — a whole template stands between flatbeds, as on the train.
        BlockPos origin = new BlockPos(player.blockPosition().getX(),
            PortalTwinLanes.floorY(overworld.getMinBuildHeight()), TEST_Z_OFFSET);
        BlockPos wholeOrigin = origin.offset(dims.length(), 0, 0);
        BlockPos frontFlatbed = wholeOrigin.offset(size.getX(), 0, 0);
        BoundingBox box = spanBox(origin, 2 * dims.length() + size.getX(),
            Math.max(dims.height(), size.getY()), Math.max(dims.width(), size.getZ()));
        GameType previous = player.gameMode.getGameModeForPlayer();
        CarriageTestSession.put(player.getUUID(), new CarriageTestSession.Session(
            player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
            previous, kind, id, box, shellSeed, contentsSeed));

        boolean[] placed = {false};
        // Placeholders resolve for the stage the editor is previewing, as in CarriagePlacer#placeForTest.
        String stage = games.brennan.dungeontrain.editor.EditorStageSelection.effective();
        CarriageStampGuard.run(() -> StagePlacementScope.run(stage, () -> {
            placed[0] = place.test(wholeOrigin);
            if (placed[0]) {
                WholeOverlay.apply(overworld, wholeOrigin, wholeKind, id, size, shellSeed,
                    CarriageTestSession.TEST_INDEX);
            }
        }));
        if (!placed[0]) {
            CarriageTestSession.take(player.getUUID());
            PortalClear.clearBox(overworld, box, PortalCorridorMask.NONE);
            return failCode(source, "chat.dungeontrain.carriage_test.no_whole_template", id);
        }
        stampFlatbed(overworld, origin, dims, shellSeed);
        stampFlatbed(overworld, frontFlatbed, dims, shellSeed);

        arrive(overworld, player, origin, sizeOf(dims), previous, id);
        LOGGER.info("[DungeonTrain] carriage test: stamped {} '{}' at {} for {}, seed={}",
            kind.literal(), id, origin, player.getName().getString(), shellSeed);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.carriage_test.standing_in",
            id, id, "none").withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** A flatbed at {@code at} — where the author arrives, and what flanks a whole template. */
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
        if (ContentsShellPicker.isFlatbed(shell)) return null;
        for (games.brennan.dungeontrain.portal.PortalCorridorKind k
                : games.brennan.dungeontrain.portal.PortalCorridorKind.values()) {
            if (!shell.equals(games.brennan.dungeontrain.portal.PortalCarriageBuilder.portalVariant(k))) continue;
            return CarriageContentsRegistry.resolveSubVariant(
                games.brennan.dungeontrain.portal.PortalCarriageBuilder.portalContents(k),
                seed ^ CarriageTestSession.TEST_INDEX, null);
        }
        if (ContentsShellPicker.isPortalPart(shell)) return null;
        return CarriageContentsRegistry.pick(seed, CarriageTestSession.TEST_INDEX, shell, null);
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
     * they stood, if the new roll left that spot open. The same rule
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
        if (fresh == null || !fresh.box().isInside(BlockPos.containing(stood))) return result;
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
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.portal.back_plot_test_has",
            session.templateId()).withStyle(ChatFormatting.GRAY), false);
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
