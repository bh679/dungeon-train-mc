package games.brennan.dungeontrain.player;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NBT shapes for {@link PlayerRunState#napExtras}: the run-state fields the attachment codec has no
 * room for, written into a nap so the woken life keeps them. Every reader tolerates a missing or
 * malformed entry by skipping it, so a nap from an older build wakes with what it has.
 */
final class NapExtrasNbt {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NapExtrasNbt() {}

    static DynamicOps<Tag> ops(HolderLookup.Provider registries) {
        return registries == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, registries);
    }

    static ListTag strings(Collection<String> values) {
        ListTag list = new ListTag();
        values.forEach(v -> list.add(StringTag.valueOf(v)));
        return list;
    }

    static List<String> strings(CompoundTag tag, String key) {
        List<String> out = new ArrayList<>();
        tag.getList(key, Tag.TAG_STRING).forEach(t -> out.add(t.getAsString()));
        return out;
    }

    static ListTag ids(Collection<ResourceLocation> ids) {
        return strings(ids.stream().map(ResourceLocation::toString).toList());
    }

    static List<ResourceLocation> ids(CompoundTag tag, String key) {
        List<ResourceLocation> out = new ArrayList<>();
        for (String s : strings(tag, key)) {
            ResourceLocation id = ResourceLocation.tryParse(s);
            if (id != null) out.add(id);
        }
        return out;
    }

    static ListTag uuids(Collection<UUID> uuids) {
        ListTag list = new ListTag();
        uuids.forEach(u -> list.add(NbtUtils.createUUID(u)));
        return list;
    }

    static List<UUID> uuids(CompoundTag tag, String key) {
        List<UUID> out = new ArrayList<>();
        for (Tag t : tag.getList(key, Tag.TAG_INT_ARRAY)) {
            if (t instanceof IntArrayTag a && a.size() == 4) out.add(NbtUtils.loadUUID(a));
        }
        return out;
    }

    /** {@code int → int} as a compound keyed by the decimal key. */
    static CompoundTag intMap(Map<Integer, Integer> map) {
        CompoundTag out = new CompoundTag();
        map.forEach((k, v) -> out.putInt(Integer.toString(k), v));
        return out;
    }

    static Map<Integer, Integer> intMap(CompoundTag tag, String key) {
        CompoundTag in = tag.getCompound(key);
        Map<Integer, Integer> out = new HashMap<>();
        for (String k : in.getAllKeys()) {
            try {
                out.put(Integer.parseInt(k), in.getInt(k));
            } catch (NumberFormatException e) {
                LOGGER.debug("[DungeonTrain] Nap extras: skipped served-book key {}", k);
            }
        }
        return out;
    }

    static ListTag frames(List<Frame> frames, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        DynamicOps<Tag> ops = ops(registries);
        for (Frame frame : frames) {
            Frame.CODEC.encodeStart(ops, frame)
                .resultOrPartial(e -> LOGGER.warn("[DungeonTrain] Nap extras: camera frame not carried: {}", e))
                .ifPresent(list::add);
        }
        return list;
    }

    static List<Frame> frames(CompoundTag tag, String key, HolderLookup.Provider registries) {
        List<Frame> out = new ArrayList<>();
        DynamicOps<Tag> ops = ops(registries);
        for (Tag t : tag.getList(key, Tag.TAG_COMPOUND)) {
            Frame.CODEC.parse(ops, t)
                .resultOrPartial(e -> LOGGER.warn("[DungeonTrain] Nap extras: camera frame not read: {}", e))
                .ifPresent(out::add);
        }
        return out;
    }

    private static final String[] SLOTS = {"mainHand", "offHand", "head", "chest", "legs", "feet"};

    static CompoundTag appearance(PlayerMobAppearance a, HolderLookup.Provider registries) {
        CompoundTag out = new CompoundTag();
        out.putInt("skinIndex", a.skinIndex());
        out.putString("skinTextureUrl", a.skinTextureUrl());
        out.putBoolean("slim", a.slim());
        out.putString("name", a.name());
        ItemStack[] stacks = {a.mainHand(), a.offHand(), a.head(), a.chest(), a.legs(), a.feet()};
        DynamicOps<Tag> ops = ops(registries);
        for (int i = 0; i < SLOTS.length; i++) {
            String slot = SLOTS[i];
            ItemStack.OPTIONAL_CODEC.encodeStart(ops, stacks[i])
                .resultOrPartial(e -> LOGGER.warn("[DungeonTrain] Nap extras: portrait {} not carried: {}", slot, e))
                .ifPresent(t -> out.put(slot, t));
        }
        return out;
    }

    /** Null when {@code key} holds no appearance. */
    static PlayerMobAppearance appearance(CompoundTag tag, String key, HolderLookup.Provider registries) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) return null;
        CompoundTag in = tag.getCompound(key);
        DynamicOps<Tag> ops = ops(registries);
        ItemStack[] stacks = new ItemStack[SLOTS.length];
        for (int i = 0; i < SLOTS.length; i++) {
            Tag slot = in.get(SLOTS[i]);
            stacks[i] = slot == null ? ItemStack.EMPTY
                : ItemStack.OPTIONAL_CODEC.parse(ops, slot).result().orElse(ItemStack.EMPTY);
        }
        return new PlayerMobAppearance(in.getInt("skinIndex"), in.getString("skinTextureUrl"),
            in.getBoolean("slim"), in.getString("name"),
            stacks[0], stacks[1], stacks[2], stacks[3], stacks[4], stacks[5]);
    }
}
