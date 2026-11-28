package games.brennan.dungeontrain.perks;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModBlocks;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Eating a cosmic apple puts a Constellation Window at the player's feet; it is taken away the first
 * time they step out of it after putting a perk point in (from the sky or the menu), and on death or
 * logout. On a carriage it goes into the carriage's plot, so it rides with the train.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ConstellationWindowPlacement {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private ConstellationWindowPlacement() {}

    /**
     * Put the window at the player's feet; false when there is no room (the caller opens the menu).
     * A new apple is new points to spend, so the window it leaves (new, or the one already underfoot)
     * waits for a fresh spend before it goes.
     */
    public static boolean place(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        SubLevel frame = Sable.HELPER.getTrackingSubLevel(player);
        if (frame != null && frame.isRemoved()) frame = null;
        BlockPos cell = cellOf(frame, player);
        UUID frameId = frame == null ? null : frame.getUniqueId();
        BlockState here = level.getBlockState(cell);
        if (here.is(ModBlocks.CONSTELLATION_WINDOW.get())) {
            // Already standing on a window (this apple's last one, or any other): it is this one now.
            ConstellationWindowSpot old = spot(player);
            if (old.placed() && !(old.pos() == cell.asLong() && old.subLevel().equals(java.util.Optional.ofNullable(frameId)))) {
                remove(player);
            }
            remember(player, frameId, cell);
            return true;
        }
        if (!here.canBeReplaced() || !here.getFluidState().isEmpty()) {
            LOGGER.info("[DungeonTrain] No room for a constellation window at {}'s feet ({} in {}): {}",
                player.getGameProfile().getName(), cell, frameId == null ? "the world" : "carriage " + frameId, here);
            return false;
        }
        remove(player);
        if (!level.setBlock(cell, ModBlocks.CONSTELLATION_WINDOW.get().defaultBlockState(), Block.UPDATE_ALL)) {
            LOGGER.info("[DungeonTrain] Could not set a constellation window at {} for {}", cell, player.getGameProfile().getName());
            return false;
        }
        remember(player, frameId, cell);
        return true;
    }

    private static void remember(ServerPlayer player, UUID frameId, BlockPos cell) {
        player.setData(ModDataAttachments.CONSTELLATION_WINDOW.get(), ConstellationWindowSpot.at(frameId, cell.asLong()));
    }

    /** A perk point went in: the window goes once the player steps out. */
    public static void markSpent(ServerPlayer player) {
        ConstellationWindowSpot s = spot(player);
        if (s.placed() && !s.spent()) player.setData(ModDataAttachments.CONSTELLATION_WINDOW.get(), s.withSpent());
    }

    /** Take the window away (if it is still there) and forget it. */
    public static void remove(ServerPlayer player) {
        ConstellationWindowSpot s = spot(player);
        if (!s.placed()) return;
        ServerLevel level = player.serverLevel();
        BlockPos pos = BlockPos.of(s.pos());
        // A carriage that has gone takes its plot cell with it; only clear a cell that still holds ours.
        if (s.subLevel().isEmpty() || subLevel(level, s.subLevel().get()) != null) {
            if (isWindow(level, s)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        player.setData(ModDataAttachments.CONSTELLATION_WINDOW.get(), ConstellationWindowSpot.NONE);
    }

    static ConstellationWindowSpot spot(ServerPlayer player) {
        return player.getData(ModDataAttachments.CONSTELLATION_WINDOW.get());
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ConstellationWindowSpot s = spot(player);
        if (!s.placed()) return;
        ServerLevel level = player.serverLevel();
        if (s.subLevel().isPresent() && subLevel(level, s.subLevel().get()) == null) {
            player.setData(ModDataAttachments.CONSTELLATION_WINDOW.get(), ConstellationWindowSpot.NONE);
            return;
        }
        if (!isWindow(level, s)) { // broken or replaced: nothing left to take away
            player.setData(ModDataAttachments.CONSTELLATION_WINDOW.get(), ConstellationWindowSpot.NONE);
            return;
        }
        if (ConstellationWindowSpot.shouldRemove(s, inCell(player, s))) remove(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) safeRemove(player);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) safeRemove(player); // left by a crash
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) safeRemove(player);
    }

    private static void safeRemove(ServerPlayer player) {
        try {
            remove(player);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not remove {}'s constellation window", player.getGameProfile().getName(), e);
            player.setData(ModDataAttachments.CONSTELLATION_WINDOW.get(), ConstellationWindowSpot.NONE);
        }
    }

    private static boolean isWindow(ServerLevel level, ConstellationWindowSpot s) {
        return level.getBlockState(BlockPos.of(s.pos())).is(ModBlocks.CONSTELLATION_WINDOW.get());
    }

    /** Does the player's body overlap the window's cell (in the carriage's frame when it has one)? */
    private static boolean inCell(ServerPlayer player, ConstellationWindowSpot s) {
        BlockPos pos = BlockPos.of(s.pos());
        AABB cell = new AABB(pos);
        AABB body = player.getBoundingBox().deflate(1.0e-3);
        if (s.subLevel().isEmpty()) return body.intersects(cell);
        SubLevel sub = subLevel(player.serverLevel(), s.subLevel().get());
        if (sub == null) return false;
        Pose3dc pose = sub.logicalPose();
        // The body's box carried into the plot: the eight corners' bounds (carriages don't roll).
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int c = 0; c < 8; c++) {
            Vector3d p = pose.transformPositionInverse(new Vector3d(
                (c & 1) == 0 ? body.minX : body.maxX,
                (c & 2) == 0 ? body.minY : body.maxY,
                (c & 4) == 0 ? body.minZ : body.maxZ));
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).intersects(cell);
    }

    private static BlockPos cellOf(SubLevel frame, ServerPlayer player) {
        if (frame == null) return player.blockPosition();
        Vector3d local = frame.logicalPose().transformPositionInverse(new Vector3d(player.getX(), player.getY() + 0.01, player.getZ()));
        return BlockPos.containing(local.x, local.y, local.z);
    }

    private static SubLevel subLevel(ServerLevel level, UUID id) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        SubLevel sub = container.getSubLevel(id);
        return sub == null || sub.isRemoved() ? null : sub;
    }
}
