package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.SableCommonEvents;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.portal.PortalEditMirror;
import games.brennan.dungeontrain.ship.sable.CarriagePivotPin;
import games.brennan.dungeontrain.ship.sable.CarriagePlotResolver;
import games.brennan.dungeontrain.ship.sable.WorldgenForceGuard;
import games.brennan.dungeontrain.train.PlayerPlacedTrainBlocks;
import games.brennan.dungeontrain.train.SharedCarriageChangeFilter;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Hooks on Sable's per-block-change entry point {@code SableCommonEvents.handleBlockChange} —
 * the single point Sable's {@code plot.LevelChunkMixin} (on {@code LevelChunk.setBlockState}) calls
 * for every block change in a sub-level.
 *
 * <ol>
 *   <li><b>Worldgen-force guard (HEAD, cancellable)</b> — cancels the physics hook while a Dungeon
 *       Train <b>synchronous forced chunk generation</b> is in progress on this thread, breaking the
 *       re-entrant {@code getChunk} deadlock that otherwise freezes the server at spawn (see
 *       {@link WorldgenForceGuard}). During a DT forced {@code getChunk(FULL, true)}, a fluid tick in
 *       {@code postProcessGeneration} sets a block → this hook → {@code SubLevelPhysicsSystem} → a
 *       nested {@code getChunk} → permanent deadlock. Skipping the hook avoids the re-entry.</li>
 *   <li><b>Pivot re-pin (TAIL)</b> — the block change we just tailed made Sable recompute the
 *       sub-level's centre of mass and write it into {@code Pose3d.rotationPoint}, which for a DT
 *       carriage would translate every one of its blocks. {@link CarriagePivotPin#repinAfterMassChange}
 *       puts the spawn-locked pivot back synchronously, before anything can observe the moved one.
 *       This is what stops a bed explosion — or a creeper, TNT, or a player with a pickaxe — from
 *       shifting a train.</li>
 *   <li><b>Shared-carriage edit tracking (TAIL)</b> — after a block change lands in a carriage
 *       sub-level, marks the corresponding {@link SharedCarriageRegistry} instance dirty so its build
 *       is uploaded/saved to the relay. Which changes count is
 *       {@link SharedCarriageChangeFilter}'s call — it excludes breaking a loot container (so ordinary
 *       looting never dirties a carriage) and transient property flips (a plate powering, a door
 *       swinging), which otherwise upload a delta every ~1.5&nbsp;s while a player walks around.</li>
 *   <li><b>Player-block unmark (TAIL)</b> — a change of block type ends a player-added block's
 *       claim on its cell ({@link PlayerPlacedTrainBlocks}); whatever fills the cell next is judged
 *       afresh, and a player's own placement re-marks it when its place event fires.</li>
 * </ol>
 *
 * <p>{@code remap = false}: {@code SableCommonEvents} and {@code handleBlockChange} are Sable's own
 * names. Bytecode-verified against {@code sable-2.0.2+mc1.21.1} — {@code public static void
 * handleBlockChange(ServerLevel, LevelChunk, int, int, int, BlockState, BlockState)}. <b>Re-verify on
 * any {@code sable_version} bump.</b></p>
 */
@Mixin(value = SableCommonEvents.class, remap = false)
public abstract class SableBlockChangeGuardMixin {

    @Inject(method = "handleBlockChange", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$skipDuringWorldgenForce(
            final ServerLevel level, final LevelChunk chunk, final int x, final int y, final int z,
            final BlockState oldState, final BlockState newState, final CallbackInfo ci) {
        if (WorldgenForceGuard.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleBlockChange", at = @At("TAIL"), remap = false)
    private static void dungeontrain$trackSharedCarriageEdit(
            final ServerLevel level, final LevelChunk chunk, final int x, final int y, final int z,
            final BlockState oldState, final BlockState newState, final CallbackInfo ci) {
        // (The HEAD guard already returns early during worldgen force; belt-and-suspenders here too.)
        if (WorldgenForceGuard.isActive()) return;
        // Resolve the sub-level this chunk belongs to. A null chunk-holder means an ordinary world
        // chunk (not a carriage) — the overwhelming majority of block changes short-circuit here.
        ServerSubLevel serverSub = CarriagePlotResolver.subLevelAt(level, chunk.getPos());
        if (serverSub == null) return;
        LevelPlot plot = serverSub.getPlot();

        // Undo the centre-of-mass shift the call we just tailed applied to this sub-level's pivot.
        //
        // Placement is load-bearing. Everything above this line means "no mass recompute happened"
        // (the worldgen guard cancelled Sable's hook outright; the rest mean the change wasn't in a
        // sub-level at all), so skipping is correct. Everything BELOW it must not gate the pin: a
        // carriage that isn't a shared build never reaches the hasSubLevel check, and
        // SharedCarriageChangeFilter deliberately excludes breaking a loot container — both of which
        // move the centre of mass just as much as any other block removal.
        CarriagePivotPin.repinAfterMassChange(serverSub);

        // A player-added block that is broken or replaced — by anything — stops being the player's.
        // Runs before the place event of a player's own placement, which then re-marks the cell.
        PlayerPlacedTrainBlocks.onBlockChanged(serverSub, x, y, z, oldState.getBlock() != newState.getBlock());
        // Likewise a forced fence / wall connect mode: replace the block and it is an ordinary one.
        games.brennan.dungeontrain.train.ForcedConnectCells.onBlockChanged(
            serverSub, x, y, z, oldState.getBlock() != newState.getBlock());

        // Hallway portal: a corridor and its twin must stay block-for-block identical or the crossing
        // becomes visible, so any edit inside a portal carriage is copied to its twin. Placed on
        // Sable's own choke point, this catches redstone, pistons and explosions as well as players.
        // PortalEditMirror no-ops when no portals are live, and guards its own re-entry.
        PortalEditMirror.onCarriageBlockChanged(level, plot, x, y, z, newState);

        UUID subLevelId = serverSub.getUniqueId();
        if (!SharedCarriageRegistry.hasSubLevel(subLevelId)) return;
        // Not every block change is a BUILD change — breaking a loot container and flipping a transient
        // property (a plate powering, a door swinging) both leave the build untouched.
        if (!SharedCarriageChangeFilter.isBuildChange(oldState, newState)) return;
        SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.resolve(subLevelId, x, y, z);
        // Queue the changed cell for the next delta flush (deduped by pos; drained off-thread). Cheap +
        // non-blocking — safe on the server thread inside setBlock. Skipped once the carriage is culling.
        if (inst != null && !inst.isCulled()) {
            inst.enqueue(new BlockPos(x, y, z));
            // A real build edit — from now on this carriage's parked storage changes are allowed to travel.
            inst.markBlockEdited();
        }
    }
}
