package games.brennan.dungeontrain.spikefabric;

import com.mojang.brigadier.Command;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 0 go/no-go spike for the Dungeon Train Fabric port.
 *
 * <p>Mirrors DT's core Sable usage (see {@code ship/sable/SableShipyard} and
 * {@code SableManagedShip} in the main repo): world blocks are assembled into
 * a {@link ServerSubLevel} via {@link SubLevelAssemblyHelper#assembleBlocks},
 * then moved through {@link RigidBodyHandle}. If these three commands work on
 * Fabric, the physics foundation of the port is proven.</p>
 *
 * <ul>
 *   <li>{@code /sablespike spawn} — builds a 5x3 plank deck with a mast next
 *       to the player and assembles it into a sub-level</li>
 *   <li>{@code /sablespike push} — applies linear + angular velocity to the
 *       last assembled sub-level</li>
 *   <li>{@code /sablespike list} — lists live sub-levels in the player's
 *       dimension</li>
 * </ul>
 */
public final class SableSpike implements ModInitializer {

    public static final String MOD_ID = "dungeontrain_spike";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** UUID survives Sable re-creating the ServerSubLevel between ticks. */
    private static UUID lastAssembled;

    @Override
    public void onInitialize() {
        LOGGER.info("[spike] Sable-Fabric spike initialised; sable loaded = {}",
            FabricLoader.getInstance().isModLoaded("sable"));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("sablespike")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn").executes(ctx -> spawn(ctx.getSource())))
                .then(Commands.literal("push").executes(ctx -> push(ctx.getSource())))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))));
    }

    private static int spawn(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos base = BlockPos.containing(source.getPosition()).offset(4, 1, -1);

        Set<BlockPos> blocks = new HashSet<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 3; z++) {
                BlockPos p = base.offset(x, 0, z);
                level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                blocks.add(p);
            }
        }
        BlockPos mast = base.offset(2, 1, 1);
        level.setBlock(mast, Blocks.STONE_BRICKS.defaultBlockState(), 3);
        blocks.add(mast);

        // Same contract as SableShipyard.assemble: anchor = AABB centre,
        // bounds = exact integer AABB of the block set.
        BlockPos anchor = base.offset(2, 0, 1);
        BoundingBox3i bounds = computeBounds(blocks);

        try {
            ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, anchor, blocks, bounds);
            lastAssembled = subLevel.getUniqueId();
            LOGGER.info("[spike] Assembled sub-level {} from {} blocks at {}",
                lastAssembled, blocks.size(), anchor);
            source.sendSuccess(() -> Component.literal(
                "Assembled sub-level " + lastAssembled + " (" + blocks.size()
                    + " blocks). Step on the deck, then run /sablespike push"), true);
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException e) {
            LOGGER.error("[spike] Sub-level assembly FAILED", e);
            source.sendFailure(Component.literal("Assembly failed: " + e));
            return 0;
        }
    }

    private static int push(CommandSourceStack source) {
        ServerSubLevel subLevel = findLastAssembled(source.getLevel());
        if (subLevel == null) {
            source.sendFailure(Component.literal(
                "No live sub-level from this session — run /sablespike spawn first"));
            return 0;
        }
        try {
            RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
            handle.addLinearAndAngularVelocity(new Vector3d(0.0, 3.0, 0.0), new Vector3d(0.0, 0.5, 0.0));
            LOGGER.info("[spike] Pushed sub-level {}", subLevel.getUniqueId());
            source.sendSuccess(() -> Component.literal(
                "Pushed sub-level " + subLevel.getUniqueId() + " (hop + spin)"), true);
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException e) {
            LOGGER.error("[spike] Push FAILED", e);
            source.sendFailure(Component.literal("Push failed: " + e));
            return 0;
        }
    }

    private static int list(CommandSourceStack source) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(source.getLevel());
        if (container == null) {
            source.sendFailure(Component.literal("No sub-level container for this dimension"));
            return 0;
        }
        StringBuilder out = new StringBuilder("Live sub-levels:");
        int count = 0;
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub instanceof ServerSubLevel server && !server.isRemoved()) {
                count++;
                var pos = server.logicalPose().position();
                out.append("\n  ").append(server.getUniqueId())
                    .append(String.format(" @ (%.2f, %.2f, %.2f)", pos.x(), pos.y(), pos.z()));
            }
        }
        int total = count;
        String message = total == 0 ? "No live sub-levels" : out.toString();
        source.sendSuccess(() -> Component.literal(message + " (total " + total + ")"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static ServerSubLevel findLastAssembled(ServerLevel level) {
        if (lastAssembled == null) {
            return null;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub instanceof ServerSubLevel server && !server.isRemoved()
                && lastAssembled.equals(server.getUniqueId())) {
                return server;
            }
        }
        return null;
    }

    private static BoundingBox3i computeBounds(Set<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return new BoundingBox3i(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
