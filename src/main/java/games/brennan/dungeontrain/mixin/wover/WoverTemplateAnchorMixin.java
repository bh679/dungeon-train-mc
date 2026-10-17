package games.brennan.dungeontrain.mixin.wover;

import games.brennan.dungeontrain.worldgen.feature.NetherCoreDecoration;
import games.brennan.dungeontrain.worldgen.feature.TemplateAnchorKeepProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.betterx.wover.structure.api.StructureNBT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Inside Dungeon Train's Nether-core feature pass, every WorldWeaver template stamp (BetterNether's
 * prebuilt trees, {@code wover:template}) goes through {@link TemplateAnchorKeepProcessor}, so a tree
 * stamped over another never replaces the first tree's vine anchors with wall moss. WorldWeaver builds a
 * fresh {@link StructurePlaceSettings} with no processors right before {@code placeInWorld}; this is the
 * one seam that sees it. Outside the core pass ({@link NetherCoreDecoration#isActive()} false) the call
 * is untouched, so BetterNether's own Nether keeps its behaviour.
 */
@Mixin(value = StructureNBT.class, remap = false)
public abstract class WoverTemplateAnchorMixin {

    @Redirect(method = { "generateCentered", "generateAt" }, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;placeInWorld("
            + "Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;"
            + "Lnet/minecraft/util/RandomSource;I)Z"))
    private boolean dungeontrain$keepAnchorsInCore(StructureTemplate template, ServerLevelAccessor level,
                                                    BlockPos pos, BlockPos pivot, StructurePlaceSettings settings,
                                                    RandomSource random, int flags) {
        if (NetherCoreDecoration.isActive()) {
            settings.addProcessor(TemplateAnchorKeepProcessor.INSTANCE);
        }
        return template.placeInWorld(level, pos, pivot, settings, random, flags);
    }
}
