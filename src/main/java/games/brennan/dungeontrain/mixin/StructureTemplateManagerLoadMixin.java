package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import games.brennan.dungeontrain.worldgen.LostCityStructures;
import games.brennan.dungeontrain.worldgen.LostCityTemplateLoads;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Optional;

/**
 * Times every lookup of a Big Lost City template that misses the cache ({@link LostCityTemplateLoads}): the
 * caller either loads and datafixes the template itself or waits for the thread that is, and both are the
 * stall {@code LostCityTemplatePreloadEvents} exists to keep off worldgen threads. A hit — every other
 * template, and these once loaded — passes straight through.
 */
@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerLoadMixin {

    @Shadow
    @Final
    private Map<ResourceLocation, Optional<StructureTemplate>> structureRepository;

    @WrapMethod(method = "get")
    private Optional<StructureTemplate> dungeontrain$timeColdLostCityLookup(ResourceLocation id,
                                                                            Operation<Optional<StructureTemplate>> original) {
        if (!LostCityStructures.NAMESPACE.equals(id.getNamespace()) || this.structureRepository.containsKey(id)) {
            return original.call(id);
        }
        long t0 = System.nanoTime();
        try {
            return original.call(id);
        } finally {
            LostCityTemplateLoads.record(id, System.nanoTime() - t0);
        }
    }
}
