package games.brennan.dungeontrain.difficulty;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemstats.api.StatsModifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Repairs items with impossible stats that were <b>baked into a saved template</b>.
 *
 * <h2>Why this exists</h2>
 * <p>A player looted a Train Dimension room chest holding a diamond chestplate with
 * <b>+4,433,488.29 armour</b>. Nothing rolled it at play time: the four items sat inside
 * {@code evilhouse.nbt} as literal chest contents. They got there in the editor —
 * {@code ContainerContentsLinkPropagator} rolled that cell's linked prefab with a
 * {@code System.nanoTime} salt in the difficulty frame (fixed separately), producing tier ≈44.3M
 * gear in the plot, and {@code /dt save} captured the plot with {@code fillFromWorld}, which bakes
 * block-entity NBT. The room was then committed and shipped.</p>
 *
 * <p>Fixing the propagator stops new bakes. It does nothing for templates already saved — and those
 * include rooms authored by players and rooms served from the relay, which cannot be reached by
 * editing data in this repo. So the repair has to happen on the way into the world.</p>
 *
 * <h2>Reroll, not clamp or strip</h2>
 * <p>The item is rolled again at the tier it should have had, so it comes out indistinguishable from
 * ordinary loot rather than as a capped freak or a stripped vanilla item.</p>
 *
 * <p><b>The clear on line one is load-bearing.</b> {@code StatsModifier.applyStats} reads the stack's
 * existing {@link DataComponents#ATTRIBUTE_MODIFIERS} as the base it scales from, and only falls back
 * to {@code Item.getDefaultAttributeModifiers()} when that component is empty. Rerolling without
 * clearing first would scale <i>from four million</i> and produce another impossible number.</p>
 *
 * <h2>A function of where, never of when</h2>
 * <p>The tier is {@link DifficultyProgression#levelAtWorldX} — position-derived, the same frame
 * {@code GateContext} and {@code TunnelPlacer} gate on — and the RNG is seeded from the world seed and
 * the cell's position. A portal room is re-stamped every time the tiling window slides over it, so a
 * repair keyed on anything else would visibly reroll the chest under a player standing in it. This is
 * the same rule the room's own loot pass follows.</p>
 */
public final class BakedItemStats {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Largest attribute-modifier amount a legitimately-rolled item can carry.
     *
     * <p>The per-tier bonus is {@code 0.15 * tier} at its steepest ({@code ItemStatLevelScaling}'s axe
     * rate), and {@code TemplateGate.MAX_LEVEL} is 1000, so the ceiling the game can actually reach is
     * ~150 plus a small base. This leaves roughly six times that as headroom while sitting four orders
     * of magnitude below the corruption it is here to catch.</p>
     */
    public static final double MAX_SANE_MODIFIER = 1000.0;

    /** Item-stack NBT keys — {@code id} identifies a stack, the rest hold the stats. */
    private static final String KEY_ID = "id";
    private static final String KEY_COMPONENTS = "components";
    private static final String KEY_ATTRIBUTE_MODIFIERS = "minecraft:attribute_modifiers";
    private static final String KEY_MODIFIERS = "modifiers";
    private static final String KEY_AMOUNT = "amount";

    private BakedItemStats() {}

    /** Whether {@code amount} is past anything the game could have produced. Pure. */
    public static boolean isAbsurd(double amount) {
        return !Double.isFinite(amount) || Math.abs(amount) > MAX_SANE_MODIFIER;
    }

    /**
     * Whether {@code itemTag} is a stack carrying at least one impossible modifier.
     *
     * <p>Reads the tag rather than deserialising: this runs for every block entity stamped into the
     * world, and all but a vanishing fraction are clean. Pure, so it is unit-testable without a
     * NeoForge bootstrap.</p>
     */
    public static boolean isCorrupt(CompoundTag itemTag) {
        if (itemTag == null || !itemTag.contains(KEY_ID, Tag.TAG_STRING)) return false;
        CompoundTag components = itemTag.getCompound(KEY_COMPONENTS);
        if (components.isEmpty()) return false;
        CompoundTag attributes = components.getCompound(KEY_ATTRIBUTE_MODIFIERS);
        if (attributes.isEmpty()) return false;
        ListTag modifiers = attributes.getList(KEY_MODIFIERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < modifiers.size(); i++) {
            if (isAbsurd(modifiers.getCompound(i).getDouble(KEY_AMOUNT))) return true;
        }
        return false;
    }

    /**
     * Whether any stack anywhere inside {@code beNbt} is corrupt.
     *
     * <p>Walks the whole tag rather than naming {@code Items} / {@code item} / {@code ArmorItems} in
     * turn, so a container, a decorated pot and a baked armour stand are all covered by one rule and a
     * container shape nobody thought of does not slip through.</p>
     */
    public static boolean containsCorrupt(CompoundTag beNbt) {
        if (beNbt == null) return false;
        if (isCorrupt(beNbt)) return true;
        for (String key : beNbt.getAllKeys()) {
            Tag child = beNbt.get(key);
            if (child instanceof CompoundTag compound) {
                if (containsCorrupt(compound)) return true;
            } else if (child instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof CompoundTag element && containsCorrupt(element)) return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code beNbt} with every impossible stack inside it rolled again at this position's tier.
     *
     * <p>Returns the input <b>unchanged</b> when nothing is wrong, which is the overwhelmingly common
     * case and the one that has to stay cheap — the scan above is the only cost paid per stamped block
     * entity. Never mutates the input: a template's tag is shared across every copy stamped from it,
     * so writing through it would corrupt the tiling's other tiles.</p>
     */
    public static CompoundTag repair(ServerLevel level, BlockPos pos, CompoundTag beNbt) {
        if (level == null || beNbt == null || !containsCorrupt(beNbt)) return beNbt;

        int carriageLength = Math.max(1, DungeonTrainWorldData.get(level).dims().length());
        int tier = DifficultyProgression.levelAtWorldX(pos.getX(), carriageLength);
        CompoundTag repaired = repairIn(beNbt, level.registryAccess(), level.getSeed(), pos, tier);
        LOGGER.warn("[DungeonTrain] Repaired baked item stats at {} — a saved template held gear with"
                + " impossible values (over {}). Rerolled at the tier for this position ({})."
                + " The template itself still holds the bad values; this is the read-side repair.",
            pos, MAX_SANE_MODIFIER, tier);
        return repaired;
    }

    /** Recursive half of {@link #repair}, on a tag already known to need work. */
    private static CompoundTag repairIn(CompoundTag tag, HolderLookup.Provider registries,
                                        long worldSeed, BlockPos pos, int tier) {
        CompoundTag out = tag.copy();
        if (isCorrupt(out)) return reroll(out, registries, worldSeed, pos, tier);
        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                if (containsCorrupt(compound)) {
                    out.put(key, repairIn(compound, registries, worldSeed, pos, tier));
                }
            } else if (child instanceof ListTag list) {
                ListTag replacement = null;
                for (int i = 0; i < list.size(); i++) {
                    if (!(list.get(i) instanceof CompoundTag element) || !containsCorrupt(element)) continue;
                    if (replacement == null) replacement = list.copy();
                    replacement.set(i, repairIn(element, registries, worldSeed, pos, tier));
                }
                if (replacement != null) out.put(key, replacement);
            }
        }
        return out;
    }

    /**
     * One stack, rolled again at {@code tier}.
     *
     * <p>The clear is what makes this a reroll rather than a re-inflation — see the class note. If the
     * stack will not deserialise (an item from a mod that is no longer present, say), the corrupt
     * modifiers are dropped rather than trusted: an item nobody can identify is not one to hand a
     * player four million armour on.</p>
     */
    private static CompoundTag reroll(CompoundTag itemTag, HolderLookup.Provider registries,
                                      long worldSeed, BlockPos pos, int tier) {
        Optional<ItemStack> parsed = ItemStack.parse(registries, itemTag);
        if (parsed.isEmpty()) {
            CompoundTag stripped = itemTag.copy();
            stripped.getCompound(KEY_COMPONENTS).remove(KEY_ATTRIBUTE_MODIFIERS);
            return stripped;
        }
        ItemStack stack = parsed.get();
        // Back to the item's own defaults, so applyStats scales from a sane base.
        stack.remove(DataComponents.ATTRIBUTE_MODIFIERS);
        RandomSource rng = RandomSource.create(seedFor(worldSeed, pos, itemTag));
        StatsModifier.applyStats(stack, rng, ItemStatLevelScaling.primaryStatBonus(stack, tier));
        Tag saved = stack.save(registries);
        return saved instanceof CompoundTag compound ? compound : itemTag;
    }

    /**
     * A seed that is a pure function of the cell and the slot, so a room re-stamped as the tiling
     * window slides repairs to the same item rather than rerolling under a player looking at it.
     */
    private static long seedFor(long worldSeed, BlockPos pos, CompoundTag itemTag) {
        long slot = itemTag.contains("Slot") ? itemTag.getByte("Slot") : 0L;
        return worldSeed
            ^ ((long) pos.getX() * 0x9E3779B97F4A7C15L)
            ^ ((long) pos.getY() * 0xBF58476D1CE4E5B9L)
            ^ ((long) pos.getZ() * 0x94D049BB133111EBL)
            ^ (slot * 0xC2B2AE3D27D4EB4FL)
            ^ itemTag.getString(KEY_ID).hashCode();
    }
}
