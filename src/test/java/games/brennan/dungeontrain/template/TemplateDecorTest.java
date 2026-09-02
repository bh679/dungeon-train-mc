package games.brennan.dungeontrain.template;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    /**
     * An entry for a living entity — {@link #entry} plus the {@code Health} value every
     * {@code LivingEntity} writes, which is what {@code TemplateDecor.carries} reads.
     */
    private static CompoundTag living(String id, double x, double y, double z) {
        CompoundTag e = entry(id, x, y, z, (int) x, (int) y, (int) z);
        e.getCompound("nbt").putFloat("Health", 20.0f);
        return e;
    }

    private static CompoundTag entry(String id, double x, double y, double z, int bx, int by, int bz) {
        CompoundTag e = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        e.put("pos", pos);
        // A LIST of ints, which is what StructureTemplate.save writes — an int array here would be a
        // format this test invented, and would hide anchorOf reading the wrong tag type.
        ListTag blockPos = new ListTag();
        blockPos.add(IntTag.valueOf(bx));
        blockPos.add(IntTag.valueOf(by));
        blockPos.add(IntTag.valueOf(bz));
        e.put("blockPos", blockPos);
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
    void decorAndLivingEntitiesAreCarriedAndLitterIsNot() {
        assertTrue(TemplateDecor.isDecor(entry("minecraft:item_frame", 1, 2, 3, 1, 2, 3)));
        assertTrue(TemplateDecor.isDecor(entry("minecraft:glow_item_frame", 1, 2, 3, 1, 2, 3)));
        assertTrue(TemplateDecor.isDecor(entry("minecraft:painting", 1, 2, 3, 1, 2, 3)));

        // A mob an author stands in a plot IS part of the build — the editor world switches natural
        // spawning off so nothing else can wander in and be saved alongside it. Recognised by the
        // Health tag every LivingEntity saves, which is what a real capture writes.
        assertTrue(TemplateDecor.isDecor(living("minecraft:villager", 1, 2, 3)));
        assertTrue(TemplateDecor.isDecor(living("minecraft:parrot", 1, 2, 3)));
        assertTrue(TemplateDecor.isDecor(living("minecraft:zombie", 1, 2, 3)));

        // Both of these are MobCategory.MISC, so the tempting "carried unless MISC" rule drops them
        // both. That rule was written; the villager above is what caught it.
        assertTrue(TemplateDecor.isDecor(living("minecraft:armor_stand", 1, 2, 3)),
            "an armor stand is MISC, and still authored content");

        // A plot's litter, not its content: none of these save a Health value.
        assertFalse(TemplateDecor.isDecor(entry("minecraft:minecart", 1, 2, 3, 1, 2, 3)));
        assertFalse(TemplateDecor.isDecor(entry("minecraft:boat", 1, 2, 3, 1, 2, 3)));
        assertFalse(TemplateDecor.isDecor(entry("minecraft:item", 1, 2, 3, 1, 2, 3)));
        assertFalse(TemplateDecor.isDecor(entry("minecraft:arrow", 1, 2, 3, 1, 2, 3)));
        assertFalse(TemplateDecor.isDecor(entry("minecraft:experience_orb", 1, 2, 3, 1, 2, 3)));

        // A tag with neither a decor id nor a Health value — an entity type this build has never
        // heard of, saved by a newer one — is refused rather than let into a template.
        assertFalse(TemplateDecor.carries(new CompoundTag()));
        assertFalse(TemplateDecor.carries(null));
    }

    @Test
    void filteringKeepsWhatIsCarriedAndDropsTheRest() {
        CompoundTag tag = saved(
            entry("minecraft:item_frame", 1, 2, 3, 1, 2, 3),
            living("minecraft:villager", 4, 0, 4),
            entry("minecraft:minecart", 2, 0, 2, 2, 0, 2),
            entry("minecraft:painting", 0, 3, 5, 0, 3, 5));

        assertTrue(TemplateDecor.filterEntities(tag),
            "a minecart was present, so the tag needs reloading");
        ListTag kept = tag.getList("entities", Tag.TAG_COMPOUND);
        assertEquals(3, kept.size(), "the mob stays; only the minecart goes");
        assertEquals("minecraft:item_frame", kept.getCompound(0).getCompound("nbt").getString("id"));
        assertEquals("minecraft:villager", kept.getCompound(1).getCompound("nbt").getString("id"));
        assertEquals("minecraft:painting", kept.getCompound(2).getCompound("nbt").getString("id"));
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

    @Test
    void theHangingAnchorIsReadOffTheEntryAndRebasedOntoTheStampOrigin() {
        CompoundTag e = entry("minecraft:painting", 1.5, 2.0, 3.5, 1, 2, 3);
        BlockPos anchor = TemplateDecor.anchorOf(
            e, Mirror.NONE, Rotation.NONE, BlockPos.ZERO, new BlockPos(100, 64, -200));

        // Template-local (1,2,3) stamped at (100,64,-200). A null here is the failure that put every
        // picture back at the coordinates it was authored at.
        assertEquals(new BlockPos(101, 66, -197), anchor);
    }

    @Test
    void anEntryCarryingNoAnchorAtAllReadsBackAsNone() {
        CompoundTag e = entry("minecraft:item_frame", 1, 2, 3, 1, 2, 3);
        e.remove("blockPos");
        assertNull(TemplateDecor.anchorOf(
            e, Mirror.NONE, Rotation.NONE, BlockPos.ZERO, new BlockPos(100, 64, -200)));
    }

    @Test
    void stampingSomewhereElseMovesTheAnchorByTheFullOriginDelta() {
        // The portal-room case: one authored template stamped as many copies. Two different origins
        // must not produce the same anchor, or every copy hangs its decor over the first copy.
        CompoundTag e = entry("minecraft:item_frame", 1, 2, 3, 1, 2, 3);
        BlockPos a = TemplateDecor.anchorOf(
            e, Mirror.NONE, Rotation.NONE, BlockPos.ZERO, new BlockPos(0, 64, 0));
        BlockPos b = TemplateDecor.anchorOf(
            e, Mirror.NONE, Rotation.NONE, BlockPos.ZERO, new BlockPos(48, 64, 0));

        assertNotEquals(a, b);
        assertEquals(48, b.getX() - a.getX());
    }
}
