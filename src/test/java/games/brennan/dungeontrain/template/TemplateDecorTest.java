package games.brennan.dungeontrain.template;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two decisions {@link TemplateDecor} has to get right on paper: which saved entities a template
 * keeps, and where a kept one lands when the template is stamped somewhere else.
 *
 * <p>Both are the kind of mistake an in-game pass is bad at noticing — a mob quietly baked into every
 * stamp of a carriage, or an item frame whose {@code TileX/Y/Z} still points at the plot it was
 * authored in, which looks fine until the first block update pops it off the wall. Capture and spawn
 * themselves need a live level, so they are exercised in-game; these are the pure parts.</p>
 */
class TemplateDecorTest {

    /** One entry as {@code StructureTemplate.save} writes it: local {@code pos}, {@code blockPos}, {@code nbt}. */
    private static CompoundTag entry(String id, double x, double y, double z, int bx, int by, int bz) {
        CompoundTag e = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        e.put("pos", pos);
        e.putIntArray("blockPos", new int[]{bx, by, bz});
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", id);
        nbt.putInt("TileX", bx);
        nbt.putInt("TileY", by);
        nbt.putInt("TileZ", bz);
        e.put("nbt", nbt);
        return e;
    }

    private static CompoundTag saved(CompoundTag... entries) {
        ListTag list = new ListTag();
        for (CompoundTag e : entries) list.add(e);
        CompoundTag tag = new CompoundTag();
        tag.put("entities", list);
        return tag;
    }

    @Test
    void framesAndPaintingsAreDecorAndMobsAreNot() {
        assertTrue(TemplateDecor.isDecor(entry("minecraft:item_frame", 1, 2, 3, 1, 2, 3)));
        assertTrue(TemplateDecor.isDecor(entry("minecraft:glow_item_frame", 1, 2, 3, 1, 2, 3)));
        assertTrue(TemplateDecor.isDecor(entry("minecraft:painting", 1, 2, 3, 1, 2, 3)));
        assertFalse(TemplateDecor.isDecor(entry("minecraft:villager", 1, 2, 3, 1, 2, 3)));
        assertFalse(TemplateDecor.isDecor(entry("minecraft:armor_stand", 1, 2, 3, 1, 2, 3)));
    }

    @Test
    void filteringKeepsDecorAndDropsEverythingElse() {
        CompoundTag tag = saved(
            entry("minecraft:item_frame", 1, 2, 3, 1, 2, 3),
            entry("minecraft:villager", 4, 0, 4, 4, 0, 4),
            entry("minecraft:painting", 0, 3, 5, 0, 3, 5));

        assertTrue(TemplateDecor.filterEntities(tag), "a mob was present, so the tag needs reloading");
        ListTag kept = tag.getList("entities", Tag.TAG_COMPOUND);
        assertEquals(2, kept.size());
        assertEquals("minecraft:item_frame", kept.getCompound(0).getCompound("nbt").getString("id"));
        assertEquals("minecraft:painting", kept.getCompound(1).getCompound("nbt").getString("id"));
    }

    @Test
    void anAlreadyCleanListIsLeftAloneSoNoReloadIsPaidFor() {
        CompoundTag tag = saved(entry("minecraft:item_frame", 1, 2, 3, 1, 2, 3));
        assertFalse(TemplateDecor.filterEntities(tag));
        assertEquals(1, tag.getList("entities", Tag.TAG_COMPOUND).size());
    }

    @Test
    void aTemplateWithNoEntitiesAtAllNeedsNoReload() {
        assertFalse(TemplateDecor.filterEntities(new CompoundTag()));
    }

    @Test
    void rebaseMovesBothThePositionAndTheHangingAnchor() {
        CompoundTag e = entry("minecraft:item_frame", 1.5, 2.5, 3.5, 1, 2, 3);
        CompoundTag nbt = TemplateDecor.rebase(e, new Vec3(101.5, 66.5, -196.5), new BlockPos(101, 66, -197));

        ListTag pos = nbt.getList("Pos", Tag.TAG_DOUBLE);
        assertEquals(101.5, pos.getDouble(0), 1e-9);
        assertEquals(66.5, pos.getDouble(1), 1e-9);
        assertEquals(-196.5, pos.getDouble(2), 1e-9);

        // The anchor block, not the entity's own position: a frame nailed to the wrong block pops off.
        assertEquals(101, nbt.getInt("TileX"));
        assertEquals(66, nbt.getInt("TileY"));
        assertEquals(-197, nbt.getInt("TileZ"));
    }

    @Test
    void rebaseLeavesTheSavedEntryUntouchedSoOneTemplateCanStampManySites() {
        CompoundTag e = entry("minecraft:painting", 1, 2, 3, 1, 2, 3);
        TemplateDecor.rebase(e, new Vec3(1001, 2, 1003), new BlockPos(1001, 2, 1003));

        assertEquals(1, e.getCompound("nbt").getInt("TileX"),
            "the template's own copy must stay template-local");
        assertFalse(e.getCompound("nbt").contains("Pos"));
    }

    @Test
    void everyStampGetsItsOwnIdentity() {
        CompoundTag e = entry("minecraft:item_frame", 1, 2, 3, 1, 2, 3);
        Vec3 at = new Vec3(1, 2, 3);
        // Two stamps of one template: sharing a UUID means MC drops all but the first.
        assertNotEquals(TemplateDecor.rebase(e, at, BlockPos.ZERO).getUUID("UUID"),
            TemplateDecor.rebase(e, at, BlockPos.ZERO).getUUID("UUID"));
    }

    @Test
    void anEntryWithoutAHangingAnchorIsRebasedOnPositionAlone() {
        CompoundTag e = entry("minecraft:painting", 1, 2, 3, 1, 2, 3);
        e.getCompound("nbt").remove("TileX");
        e.getCompound("nbt").remove("TileY");
        e.getCompound("nbt").remove("TileZ");

        CompoundTag nbt = TemplateDecor.rebase(e, new Vec3(11, 12, 13), null);
        assertFalse(nbt.contains("TileX"));
        assertEquals(11.0, nbt.getList("Pos", Tag.TAG_DOUBLE).getDouble(0), 1e-9);
    }
}
