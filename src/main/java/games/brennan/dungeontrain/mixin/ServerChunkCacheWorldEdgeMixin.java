package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Never generates a chunk past the world's hard edge (30,000,000 blocks): one asked for there comes
 * back as an empty chunk instead.
 *
 * <p><b>Why:</b> a carriage snow golem's path came back from Sable Mob Paths 0.3.0 with its nodes
 * converted world-to-plot twice, at about 40,950,000 blocks. Vanilla {@code PathNavigation.trimPath}
 * read a block there, the server generated that chunk on its own thread, and vanilla's aquifer
 * overflows that far out ({@code ArrayIndexOutOfBoundsException} in {@code getAquiferStatus}). The
 * exception killed the integrated server while the client played on, frozen (a player report of
 * November 2026). Sable Mob Paths 0.4.1 fixes that conversion; this catches the next coordinate bug
 * of its kind, whatever reads the block.</p>
 *
 * <p>Nothing legitimate lives out there: the world border tops out inside the edge, and Sable's plot
 * grid, which its own {@code ServerChunkCacheMixin} answers from the plots, is left alone here. The
 * first such request in a run is logged with its stack, so a bug report names the caller.</p>
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheWorldEdgeMixin {

    @Unique
    private static final Logger dungeonTrain$LOGGER = LogUtils.getLogger();

    /** Last chunk coordinate inside the world's hard edge, either side of zero. */
    @Unique
    private static final int dungeonTrain$EDGE_CHUNK = Level.MAX_LEVEL_SIZE >> 4;

    @Unique
    private static boolean dungeonTrain$logged;

    @Shadow @Final ServerLevel level;

    @Unique
    private EmptyLevelChunk dungeonTrain$emptyChunk;

    @Inject(method = "getChunkFutureMainThread", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$noChunkPastWorldEdge(int x, int z, ChunkStatus status, boolean load,
                                                   CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        if (Math.abs(x) <= dungeonTrain$EDGE_CHUNK && Math.abs(z) <= dungeonTrain$EDGE_CHUNK) return;
        SubLevelContainer plots = SubLevelContainer.getContainer(level);
        if (plots != null && plots.inBounds(x, z)) return;
        if (!dungeonTrain$logged) {
            dungeonTrain$logged = true;
            dungeonTrain$LOGGER.warn("[DungeonTrain] Refused to generate chunk {},{} past the world's edge; "
                + "returned an empty chunk (logged once per run)", x, z, new Throwable("caller"));
        }
        if (dungeonTrain$emptyChunk == null) {
            dungeonTrain$emptyChunk = new EmptyLevelChunk(level, new ChunkPos(0, 0),
                level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        }
        cir.setReturnValue(CompletableFuture.completedFuture(ChunkResult.of(dungeonTrain$emptyChunk)));
    }
}
