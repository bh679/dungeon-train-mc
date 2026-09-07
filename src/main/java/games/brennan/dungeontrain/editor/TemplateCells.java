package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@link StructureTemplate}'s blocks out as positions and states.
 *
 * <p>Vanilla keeps the palette list private and this mod ships no access transformer, so getting at
 * it means reflection. Three places already do it —
 * {@code TrackTemplateStore.extractCells}, {@code PillarTemplateStore.extractColumn} and
 * {@code StageBlockIndex.palettesOf} — each with its own cached {@link Field} and its own
 * log-and-degrade path, a duplication the last of those already apologises for in its javadoc. This
 * is the one that new callers should use instead of becoming a fourth.</p>
 *
 * <p>Deliberately shaped as a map rather than a fixed array: the existing three each return the
 * shape their own caller wanted ({@code [x][y][z]}, {@code [y][z]}), which is exactly why none of
 * them could be reused by the next caller along. A position-keyed map fits any footprint, and an
 * absent key means the template authored air there — the same convention all three already use for
 * their nulls.</p>
 */
public final class TemplateCells {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Field palettesField;

    private TemplateCells() {}

    /**
     * Every non-air block in {@code template}, keyed by its position local to the template.
     *
     * <p>Empty when the template is null or the reflection fails — degrading to "no blocks" rather
     * than throwing, because every caller of this is drawing a preview or an index, and neither is
     * worth taking a world down for.</p>
     */
    public static Map<BlockPos, BlockState> of(StructureTemplate template) {
        if (template == null) {
            return Map.of();
        }
        List<StructureTemplate.Palette> palettes = palettesOf(template);
        if (palettes.isEmpty()) {
            return Map.of();
        }
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
            if (info.state() != null && !info.state().isAir()) {
                cells.put(info.pos(), info.state());
            }
        }
        return cells;
    }

    /** What a template's block-entity NBT adds up to: how many blocks carry any, and how many hold items. */
    public record NbtTally(int blockEntities, int containers) {}

    /**
     * Tally the block-entity tags of a template's first palette.
     *
     * <p>Every block with a tag is a block entity; one whose tag carries an {@code Items} list is
     * a container. That covers chests, barrels, shulker boxes, hoppers, furnaces and the like
     * without naming any of them.</p>
     */
    public static NbtTally tallyBlockEntities(StructureTemplate template) {
        if (template == null) return new NbtTally(0, 0);
        List<StructureTemplate.Palette> palettes = palettesOf(template);
        if (palettes.isEmpty()) return new NbtTally(0, 0);
        List<CompoundTag> tags = new java.util.ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
            if (info.nbt() != null) tags.add(info.nbt());
        }
        return tallyNbt(tags);
    }

    /** The pure half of {@link #tallyBlockEntities}: count tags, and tags with an {@code Items} list. */
    public static NbtTally tallyNbt(List<CompoundTag> tags) {
        int containers = 0;
        for (CompoundTag tag : tags) {
            if (tag != null && tag.contains("Items", Tag.TAG_LIST)) containers++;
        }
        return new NbtTally(tags.size(), containers);
    }

    /** How many entities a raw template tag carries — the {@code entities} list's length. */
    public static int entityCount(CompoundTag templateTag) {
        if (templateTag == null || !templateTag.contains("entities", Tag.TAG_LIST)) return 0;
        return templateTag.getList("entities", Tag.TAG_COMPOUND).size();
    }

    @SuppressWarnings("unchecked")
    private static List<StructureTemplate.Palette> palettesOf(StructureTemplate template) {
        try {
            Field field = palettesField;
            if (field == null) {
                field = StructureTemplate.class.getDeclaredField("palettes");
                field.setAccessible(true);
                palettesField = field;
            }
            List<StructureTemplate.Palette> palettes =
                    (List<StructureTemplate.Palette>) field.get(template);
            return palettes == null ? List.of() : palettes;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // A mapping change on a Minecraft upgrade lands here. Same log-and-degrade the other
            // three walkers use, so an upgrade loses previews rather than crashing.
            LOGGER.error("[DungeonTrain] Unable to read template palettes: {}", e.toString());
            return List.of();
        }
    }
}
