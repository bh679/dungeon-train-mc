package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.WorldFloor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Drops a structure whose every piece would sit below the bedrock layer.
 *
 * <p>{@link WorldGenRegionFloorMixin} already refuses the blocks, but on its own that leaves the
 * <i>start</i> registered: {@code /locate} would point at empty basement, the chunk would carry
 * structure references forever, and structure-bounded mob spawning would keep firing over nothing.
 * Declining the start is also the cheaper answer — none of the pieces get placed at all.</p>
 *
 * <p>Only a structure entirely under the floor is dropped. One that straddles it keeps its start and
 * is clipped write-by-write, so a tall structure rooted just under bedrock still shows the part of
 * itself that reaches real terrain.</p>
 *
 * <p>In a world without a basement {@code bedrockY} is the build floor, nothing generates below it,
 * and this never fires.</p>
 */
@Mixin(Structure.class)
public abstract class StructureBasementMixin {

    /**
     * Structures the sunk zone (the Amplified band and its approach) never gets. Both are pinned to
     * absolute depths that only reached the basement before the band sank; sunk, they would surface in
     * its valleys — and they belong to the ordinary overworld's depths, not to this band.
     */
    @Unique
    private static boolean dungeontrain$excludedFromSunkZone(ResourceLocation id) {
        String s = id.toString();
        return s.equals("minecraft:ancient_city") || s.equals("minecraft:trial_chambers");
    }

    @Inject(method = "generate", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$dropBasementStarts(RegistryAccess registryAccess, ChunkGenerator chunkGenerator,
                                                 BiomeSource biomeSource, RandomState randomState,
                                                 StructureTemplateManager structureTemplateManager, long seed,
                                                 ChunkPos chunkPos, int references, LevelHeightAccessor heightAccessor,
                                                 Predicate<Holder<Biome>> validBiome,
                                                 CallbackInfoReturnable<StructureStart> cir) {
        StructureStart start = cir.getReturnValue();
        if (start == null || !start.isValid()) {
            return;
        }
        int floorY = WorldFloor.bedrockY(heightAccessor, chunkGenerator);
        // The sunk zone's terrain reaches into the basement, so a deep start there is real — except the
        // deep-dark and trial-chamber structures, which are left out of the sunk bands on purpose.
        if (heightAccessor instanceof ChunkAccess chunk
                && ((ChunkAccessAccessor) chunk).dungeontrain$getLevelHeightAccessor() instanceof ServerLevel level
                && level.getChunkSource().getGenerator() == chunkGenerator) {
            int sunkFloor = WorldFloor.terrainFloorY(level, chunkPos.x, chunkPos.z);
            if (sunkFloor < floorY) {
                ResourceLocation id = registryAccess.registryOrThrow(Registries.STRUCTURE)
                        .getKey((Structure) (Object) this);
                if (id != null && dungeontrain$excludedFromSunkZone(id)) {
                    cir.setReturnValue(StructureStart.INVALID_START);
                    return;
                }
                floorY = sunkFloor;
            }
        }
        if (WorldFloor.entirelyBelowFloor(start.getBoundingBox().maxY(), floorY)) {
            cir.setReturnValue(StructureStart.INVALID_START);
        }
    }
}
