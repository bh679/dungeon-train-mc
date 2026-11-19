package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Predicate;

/**
 * What a note's author was carrying at the moment they died, so the echo their note sends arrives
 * wearing it — the gear of <em>that</em> life, not a fresh difficulty roll and not some older life's.
 *
 * <p>Captured at the author's death ({@code DeathNoteEvents}), shipped to the relay with the note as
 * the opaque {@code authorGear} string, and put on the echo by {@code DeathNoteEchoSpawner} on the
 * target's game. An exact copy: an author who died empty-handed sends an empty-handed echo.</p>
 *
 * <p>Follows PlayerMob's own reincarnation rules ({@code PlayerReincarnation.applyGear}) so a note echo
 * and an ordinary echo of the same death carry the same things: worn armour and both hands into the
 * matching slots, the rest of the inventory into the mob's backpack — food first, then inventory
 * order (hotbar first) — until it is full. Curse of Vanishing items stay behind (vanilla destroys
 * them on death), and so do signed Death / Love Notes: the echo already drops its own trophy book.</p>
 *
 * <p>Wire format: {@code {Equipment:{<slot>:item,…}, Pack:[item,…]}} as gzip NBT, base64-wrapped —
 * the same shape Discord Presence uses for reincarnation snapshots. Untrusted on the way back in:
 * the decode is size-bounded and every item is parsed leniently, so a corrupt or forged string costs
 * the echo its gear, never the spawn.</p>
 */
public final class NoteAuthorGear {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Longest encoded gear we send or accept. Matches the relay's {@code MAX_GEAR_CHARS}. */
    public static final int MAX_ENCODED_CHARS = 65536;
    /** Decoded NBT budget — a little over what {@link #MAX_ENCODED_CHARS} of gzip could honestly hold. */
    private static final long MAX_DECODED_BYTES = 2L * 1024 * 1024;
    /** PlayerMob's backpack size ({@code PlayerMobEntity.INVENTORY_SIZE}). */
    static final int PACK_SLOTS = 8;

    static final String KEY_EQUIPMENT = "Equipment";
    static final String KEY_PACK = "Pack";

    private static final EquipmentSlot[] WORN_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
    };

    private NoteAuthorGear() {}

    /** Encoded gear for {@code player} as they die, or {@code ""} when it can't be captured or is too big. */
    public static String captureEncoded(ServerPlayer player) {
        try {
            return encode(capture(player, player.registryAccess()));
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Note author gear: capture failed for {}: {}",
                    player.getGameProfile().getName(), e.toString());
            return "";
        }
    }

    /** The gear tag for {@code player}'s current loadout. */
    static CompoundTag capture(ServerPlayer player, HolderLookup.Provider registries) {
        CompoundTag equipment = new CompoundTag();
        for (EquipmentSlot slot : WORN_SLOTS) {
            ItemStack worn = player.getItemBySlot(slot);
            if (keeps(worn)) equipment.put(slot.getName(), worn.save(registries));
        }

        NonNullList<ItemStack> items = player.getInventory().items;
        int selected = player.getInventory().selected;
        List<ItemStack> rest = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (i == selected) continue; // the held item already went to MAINHAND
            if (keeps(items.get(i))) rest.add(items.get(i));
        }
        ListTag pack = new ListTag();
        for (ItemStack stack : packOrder(rest, s -> s.has(DataComponents.FOOD), PACK_SLOTS)) {
            pack.add(stack.save(registries));
        }

        CompoundTag gear = new CompoundTag();
        gear.put(KEY_EQUIPMENT, equipment);
        gear.put(KEY_PACK, pack);
        return gear;
    }

    /** True when {@code stack} rides with the echo — see the class javadoc for what stays behind. */
    private static boolean keeps(ItemStack stack) {
        return !stack.isEmpty()
                && !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)
                && !DeathNoteBookTag.isDeathNote(stack)
                && !LoveNoteBookTag.isLoveNote(stack);
    }

    /** {@code stacks} in backpack order — those matching {@code food} first, each group in order — capped. */
    static <T> List<T> packOrder(List<T> stacks, Predicate<T> food, int cap) {
        List<T> out = new ArrayList<>(Math.min(stacks.size(), cap));
        for (T s : stacks) if (out.size() < cap && food.test(s)) out.add(s);
        for (T s : stacks) if (out.size() < cap && !food.test(s)) out.add(s);
        return out;
    }

    /**
     * Dress {@code mob} in {@code gear}: matching equipment slots, then the backpack in order. Items
     * that fail to parse (unknown to this game, or forged) are skipped. Returns how many stacks landed.
     */
    public static int applyTo(PlayerMobEntity mob, CompoundTag gear, HolderLookup.Provider registries) {
        int placed = 0;
        CompoundTag equipment = gear.getCompound(KEY_EQUIPMENT);
        for (EquipmentSlot slot : WORN_SLOTS) {
            if (!equipment.contains(slot.getName(), Tag.TAG_COMPOUND)) continue;
            ItemStack stack = ItemStack.parseOptional(registries, equipment.getCompound(slot.getName()));
            if (stack.isEmpty()) continue;
            mob.setItemSlot(slot, stack); // PlayerMob drops all equipped gear + backpack on death
            placed++;
        }
        SimpleContainer backpack = mob.getInventory();
        ListTag pack = gear.getList(KEY_PACK, Tag.TAG_COMPOUND);
        int slot = 0;
        for (int i = 0; i < pack.size() && slot < backpack.getContainerSize(); i++) {
            ItemStack stack = ItemStack.parseOptional(registries, pack.getCompound(i));
            if (stack.isEmpty()) continue;
            backpack.setItem(slot++, stack);
            placed++;
        }
        return placed;
    }

    /** gzip NBT, base64; {@code ""} for null, or when the result would exceed {@link #MAX_ENCODED_CHARS}. */
    static String encode(CompoundTag gear) {
        if (gear == null) return "";
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(gear, bytes);
            String out = Base64.getEncoder().encodeToString(bytes.toByteArray());
            if (out.length() > MAX_ENCODED_CHARS) {
                LOGGER.info("[DungeonTrain] Note author gear: {} chars is over the {} cap — echo goes without.",
                        out.length(), MAX_ENCODED_CHARS);
                return "";
            }
            return out;
        } catch (IOException e) {
            return "";
        }
    }

    /** The gear in {@code encoded}, or {@code null} when it is blank, oversize or corrupt. */
    public static CompoundTag decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_CHARS) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(encoded.trim());
            return NbtIo.readCompressed(new ByteArrayInputStream(raw),
                    NbtAccounter.create(MAX_DECODED_BYTES));
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[DungeonTrain] Note author gear: undecodable ({}), echo goes without.", e.toString());
            return null;
        }
    }
}
