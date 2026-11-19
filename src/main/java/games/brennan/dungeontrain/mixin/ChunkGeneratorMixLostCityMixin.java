package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.LostCityMixStarts;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Names the level whose chunk {@code createStructures} is filling, so {@link StructurePlacementMixLostCityMixin}
 * can start a Lost City building in every mix-zone chunk that picked it ({@link LostCityMixStarts}).
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixLostCityMixin {

    @Inject(method = "createStructures", at = @At("HEAD"))
    private void dungeontrain$nameLevel(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                        StructureManager structureManager, ChunkAccess chunk,
                                        StructureTemplateManager structureTemplateManager, CallbackInfo ci) {
        LostCityMixStarts.creating(((ChunkAccessAccessor) chunk).dungeontrain$getLevelHeightAccessor()
                instanceof ServerLevel level ? level : null);
    }

    @Inject(method = "createStructures", at = @At("RETURN"))
    private void dungeontrain$clearLevel(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                         StructureManager structureManager, ChunkAccess chunk,
                                         StructureTemplateManager structureTemplateManager, CallbackInfo ci) {
        LostCityMixStarts.creating(null);
    }
}
