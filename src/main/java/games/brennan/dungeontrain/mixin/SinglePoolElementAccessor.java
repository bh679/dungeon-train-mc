package games.brennan.dungeontrain.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads a pool element's template id and processor list — {@code LostCityFootprint} needs both at start time. */
@Mixin(SinglePoolElement.class)
public interface SinglePoolElementAccessor {

    @Accessor("template")
    Either<ResourceLocation, StructureTemplate> dungeontrain$template();

    @Accessor("processors")
    Holder<StructureProcessorList> dungeontrain$processors();
}
