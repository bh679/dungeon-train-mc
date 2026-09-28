package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link FrozenMobs#thaw}: a mob frozen by the editor's Mobs | Blocks setting must come
 * alive when its template is stamped onto a real train — the freeze flags ride the saved NBT, so
 * this strip is the only thing between an authored statue and a live mob. An entity that does not
 * carry the {@link FrozenMobs#TAG} is not the editor's to touch.
 */
final class FrozenMobsThawTest {

    private static CompoundTag frozenZombie(String... extraTags) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:zombie");
        nbt.putBoolean("NoAI", true);
        nbt.putBoolean("Silent", true);
        nbt.putBoolean("Invulnerable", true);
        nbt.putBoolean("PersistenceRequired", true);
        nbt.putString("CustomName", "{\"text\":\"Bob\"}");
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(FrozenMobs.TAG));
        for (String t : extraTags) tags.add(StringTag.valueOf(t));
        nbt.put("Tags", tags);
        return nbt;
    }

    @Test
    void thawStripsFreezeFlagsAndTag() {
        CompoundTag out = FrozenMobs.thaw(frozenZombie());
        assertFalse(out.contains("NoAI"));
        assertFalse(out.contains("Silent"));
        assertFalse(out.contains("Invulnerable"));
        assertFalse(out.contains("Tags"), "the only tag was ours — drop the empty list");
        // Authored intent survives.
        assertEquals("minecraft:zombie", out.getString("id"));
        assertEquals("{\"text\":\"Bob\"}", out.getString("CustomName"));
        assertTrue(out.getBoolean("PersistenceRequired"));
    }

    @Test
    void thawKeepsOtherTags() {
        CompoundTag out = FrozenMobs.thaw(frozenZombie("author_tag"));
        ListTag tags = out.getList("Tags", Tag.TAG_STRING);
        assertEquals(1, tags.size());
        assertEquals("author_tag", tags.getString(0));
    }

    @Test
    void thawDoesNotMutateInput() {
        CompoundTag in = frozenZombie();
        FrozenMobs.thaw(in);
        assertTrue(in.getBoolean("NoAI"), "input must be left as-is (new tag returned)");
    }

    @Test
    void untaggedNbtIsReturnedUntouched() {
        CompoundTag in = new CompoundTag();
        in.putString("id", "minecraft:zombie");
        in.putBoolean("NoAI", true); // an author's own NoAI egg is theirs to keep
        assertSame(in, FrozenMobs.thaw(in));
        assertTrue(in.getBoolean("NoAI"));
    }
}
