package games.brennan.dungeontrain.editor.pool;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.editor.EditorLayout;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * A scratch box high in the sky where a template is stamped, re-captured in another frame, and
 * erased — how a full carriage becomes its interior and an interior becomes a full carriage.
 *
 * <p>In-world rather than an offline NBT merge: two palette-indexed templates would have to be
 * remapped and de-duplicated, which is new and untested surgery, whereas stamp-then-capture is how
 * every other capture in the mod already works. No stage scope on purpose — placeholders stay
 * placeholders so the template resolves its stage when it is stamped into a train.</p>
 */
public final class PoolScratch {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Behind the editor's rows, where no category lays a plot. */
    static final int SCRATCH_Z = -(EditorLayout.GAP * 8);

    /** The bundled shell each tier's contents are baked onto when they become a full box. */
    static final String HALF_DEFAULT_SHELL = "standard_half";
    static final String GROUP_DEFAULT_SHELL = "carriage";

    private PoolScratch() {}

    /** The id of the bundled shell {@code tier}'s contents are baked onto. */
    public static String defaultShellId(PoolTier tier) {
        return switch (tier) {
            case ROOM -> CarriageVariant.of(CarriagePlacer.CarriageType.STANDARD).id();
            case HALF -> HALF_DEFAULT_SHELL;
            case GROUP -> GROUP_DEFAULT_SHELL;
        };
    }

    /** The bundled shell contents of {@code tier} are baked onto; empty when this install lacks it. */
    public static Optional<StructureTemplate> defaultShell(ServerLevel level, PoolTier tier, CarriageDims box) {
        if (tier == PoolTier.ROOM) {
            return CarriageTemplateStore.get(level, CarriageVariant.of(CarriagePlacer.CarriageType.STANDARD), box);
        }
        return CarriageVariantRegistry.find(defaultShellId(tier)).flatMap(v -> CarriageTemplateStore.get(level, v, box));
    }

    /** Where a scratch box of {@code box} sits: at the top of the world, clear of every plot height. */
    static BlockPos origin(ServerLevel level, CarriageDims box) {
        return new BlockPos(0, level.getMaxBuildHeight() - box.height() - 2, SCRATCH_Z);
    }

    /**
     * Stamp {@code shell} and then {@code contents} inside it into a scratch box, capture the whole
     * box, erase it. Null when the contents are not the interior of {@code box}.
     */
    public static StructureTemplate bake(ServerLevel level, StructureTemplate shell, StructureTemplate contents,
                                         CarriageDims box) {
        Vec3i interior = CarriageContentsPlacer.interiorSize(box);
        if (!contents.getSize().equals(interior)) {
            Vec3i size = contents.getSize();
            LOGGER.warn("[DungeonTrain] Pool scratch: contents are {}x{}x{}, the interior is {}x{}x{} — cannot bake",
                size.getX(), size.getY(), size.getZ(), interior.getX(), interior.getY(), interior.getZ());
            return null;
        }
        if (!CarriagePlacer.sizeMatches(shell.getSize(), box)) {
            Vec3i size = shell.getSize();
            LOGGER.warn("[DungeonTrain] Pool scratch: shell is {}x{}x{}, the box is {}x{}x{} — cannot bake",
                size.getX(), size.getY(), size.getZ(), box.length(), box.height(), box.width());
            return null;
        }
        BlockPos scratch = origin(level, box);
        try {
            return CarriageStampGuard.call(() -> {
                CarriagePlacer.eraseAt(level, scratch, box);
                StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
                shell.placeInWorld(level, scratch, scratch, settings, level.getRandom(), CarriageStampGuard.STAMP_FLAGS);
                BlockPos inside = CarriageContentsPlacer.interiorOrigin(scratch);
                contents.placeInWorld(level, inside, inside, settings, level.getRandom(), CarriageStampGuard.STAMP_FLAGS);
                return CarriageEditor.captureTemplate(level, scratch, box);
            });
        } finally {
            CarriagePlacer.eraseAt(level, scratch, box);
        }
    }

    /**
     * Stamp a full-box template into the scratch box and capture its interior alone — the shell is
     * left behind. Null when {@code full} is not {@code box}-sized.
     */
    public static StructureTemplate interiorOf(ServerLevel level, StructureTemplate full, CarriageDims box) {
        if (!CarriagePlacer.sizeMatches(full.getSize(), box)) {
            Vec3i size = full.getSize();
            LOGGER.warn("[DungeonTrain] Pool scratch: template is {}x{}x{}, the box is {}x{}x{} — cannot take its interior",
                size.getX(), size.getY(), size.getZ(), box.length(), box.height(), box.width());
            return null;
        }
        BlockPos scratch = origin(level, box);
        try {
            return CarriageStampGuard.call(() -> {
                CarriagePlacer.eraseAt(level, scratch, box);
                StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
                full.placeInWorld(level, scratch, scratch, settings, level.getRandom(), CarriageStampGuard.STAMP_FLAGS);
                return CarriageContentsPlacer.captureTemplate(level, scratch, box);
            });
        } finally {
            CarriagePlacer.eraseAt(level, scratch, box);
        }
    }
}
