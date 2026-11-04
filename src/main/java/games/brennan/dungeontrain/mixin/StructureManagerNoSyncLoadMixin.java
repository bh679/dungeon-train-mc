package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.worldgen.OffThreadStructureChunks;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps a structure lookup made off the server thread, through the level's own
 * {@link StructureManager}, from loading a chunk <em>on</em> the server thread.
 *
 * <p>Every {@code getChunk} call site in {@code StructureManager} is wrapped. On the server thread,
 * or for a region-wrapped manager ({@code forWorldGenRegion}, whose level is a {@code WorldGenRegion}),
 * the original call runs untouched. Off the server thread with a {@code ServerLevel}, the chunk is read
 * from the chunk map's visible holders instead ({@link OffThreadStructureChunks#loadedChunk}); when it
 * isn't loaded to the wanted status the lookup gets a lazily-built {@link EmptyLevelChunk}, which has
 * no structure references and no starts, so the caller sees "no structure here" rather than a 1.5 s
 * server-thread generation. See {@link OffThreadStructureChunks} for the Distant Horizons case this
 * exists for.</p>
 */
@Mixin(StructureManager.class)
public abstract class StructureManagerNoSyncLoadMixin {

    @Shadow
    @Final
    private LevelAccessor level;

    @Unique
    private EmptyLevelChunk dungeontrain$emptyChunk = null;

    @Unique
    private final OffThreadStructureChunks.SkipNote dungeontrain$skipNote = new OffThreadStructureChunks.SkipNote();

    @WrapOperation(
            method = {
                    "startsForStructure(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Predicate;)Ljava/util/List;",
                    "startsForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;",
                    "fillStartsForStructure",
                    "hasAnyStructureAt",
                    "getAllStructuresAt"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/LevelAccessor;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;)Lnet/minecraft/world/level/chunk/ChunkAccess;"
            )
    )
    private ChunkAccess dungeontrain$noSyncLoadOffThread(final LevelAccessor accessor, final int x, final int z,
                                                         final ChunkStatus status, final Operation<ChunkAccess> original) {
        final boolean isServerLevel = accessor instanceof ServerLevel;
        final boolean sameThread = isServerLevel && ((ServerLevel) accessor).getServer().isSameThread();
        if (!OffThreadStructureChunks.shouldBypass(isServerLevel, sameThread)) {
            return original.call(accessor, x, z, status);
        }
        final ServerLevel serverLevel = (ServerLevel) accessor;
        final ChunkAccess loaded = OffThreadStructureChunks.loadedChunk(serverLevel, x, z, status);
        if (loaded != null) {
            return loaded;
        }
        this.dungeontrain$skipNote.note(serverLevel, x, z);
        return this.dungeontrain$emptyChunk(serverLevel, x, z);
    }

    /** One stand-in per manager; its position is never read by the structure lookups. */
    @Unique
    private synchronized EmptyLevelChunk dungeontrain$emptyChunk(final ServerLevel serverLevel, final int x, final int z) {
        if (this.dungeontrain$emptyChunk == null) {
            final Holder<Biome> plains = serverLevel.registryAccess()
                    .registryOrThrow(Registries.BIOME)
                    .getHolderOrThrow(Biomes.PLAINS);
            this.dungeontrain$emptyChunk = new EmptyLevelChunk(serverLevel, new ChunkPos(x, z), plains);
        }
        return this.dungeontrain$emptyChunk;
    }
}
