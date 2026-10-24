package games.brennan.dungeontrain.editor;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

/**
 * The extra entity NBT a spawn egg carries, for the editor's mob-variant capture (shift-click and the
 * block-variant menu's add). Read from {@code BLOCK_ENTITY_DATA} (an anvil-renamed / NBT-given egg,
 * {@code EntityTag} or flat), else from {@code ENTITY_DATA} (an egg that ships a payload, such as
 * {@code dungeontrain:buckler_piglin_brute_spawn_egg}). The {@code id} is stripped: the egg's entity
 * type is authoritative and {@code EntityType.create} writes its own. Null when the egg carries nothing
 * beyond its id, which keeps a plain egg's variant on the default-equipment path.
 */
public final class SpawnEggMobNbt {

    private SpawnEggMobNbt() {}

    public static @Nullable CompoundTag read(ItemStack egg) {
        CompoundTag mobNbt = null;
        CustomData beData = egg.get(DataComponents.BLOCK_ENTITY_DATA);
        if (beData != null && !beData.isEmpty()) {
            CompoundTag raw = beData.copyTag();
            mobNbt = raw.contains("EntityTag", Tag.TAG_COMPOUND) ? raw.getCompound("EntityTag").copy() : raw;
        } else {
            CustomData entityData = egg.get(DataComponents.ENTITY_DATA);
            if (entityData != null && !entityData.isEmpty()) mobNbt = entityData.copyTag();
        }
        if (mobNbt == null) return null;
        mobNbt.remove("id");
        return mobNbt.isEmpty() ? null : mobNbt;
    }
}
