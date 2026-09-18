package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Stamps and captures a {@link CarriageGroup} — a whole run of carriages as one template.
 *
 * <p>The sibling of {@link WholeCarriagePlacer}, over a longer box. Both erase before they stamp, and
 * both ignore entities so a capture and a placement are the same operation in reverse; the difference
 * is that a group's box spans {@code carriages × length}, so it is erased and written in one pass
 * rather than per carriage. Doing it per carriage would be the same blocks in the same places — right
 * up until a build straddles a carriage boundary, which from the platform is an ordinary thing to
 * make.</p>
 */
public final class CarriageGroupPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CarriageGroupPlacer() {}

    /**
     * Stamp {@code group} at {@code origin} — the first carriage's shell anchor, so a group lands
     * exactly where its carriages would individually.
     *
     * @param carriages how many carriages this train's group holds; a template that holds a different
     *                  number is refused by the store rather than stamped short or long
     * @return false when there is no readable template for this world's shape, so the caller can
     *         refuse the open rather than leave a half-stamped run
     */
    public static boolean placeAt(ServerLevel level, BlockPos origin, CarriageGroup group,
                                  CarriageDims dims, int carriages) {
        Optional<StructureTemplate> template =
                CarriageGroupTemplateStore.get(level, group, dims, carriages);
        if (template.isEmpty()) {
            LOGGER.warn("[DungeonTrain] No carriage-group template for '{}' at {} carriage(s) — placing nothing.",
                    group.id(), carriages);
            return false;
        }
        CarriageStampGuard.run(() -> {
            eraseAt(level, origin, dims, carriages);
            StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
            template.get().placeInWorld(level, origin, origin, settings, level.getRandom(), CarriageStampGuard.STAMP_FLAGS);
        });
        return true;
    }

    /**
     * The train path: stamp {@code template} over a {@code carriages}-long run, section-local with no
     * relight, and return the footprint the shipyard assembles. No erase — the caller has already
     * cleared the sub-level volume. The caller holds the stage scope.
     */
    public static java.util.Set<BlockPos> placeForTrain(ServerLevel level, BlockPos origin,
                                                        StructureTemplate template, CarriageDims dims,
                                                        int carriages) {
        return CarriageStampGuard.call(() -> {
            CarriagePlacer.stampTemplateAt(level, origin, template, /*relight*/ false);
            java.util.Set<BlockPos> placed = new java.util.HashSet<>();
            for (int i = 0; i < Math.max(1, carriages); i++) {
                placed.addAll(CarriagePlacer.collectFootprint(level, origin.offset(i * dims.length(), 0, 0), dims));
            }
            return placed;
        });
    }

    /**
     * Capture the whole run at {@code origin}.
     *
     * <p>{@code fillFromWorld} against AIR over the group's box, which is what every carriage-side
     * capture does — {@link CarriageEditor#captureTemplate} is the same call over one carriage's worth
     * of it. Entities are left out, matching the placement above.</p>
     */
    public static StructureTemplate captureTemplate(ServerLevel level, BlockPos origin,
                                                    CarriageDims dims, int carriages) {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, origin, sizeOf(dims, carriages), false,
                net.minecraft.world.level.block.Blocks.AIR);
        return template;
    }

    /** Clear the run to air, so a stamp replaces what was there rather than merging with it. */
    public static void eraseAt(ServerLevel level, BlockPos origin, CarriageDims dims, int carriages) {
        for (int i = 0; i < Math.max(1, carriages); i++) {
            CarriagePlacer.eraseAt(level, origin.offset(i * dims.length(), 0, 0), dims);
        }
    }

    /** The box a group of {@code carriages} occupies at {@code dims}. */
    public static Vec3i sizeOf(CarriageDims dims, int carriages) {
        return new Vec3i(dims.length() * Math.max(1, carriages), dims.height(), dims.width());
    }
}
