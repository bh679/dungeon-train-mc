package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pure counting half of the template data sheet. */
final class TemplateCellsTallyTest {

    private static CompoundTag withItems() {
        CompoundTag tag = new CompoundTag();
        tag.put("Items", new ListTag());
        return tag;
    }

    @Test
    @DisplayName("every tag is a block entity; only tags with an Items list are containers")
    void tallyCountsContainersAmongBlockEntities() {
        List<CompoundTag> tags = Arrays.asList(new CompoundTag(), withItems(), withItems(), null);
        TemplateCells.NbtTally tally = TemplateCells.tallyNbt(tags);
        assertEquals(4, tally.blockEntities());
        assertEquals(2, tally.containers());
    }

    @Test
    @DisplayName("no tags means no block entities")
    void emptyTally() {
        assertEquals(new TemplateCells.NbtTally(0, 0), TemplateCells.tallyNbt(List.of()));
    }

    @Test
    @DisplayName("entity count reads the entities list and tolerates its absence")
    void entityCount() {
        assertEquals(0, TemplateCells.entityCount(null));
        assertEquals(0, TemplateCells.entityCount(new CompoundTag()));
        CompoundTag tag = new CompoundTag();
        ListTag entities = new ListTag();
        entities.add(new CompoundTag());
        entities.add(new CompoundTag());
        tag.put("entities", entities);
        assertEquals(2, TemplateCells.entityCount(tag));
    }
}
