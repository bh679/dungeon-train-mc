package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Reads a template's palettes — {@code LostCityFootprint} plans a stretch on the template's own blocks —
 * and its entities ({@code PortalChunkStampTrim}).
 */
@Mixin(StructureTemplate.class)
public interface StructureTemplateAccessor {

    @Accessor("palettes")
    List<StructureTemplate.Palette> dungeontrain$palettes();

    /** The template's saved entities — a chunk room's stamp lays its blocks itself only when there are none. */
    @Accessor("entityInfoList")
    List<StructureTemplate.StructureEntityInfo> dungeontrain$entityInfoList();
}
