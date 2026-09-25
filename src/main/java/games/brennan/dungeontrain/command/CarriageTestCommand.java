package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalTestSessionPacket;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorKind;
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
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
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
 * contents template: stand inside one whole copy of it, rolled the way the train rolls a carriage.
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
 *   <li><b>Contents</b> are stood up inside the shell their editor plot wraps them in. A group parent
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
            .then(Commands.literal("back").executes(ctx -> runBack(ctx.getSource())))
            .then(Commands.literal("reseed").executes(ctx -> runReseedNow(ctx.getSource())));
    }

    /** What a test stands up: the shell, and what goes in it. */
    private record Plan(CarriageVariant shell, CarriageContents contents) {}

    /**
     * @param freshRoll salt the rolls whatever the world switch says — a reseed of the copy the
     *                  author is already standing in
     */
    static int runTest(CommandSourceStack source, CarriageTestSession.Kind kind, String id,
                       boolean freshRoll) {
        ServerPlayer player = playerOf(source);
        if (player == null) return 0;

        ServerLevel overworld = source.getServer().overworld();
        DungeonTrainWorldData worldData = DungeonTrainWorldData.get(overworld);
        CarriageDims dims = worldData.dims();

        // Resolved before anything else moves: a request that is refused (an unknown id, a portal
        // part) must leave the author standing in whatever test they are already in.
        long seed = seedFor(worldData, overworld, freshRoll);
        Plan plan = planFor(source, kind, id, seed);
        if (plan == null) return 0;

        // Already inside a test — of either kind: stamping a second would leave the first standing and
        // lose the way home. Send them back first, then in again, so the button is idempotent.
        if (CarriageTestSession.has(player.getUUID())) runBack(source);
        if (PortalTestSession.has(player.getUUID())) PortalTestCommand.runBack(source);

        BlockPos origin = new BlockPos(player.blockPosition().getX(),
            PortalTwinLanes.floorY(overworld.getMinBuildHeight()), TEST_Z_OFFSET);
        CarriageDims shellDims = CarriagePlacer.variantDims(plan.shell(), dims);
        BoundingBox box = new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + shellDims.length() - 1, origin.getY() + shellDims.height() - 1,
            origin.getZ() + shellDims.width() - 1);
        GameType previous = player.gameMode.getGameModeForPlayer();
        // Registered BEFORE the stamp: the mob cells ask CarriageTestSession.isTestStamp while they
        // are placed, and a test is the one carriage stamp that spawns its hostiles as authored.
        CarriageTestSession.put(player.getUUID(), new CarriageTestSession.Session(
            player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
            previous, kind, id, box));

        CarriagePlacer.placeForTest(overworld, origin, plan.shell(), plan.contents(), dims, seed,
            CarriageTestSession.TEST_INDEX);

        BlockPos arrival = findArrival(overworld, player, origin, shellDims);
        if (previous != GameType.CREATIVE) player.setGameMode(GameType.CREATIVE);
        player.teleportTo(overworld, arrival.getX() + 0.5, arrival.getY(), arrival.getZ() + 0.5,
            FACE_EAST, 0.0f);
        DungeonTrainNet.sendTo(player, new PortalTestSessionPacket(true, id,
            worldData.isPortalTestReseed()));

        String contentsId = plan.contents() == null ? "none" : plan.contents().id();
        LOGGER.info("[DungeonTrain] carriage test: stamped {} '{}' (shell={}, contents={}) at {} for {}, seed={}",
            kind.literal(), id, plan.shell().id(), contentsId, origin, player.getName().getString(), seed);
        source.sendSuccess(() -> Component.translatable("chat.dungeontrain.carriage_test.standing_in",
            id, plan.shell().id(), contentsId).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Resolve the shell and the contents to stamp, or tell the author why there is none. */
    private static Plan planFor(CommandSourceStack source, CarriageTestSession.Kind kind, String id,
                                long seed) {
        if (kind == CarriageTestSession.Kind.CARRIAGE) {
            Optional<CarriageVariant> variant = CarriageVariantRegistry.find(id);
            if (variant.isEmpty()) return fail(source, "chat.dungeontrain.editor.unknown_carriage", id);
            if (isPortalShell(variant.get())) return fail(source, "chat.dungeontrain.carriage_test.portal_part", id);
            // The train's own pick for this shell — allow-list, weights and groups — ungated, since
            // a test is at no place on the track for a band gate to read.
            // A flatbed has no interior, and the train furnishes none.
            CarriageContents contents = isFlatbed(variant.get()) ? null : CarriageContentsRegistry.pick(
                seed, CarriageTestSession.TEST_INDEX, variant.get(), null);
            return new Plan(variant.get(), contents);
        }
        Optional<CarriageContents> contents = CarriageContentsRegistry.find(id);
        if (contents.isEmpty()) return fail(source, "chat.dungeontrain.editor.unknown_contents", id);
        // A corridor's furnishing is authored to the corridor's box and only ever stands in one; the
        // dimensional-carriage test is where it is seen as a player meets it.
        if (CarriageContentsPlacer.portalCorridorKindOf(contents.get()) != null) {
            return fail(source, "chat.dungeontrain.carriage_test.portal_part", id);
        }
        // A group parent rolls a member, as it would in a carriage; a member named outright is used.
        CarriageContents rolled = CarriageContentsRegistry.resolveSubVariant(
            contents.get(), seed ^ CarriageTestSession.TEST_INDEX, null);
        return new Plan(CarriageContentsEditor.shellFor(rolled), rolled);
    }

    private static Plan fail(CommandSourceStack source, String key, String id) {
        source.sendFailure(Component.translatable(key, id).withStyle(ChatFormatting.RED));
        return null;
    }

    private static boolean isFlatbed(CarriageVariant variant) {
        return variant instanceof CarriageVariant.Builtin b && b.type() == CarriagePlacer.CarriageType.FLATBED;
    }

    /** The corridor and the cart between a portal's corridors: parts of a dimensional carriage. */
    private static boolean isPortalShell(CarriageVariant variant) {
        if (variant.equals(PortalCarriageBuilder.middleVariant())) return true;
        for (PortalCorridorKind k : PortalCorridorKind.values()) {
            if (variant.equals(PortalCarriageBuilder.portalVariant(k))) return true;
        }
        return false;
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
    private static BlockPos defaultArrival(BlockPos origin, CarriageDims dims) {
        return origin.offset(1, 1, dims.width() / 2);
    }

    /**
     * The first open standing spot walking forward down the middle of the carriage, then outward
     * across it — contents can fill any cell, and a player teleported into a block suffocates.
     * Falls back to the plain default when the whole floor is full; creative can dig out.
     */
    private static BlockPos findArrival(ServerLevel level, ServerPlayer player, BlockPos origin,
                                        CarriageDims dims) {
        int mid = dims.width() / 2;
        for (int x = 1; x < dims.length() - 1; x++) {
            for (int off = 0; off <= mid; off++) {
                for (int z : new int[] {mid - off, mid + off}) {
                    if (z < 1 || z > dims.width() - 2) continue;
                    BlockPos at = origin.offset(x, 1, z);
                    Vec3 feet = new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                    AABB body = player.getBoundingBox().move(feet.subtract(player.position()));
                    if (level.noCollision(player, body)) return at;
                }
            }
        }
        return defaultArrival(origin, dims);
    }

    /**
     * {@code editor test reseed} — re-roll the copy the author is standing in and leave them where
     * they stood, if the new roll left that spot open. The same rule
     * {@code PortalTestCommand.runReseedNow} follows.
     */
    static int runReseedNow(CommandSourceStack source) {
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

        int result = runTest(source, session.kind(), session.templateId(), true);
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
