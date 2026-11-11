package games.brennan.dungeontrain.provenance;

import games.brennan.dungeontrain.compat.DisabledModContent;
import games.brennan.dungeontrain.editor.VaultRarity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Where a piece of gear came from, kept in the stack's own data so the client can show it under Shift
 * ({@code client.ProvenanceTooltip}): who found, made or bought it, the day, and the carriage it was
 * rolled in.
 *
 * <p>Two steps. Where an item comes into being — a carriage roll, a loot table, a mob's drops, a craft,
 * a trade — it is marked {@code fresh}, with its carriage index when it has one. The first real player
 * to hold it is then written in as its holder ({@link ProvenanceSweep}). An item that reaches a player
 * unmarked was made before the train paid attention (an old save, a creative grab): it gets an
 * {@link How#OLD} line instead, one of {@link #OLD_VARIANTS} picked at random.</p>
 *
 * <p>Only gear ({@link #eligible}) is marked: stamped data stops a stack merging with plain copies,
 * and gear doesn't stack.</p>
 */
public final class ItemProvenance {

    /** {@code CUSTOM_DATA} key holding the compound below. */
    public static final String TAG = "dungeontrain_provenance";

    static final String KEY_FRESH = "fresh";
    static final String KEY_PIDX = "pidx";
    static final String KEY_HOW = "how";
    static final String KEY_WHO = "who";
    static final String KEY_NAME = "name";
    static final String KEY_DAY = "day";
    static final String KEY_CARRIAGE = "carriage";
    static final String KEY_VARIANT = "variant";
    static final String KEY_AIN = "ain";

    /** How many {@link How#OLD} lines there are to pick from; one lang key each. */
    public static final int OLD_VARIANTS = 14;

    /** How the holder came by the item. */
    public enum How {
        FOUND, MADE, BOUGHT, OLD;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<How> of(String wire) {
            for (How h : values()) {
                if (h.wire().equals(wire)) return Optional.of(h);
            }
            return Optional.empty();
        }
    }

    /**
     * What the tooltip shows. {@code who} is empty for vault finds migrated from their old lore line,
     * which kept only a name.
     */
    public record View(How how, Optional<UUID> who, String name, long day, Optional<String> carriage,
                       int variant, boolean ainCredited) {}

    private ItemProvenance() {}

    /** Gear (armour, weapons, tools, shields, elytra) and the vault's rare finds. */
    public static boolean eligible(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return DisabledModContent.isGearClass(stack.getItem())
            || VaultRarity.isRare(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /**
     * Mark {@code stack} as newly made, rolled in carriage {@code pIdx} when given. Keeps an index
     * already there, and leaves an item with a holder alone.
     */
    public static void markFresh(ItemStack stack, OptionalInt pIdx) {
        if (!eligible(stack) || hasHolder(stack)) return;
        update(stack, tag -> {
            tag.putBoolean(KEY_FRESH, true);
            if (pIdx.isPresent() && !tag.contains(KEY_PIDX)) tag.putInt(KEY_PIDX, pIdx.getAsInt());
        });
    }

    /** Mark {@code stack} as new and record how it is coming to its holder: made or bought. */
    public static void markHow(ItemStack stack, How how) {
        if (!eligible(stack) || hasHolder(stack)) return;
        update(stack, tag -> {
            tag.putBoolean(KEY_FRESH, true);
            tag.putString(KEY_HOW, how.wire());
        });
    }

    /** Note that AdventureItemNames already names the maker in this item's description. */
    public static void markAinCredited(ItemStack stack) {
        if (!eligible(stack)) return;
        update(stack, tag -> tag.putBoolean(KEY_AIN, true));
    }

    /** True once a holder has been written in, by uuid or (for a migrated vault find) by name. */
    public static boolean hasHolder(ItemStack stack) {
        CompoundTag tag = read(stack);
        return tag != null && tag.contains(KEY_NAME);
    }

    /** The carriage index {@code stack} was rolled in, when it was rolled in a carriage. */
    public static OptionalInt pIdx(ItemStack stack) {
        CompoundTag tag = read(stack);
        return tag != null && tag.contains(KEY_PIDX) ? OptionalInt.of(tag.getInt(KEY_PIDX)) : OptionalInt.empty();
    }

    /**
     * Write in the holder: a fresh item is found (or made / bought, when marked so), an unmarked one
     * is {@link How#OLD} with {@code oldVariant}. Does nothing to an item that has a holder.
     *
     * @param carriageId the formatted id of the carriage the item was rolled in, when known
     * @return whether anything was written
     */
    public static boolean claim(ItemStack stack, UUID who, String name, long epochDay,
                                Optional<String> carriageId, int oldVariant) {
        if (!eligible(stack) || hasHolder(stack)) return false;
        update(stack, tag -> {
            boolean fresh = tag.getBoolean(KEY_FRESH);
            How how = fresh ? How.of(tag.getString(KEY_HOW)).orElse(How.FOUND) : How.OLD;
            tag.putString(KEY_HOW, how.wire());
            tag.putUUID(KEY_WHO, who);
            tag.putString(KEY_NAME, name);
            tag.putLong(KEY_DAY, epochDay);
            carriageId.ifPresent(id -> tag.putString(KEY_CARRIAGE, id));
            if (how == How.OLD) tag.putInt(KEY_VARIANT, Math.floorMod(oldVariant, OLD_VARIANTS));
            tag.remove(KEY_FRESH);
        });
        return true;
    }

    /**
     * Write in the holder of a vault find from before this data existed, from the name and date its
     * old "Pulled by" line carried.
     */
    public static void claimMigrated(ItemStack stack, String name, long epochDay) {
        if (hasHolder(stack)) return;
        update(stack, tag -> {
            tag.putString(KEY_HOW, How.FOUND.wire());
            tag.putString(KEY_NAME, name);
            tag.putLong(KEY_DAY, epochDay);
            tag.remove(KEY_FRESH);
        });
    }

    /** What to show for {@code stack}, once it has a holder. */
    public static Optional<View> view(ItemStack stack) {
        CompoundTag tag = read(stack);
        if (tag == null || !tag.contains(KEY_NAME)) return Optional.empty();
        Optional<How> how = How.of(tag.getString(KEY_HOW));
        if (how.isEmpty()) return Optional.empty();
        return Optional.of(new View(how.get(),
            tag.hasUUID(KEY_WHO) ? Optional.of(tag.getUUID(KEY_WHO)) : Optional.empty(),
            tag.getString(KEY_NAME),
            tag.getLong(KEY_DAY),
            tag.contains(KEY_CARRIAGE) ? Optional.of(tag.getString(KEY_CARRIAGE)) : Optional.empty(),
            tag.getInt(KEY_VARIANT),
            tag.getBoolean(KEY_AIN)));
    }

    /** The epoch day of a {@code yyyy-MM-dd} date, as the old vault line wrote it. */
    static OptionalLong epochDayOf(String isoDate) {
        try {
            return OptionalLong.of(java.time.LocalDate.parse(isoDate).toEpochDay());
        } catch (RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    private static CompoundTag read(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(TAG)) return null;
        return data.copyTag().getCompound(TAG);
    }

    private static void update(ItemStack stack, java.util.function.Consumer<CompoundTag> edit) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
            CompoundTag tag = root.getCompound(TAG).copy();
            edit.accept(tag);
            root.put(TAG, tag);
        });
    }
}
