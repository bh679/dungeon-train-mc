package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.SableCommonEvents;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import games.brennan.dungeontrain.ship.sable.WorldgenForceGuard;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Two hooks on Sable's per-block-change entry point {@code SableCommonEvents.handleBlockChange} — the
 * single point Sable's {@code plot.LevelChunkMixin} (on {@code LevelChunk.setBlockState}) calls for
 * every block change in a sub-level.
 *
 * <ol>
 *   <li><b>Worldgen-force guard (HEAD, cancellable)</b> — cancels the physics hook while a Dungeon
 *       Train <b>synchronous forced chunk generation</b> is in progress on this thread, breaking the
 *       re-entrant {@code getChunk} deadlock that otherwise freezes the server at spawn (see
 *       {@link WorldgenForceGuard}). During a DT forced {@code getChunk(FULL, true)}, a fluid tick in
 *       {@code postProcessGeneration} sets a block → this hook → {@code SubLevelPhysicsSystem} → a
 *       nested {@code getChunk} → permanent deadlock. Skipping the hook avoids the re-entry.</li>
 *   <li><b>Shared-carriage edit tracking (TAIL)</b> — after a block change lands in a carriage
 *       sub-level, marks the corresponding {@link SharedCarriageRegistry} instance dirty so its build
 *       is uploaded/saved to the relay. Breaking a loot container is excluded (looting never fires this
 *       hook at all; only breaking the block does), so ordinary looting never dirties a carriage.</li>
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
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        ChunkPos cpos = chunk.getPos();
        if (container.getChunkHolder(cpos) == null) return;
        LevelPlot plot = container.getPlot(cpos);
        if (plot == null || !(plot.getSubLevel() instanceof ServerSubLevel serverSub)) return;
        UUID subLevelId = serverSub.getUniqueId();
        if (!SharedCarriageRegistry.hasSubLevel(subLevelId)) return;
        // Breaking a loot container (chest/barrel/pot/brushable → air) does NOT count as a build change.
        if (newState.isAir() && isLootContainer(oldState)) return;
        SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.resolve(subLevelId, x, y, z);
        if (inst != null && !inst.isDirty()) {
            inst.markDirty();
        }
    }

    private static boolean isLootContainer(BlockState s) {
        return ContainerContentsRoller.isContainerState(s)
                || ContainerContentsRoller.isDecoratedPot(s)
                || ContainerContentsRoller.isBrushable(s);
    }
}
