package games.brennan.dungeontrain.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalTwinLanes;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.train.id.CarriageId;
import games.brennan.dungeontrain.train.id.CarriageIds;
import games.brennan.dungeontrain.train.id.CarriageRebuilder;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /dungeontrain debug carriage-id [rebuild <id>|verify|back]} — the id of the carriage you are
 * in, the carriage an id names stood up again in the basement, and a block-for-block check of a
 * rebuild against the group still standing on the train. Operator-only, like the rest of
 * {@code debug}: where players see their carriage ids is not decided yet.
 */
final class CarriageIdDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Blocks of {@code +Z} the rebuild stands at, beyond the carriage test's band (640). */
    private static final int REBUILD_Z_OFFSET = 704;
    /** Facing +X, down the carriage. */
    private static final float FACE_EAST = -90.0f;
    /** How many differing cells {@code verify} lists. */
    private static final int MAX_LISTED_DIFFS = 8;

    /** Where each player was before their last rebuild, for {@code back}. */
    private static final Map<UUID, Return> RETURNS = new ConcurrentHashMap<>();

    private record Return(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot) {}

    private CarriageIdDebug() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("carriage-id")
            .executes(ctx -> showHere(ctx.getSource()))
            .then(Commands.literal("rebuild")
                .then(Commands.argument("id", StringArgumentType.greedyString())
                    .executes(ctx -> rebuild(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("verify").executes(ctx -> verify(ctx.getSource())))
            .then(Commands.literal("back").executes(ctx -> back(ctx.getSource())));
    }

    private static int showHere(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return fail(source, "Run this as a player standing in a carriage.");
        ServerLevel overworld = source.getServer().overworld();
        Integer pIdx = TrainCarriageAppender.carriageIndexAt(player.serverLevel(), player, dimsOf(overworld));
        if (pIdx == null) return fail(source, "You aren't in a carriage.");
        CarriageIds.Lookup lookup = CarriageIds.idFor(overworld, pIdx);
        String full = lookup.id().format();
        String shortForm = lookup.id().toShort().format();
        source.sendSuccess(() -> Component.literal("Carriage ").withStyle(ChatFormatting.GRAY)
            .append(copyable(shortForm, ChatFormatting.AQUA))
            .append(Component.literal("  full ").withStyle(ChatFormatting.GRAY))
            .append(copyable(full, ChatFormatting.WHITE))
            .append(lookup.recorded() ? Component.empty()
                : Component.literal("  (no record: worked out from the carriage number)").withStyle(ChatFormatting.YELLOW)), false);
        LOGGER.info("[DungeonTrain] carriage-id: {} is in carriage {} → {} (recorded={})",
            player.getName().getString(), pIdx, full, lookup.recorded());
        return 1;
    }

    private static int rebuild(CommandSourceStack source, String text) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return fail(source, "Run this as a player.");
        CarriageId id;
        try {
            id = CarriageId.parse(text);
        } catch (IllegalArgumentException e) {
            return fail(source, e.getMessage());
        }
        ServerLevel overworld = source.getServer().overworld();
        BlockPos origin = rebuildOrigin(overworld, player);
        // Arrive first: the contents pass spawns mobs only near a player, as it does on the train.
        rememberReturn(player);
        CarriageDims dims = dimsOf(overworld);
        player.teleportTo(overworld, origin.getX() + 0.5, origin.getY() + 1, origin.getZ() + dims.width() / 2.0 + 0.5,
            FACE_EAST, 0.0f);
        CarriageRebuilder.Result result;
        try {
            result = CarriageRebuilder.rebuild(overworld, id, origin);
        } catch (IllegalArgumentException e) {
            back(source);
            return fail(source, e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.error("[DungeonTrain] carriage-id rebuild of '{}' failed", id, e);
            back(source);
            return fail(source, "The rebuild failed; see the server log.");
        }
        // On the group's back pad looking down the run, the way a rider boards it from the flatbed.
        BlockPos stand = origin.offset(1, 2, dims.width() / 2);
        player.teleportTo(overworld, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, FACE_EAST, 0.0f);
        source.sendSuccess(() -> Component.literal("Rebuilt ").withStyle(ChatFormatting.GREEN)
            .append(copyable(id.format(), ChatFormatting.WHITE))
            .append(Component.literal(" (group " + result.anchorPIdx() + "–" + (result.anchorPIdx() + result.groupSize() - 1)
                + "). '/dungeontrain debug carriage-id back' to return.").withStyle(ChatFormatting.GREEN)), false);
        for (String note : result.notes()) {
            source.sendSuccess(() -> Component.literal("  " + note).withStyle(ChatFormatting.YELLOW), false);
        }
        LOGGER.info("[DungeonTrain] carriage-id: rebuilt {} for {} at {} notes={}",
            id, player.getName().getString(), result.box(), result.notes());
        return 1;
    }

    /**
     * Rebuild the group the player stands in and compare it cell by cell with the group on the train.
     * Read at the moment of the call, so blocks players have broken or placed since count as differences.
     */
    private static int verify(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return fail(source, "Run this as a player standing in a carriage.");
        ServerLevel level = player.serverLevel();
        ServerLevel overworld = source.getServer().overworld();
        CarriageDims dims = dimsOf(overworld);
        Trains.Carriage group = TrainCarriageAppender.groupAt(level, player);
        Integer pIdx = TrainCarriageAppender.carriageIndexAt(level, player, dims);
        if (group == null || pIdx == null) return fail(source, "You aren't in a carriage.");
        CarriageId id = CarriageIds.idFor(overworld, pIdx).id();
        BlockPos shipyardOrigin = group.provider().getShipyardOrigin();

        BlockPos origin = rebuildOrigin(overworld, player);
        CarriageRebuilder.Result result;
        try {
            result = CarriageRebuilder.rebuild(overworld, id, origin);
        } catch (RuntimeException e) {
            LOGGER.error("[DungeonTrain] carriage-id verify of '{}' failed", id, e);
            return fail(source, "The rebuild failed: " + e.getMessage());
        }
        Comparison c = compare(level, shipyardOrigin, overworld, result.box());
        double pct = c.total == 0 ? 0.0 : 100.0 * c.same / c.total;
        ChatFormatting colour = c.same == c.total ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
        source.sendSuccess(() -> Component.literal(String.format("Verify %s: %d of %d cells match (%.2f%%).",
            id.format(), c.same, c.total, pct)).withStyle(colour), false);
        for (String diff : c.diffs.subList(0, Math.min(MAX_LISTED_DIFFS, c.diffs.size()))) {
            source.sendSuccess(() -> Component.literal("  " + diff).withStyle(ChatFormatting.GRAY), false);
        }
        for (String note : result.notes()) {
            source.sendSuccess(() -> Component.literal("  " + note).withStyle(ChatFormatting.YELLOW), false);
        }
        LOGGER.info("[DungeonTrain] carriage-id verify {}: {}/{} cells match ({}%) diffs={} notes={}",
            id, c.same, c.total, String.format("%.2f", pct), c.diffs, result.notes());
        return 1;
    }

    private record Comparison(int same, int total, List<String> diffs) {}

    /** The live group's cells (shipyard frame) against the rebuild's, offset by offset. */
    private static Comparison compare(ServerLevel trainLevel, BlockPos shipyardOrigin, ServerLevel rebuildLevel,
                                      BoundingBox box) {
        int same = 0, total = 0;
        List<String> diffs = new ArrayList<>();
        BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
        for (BlockPos pos : BlockPos.betweenClosed(min, new BlockPos(box.maxX(), box.maxY(), box.maxZ()))) {
            BlockPos offset = pos.subtract(min);
            BlockState live = trainLevel.getBlockState(shipyardOrigin.offset(offset));
            BlockState rebuilt = rebuildLevel.getBlockState(pos);
            total++;
            if (live.equals(rebuilt)) {
                same++;
            } else {
                diffs.add(offset.toShortString() + ": train " + live + " / rebuild " + rebuilt);
            }
        }
        return new Comparison(same, total, diffs);
    }

    private static int back(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Return r = RETURNS.remove(player.getUUID());
        if (r == null) return fail(source, "Nowhere to go back to.");
        ServerLevel level = source.getServer().getLevel(r.dimension());
        if (level == null) return fail(source, "That dimension isn't loaded.");
        player.teleportTo(level, r.pos().x, r.pos().y, r.pos().z, r.yRot(), r.xRot());
        return 1;
    }

    private static void rememberReturn(ServerPlayer player) {
        RETURNS.putIfAbsent(player.getUUID(),
            new Return(player.level().dimension(), player.position(), player.getYRot(), player.getXRot()));
    }

    /** The basement spot a rebuild stands at, under the player's X so its chunks are near. */
    private static BlockPos rebuildOrigin(ServerLevel overworld, ServerPlayer player) {
        return new BlockPos(player.blockPosition().getX(),
            PortalTwinLanes.floorY(overworld.getMinBuildHeight()), REBUILD_Z_OFFSET);
    }

    private static CarriageDims dimsOf(ServerLevel overworld) {
        return DungeonTrainWorldData.get(overworld).dims();
    }

    private static MutableComponent copyable(String text, ChatFormatting colour) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(colour)
            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy"))));
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
