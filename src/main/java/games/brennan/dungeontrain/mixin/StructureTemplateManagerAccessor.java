package games.brennan.dungeontrain.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Optional;

/**
 * Reads the template cache — {@code LostCityTemplatePreloadEvents} evicts the Lost City's entries after the run,
 * and {@link StructureTemplateManager#remove} returns nothing to count them by.
 */
@Mixin(StructureTemplateManager.class)
public interface StructureTemplateManagerAccessor {

    @Accessor("structureRepository")
    Map<ResourceLocation, Optional<StructureTemplate>> dungeontrain$structureRepository();
}
