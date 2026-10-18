package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import games.brennan.dungeontrain.ship.sable.PhysicsTicketNoSyncLoad;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

/**
 * Stops Sable's physics ticket manager from <b>synchronously loading or generating</b> a world
 * chunk on the server thread — the 26 s resume freeze (player log 17 Oct 2026: stall watchdog
 * stack {@code PhysicsChunkTicketManager.update:168 → inhabitChunk:240 → ServerLevel.getChunk},
 * 138 chunk waits, 99 resident train groups). Policy and rationale: {@link PhysicsTicketNoSyncLoad}.
 *
 * <ul>
 *   <li>{@code inhabitChunk}: the ENTITY_TICKING ticket it adds already loads the chunk
 *       asynchronously; the trailing {@code getChunk(x, z, FULL, true)} only made the server
 *       thread wait for it. Its result is discarded, so an unloaded chunk just skips the wait.</li>
 *   <li>{@code addTicket}: for a section not yet in the physics scene whose chunk isn't loaded,
 *       return a throwaway, unstored ticket instead of blocking on {@code Level.getChunk(x, z)}.
 *       The section is not added to the scene, so the next tick retries it and adds it once the
 *       async load lands. Until then a force-loaded group on that chunk has no terrain collider —
 *       harmless for kinematic train groups and parked frozen ones. The returned ticket is only
 *       read when {@code SubLevelPhysicsSystem.USE_TICKETS_FOR_QUERIES} is set ({@code false} in
 *       2.0.5), and gets a real resident set so that path stays safe too.</li>
 * </ul>
 *
 * <p>Plot chunks and already-loaded chunks keep the original path (both resolve instantly).</p>
 *
 * <p>{@code remap = false}: target and methods are Sable's own names; the wrapped calls are
 * Mojang-mapped, as in the Sable NeoForge jar. Bytecode-verified against
 * {@code sable-2.0.5+mc1.21.1}: {@code inhabitChunk} has exactly one
 * {@code ServerLevel.getChunk(IILChunkStatus;Z)} site, and {@code addTicket(Level, PhysicsPipeline,
 * SectionPos, int, int, int, int, long)} opens with the {@code physicsChunks.get(sectionPos)} lookup
 * guarding its {@code Level.getChunk(II)} call. <b>Re-verify on any {@code sable_version} bump.</b></p>
 */
@Mixin(value = PhysicsChunkTicketManager.class, remap = false)
public abstract class PhysicsChunkTicketManagerNoSyncLoadMixin {

    @WrapOperation(
            method = "inhabitChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"
            )
    )
    private ChunkAccess dungeontrain$inhabitWithoutBlocking(final ServerLevel level, final int x, final int z,
                                                            final ChunkStatus status, final boolean load,
                                                            final Operation<ChunkAccess> original) {
        if (dungeontrain$skipBlockingLoad(level, x, z)) return null;
        return original.call(level, x, z, status, load);
    }

    @Inject(method = "addTicket", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$deferUnloadedSection(final Level level, final PhysicsPipeline pipeline,
                                                   final SectionPos sectionPos, final int x, final int y,
                                                   final int z, final int index, final long gameTime,
                                                   final CallbackInfoReturnable<PhysicsChunkTicket> cir) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (((PhysicsChunkTicketManagerAccessor) this).dungeontrain$physicsChunks().containsKey(sectionPos)) return;
        if (!dungeontrain$skipBlockingLoad(serverLevel, x, z)) return;
        cir.setReturnValue(new PhysicsChunkTicket(sectionPos, gameTime, new ArrayList<>(1)));
    }

    @Unique
    private static boolean dungeontrain$skipBlockingLoad(final ServerLevel level, final int x, final int z) {
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        final boolean inPlotBounds = container != null && container.inBounds(x, z);
        final boolean loadedNow = ((ServerChunkCache) level.getChunkSource()).getChunkNow(x, z) != null;
        return PhysicsTicketNoSyncLoad.shouldSkipBlockingLoad(inPlotBounds, loadedNow);
    }
}
