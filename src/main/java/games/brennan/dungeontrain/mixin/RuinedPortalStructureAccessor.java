package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Reads a ruined portal's {@code setups} so the band can tell the <b>Nether</b> variant from the six
 * overworld ones. They all share {@link RuinedPortalStructure}, and only the Nether variant belongs in the
 * band core — without this the band would happily stand a jungle portal in the netherrack.
 */
@Mixin(RuinedPortalStructure.class)
public interface RuinedPortalStructureAccessor {

    @Accessor("setups")
    List<RuinedPortalStructure.Setup> dungeontrain$setups();
}
