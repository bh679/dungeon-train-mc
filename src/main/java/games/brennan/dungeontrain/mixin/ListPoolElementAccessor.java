package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Reads a list element's parts — {@code LostCityTemplateIds} walks them for the templates they place. */
@Mixin(ListPoolElement.class)
public interface ListPoolElementAccessor {

    @Accessor("elements")
    List<StructurePoolElement> dungeontrain$elements();
}
