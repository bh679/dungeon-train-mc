package games.brennan.dungeontrain.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads a jigsaw structure's configuration so the band can (a) recognise the bastion by its start pool —
 * {@link JigsawStructure} is shared with villages, pillager outposts and trial chambers, so the class alone
 * says nothing — and (b) re-site it without duplicating the datapack values.
 */
@Mixin(JigsawStructure.class)
public interface JigsawStructureAccessor {

    @Accessor("startPool")
    Holder<StructureTemplatePool> dungeontrain$startPool();

    @Accessor("maxDepth")
    int dungeontrain$maxDepth();

    @Accessor("startHeight")
    HeightProvider dungeontrain$startHeight();

    @Accessor("useExpansionHack")
    boolean dungeontrain$useExpansionHack();

    @Accessor("maxDistanceFromCenter")
    int dungeontrain$maxDistanceFromCenter();
}
