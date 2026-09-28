package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * The common shape of a Dungeon Train template stamp at ordinary world coordinates: the block pass
 * under {@link CarriageStampGuard} with {@link CarriageStampGuard#STAMP_FLAGS}, optionally followed
 * by the {@link TemplateDecor} entity pass. The editor plot loaders (parts, tiles, pillars) and the
 * builder / persistence restores all do exactly this; routing them here keeps the flag choice —
 * and its Fast Paintings rationale — in one place.
 *
 * <p>Stamps that need to erase inside the guard, pass a bounding box, or use a non-default
 * {@link TemplateDecor.Rule} keep their own call and reference the constant directly.</p>
 */
public final class TemplateStamp {

    private TemplateStamp() {}

    /** Block pass only: {@code template.placeInWorld} at {@code origin} under the guard. */
    public static void place(ServerLevel level, BlockPos origin, StructureTemplate template,
                             StructurePlaceSettings settings) {
        CarriageStampGuard.run(() -> template.placeInWorld(
            level, origin, origin, settings, level.getRandom(), CarriageStampGuard.STAMP_FLAGS));
    }

    /** {@link #place} followed by the template's item frames and paintings via {@link TemplateDecor}. */
    public static void placeWithDecor(ServerLevel level, BlockPos origin, StructureTemplate template,
                                      StructurePlaceSettings settings) {
        place(level, origin, template, settings);
        TemplateDecor.replace(level, origin, template, settings, null);
    }
}
