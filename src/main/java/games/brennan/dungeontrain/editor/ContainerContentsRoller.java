package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.appearance.ArmorAppearanceRoller;
import games.brennan.dungeontrain.debug.DebugFlags;
import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.adventureitemstats.api.StatsModifier;
import games.brennan.dungeontrain.narrative.RandomBookFactory;
import games.brennan.dungeontrain.registry.ModItems;
import org.slf4j.Logger;

/**
 * Pure utility — turn a {@link ContainerContentsPool} into the {@code Items}
 * NBT list that {@link net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity}
 * and {@link net.minecraft.world.level.block.entity.DispenserBlockEntity}
 * read on load.
 *
 * <p>Determinism: the roll is keyed on
 * {@code (worldSeed, carriageIndex, localPos, slot)} so the same chest in
 * the same carriage at the same world seed always rolls the same contents.
 * Mixer mirrors the variant-resolve mixer in
 * {@link CarriageVariantBlocks#pickIndexWeighted}.</p>
 */
public final class ContainerContentsRoller {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long MIX_X = 0x9E3779B97F4A7C15L;
    private static final long MIX_Y = 0xBF58476D1CE4E5B9L;
    private static final long MIX_Z = 0x94D049BB133111EBL;
    private static final long MIX_S = 0xC6BC279692B5C323L;
    private static final long MIX_C = 0xCAFEBABE12345678L;

    /** Salt for the durability-chance roll (does it apply on this spawn?). */
    private static final long SALT_DUR_CHANCE  = 0xD1E5D1E5DEADCAFEL;
    /** Salt for the actual durability value when the chance roll succeeds. */
    private static final long SALT_DUR_VALUE   = 0x12345678ABCDEF01L;
    /** Salt for the enchantment-chance roll. */
    private static final long SALT_ENCH_CHANCE = 0xFEEDFACECAFEBABEL;
    /** Seed source for the vanilla EnchantmentHelper RandomSource. */
    private static final long SALT_ENCH_VALUE  = 0xBADDCAFE0FF1CE00L;
    /** Salt for the random-book placeholder substitution. */
    private static final long SALT_RANDOM_BOOK = 0xB0011AB1ECAFEBE0L;
    /** Salt for the procedural name composer (naturally-spawned items). */
    private static final long SALT_NAME        = 0x4E414D4544585742L;
    /** Salt for the AIS Gaussian stat roller (naturally-spawned items). */
    private static final long SALT_STATS       = 0x5354415453534447L;
    /** Salt for the leather-dye chance roll (leather armor only). */
    private static final long SALT_DYE_CHANCE    = 0xDEADD13DC0DEC0DEL;
    /** Salt for picking which vanilla DyeColor to apply when the dye chance hits. */
    private static final long SALT_DYE_VALUE     = 0xC010D1E1C010D1E2L;
    /** Salt for the armor-trim chance roll (any ArmorItem). */
    private static final long SALT_TRIM_CHANCE   = 0x7411C0DE5A1750CCL;
    /** Salt for picking the trim pattern from the registry. */
    private static final long SALT_TRIM_PATTERN  = 0x7411B0BB1EA110CEL;
    /** Salt for picking the trim material via weighted selection. */
    private static final long SALT_TRIM_MATERIAL = 0x7411DECAFC0FFEE5L;
    /** Salt for the per-50-carriage offensive arrow potion roll. */
    private static final long SALT_ARROW_EPOCH_POTION = 0xA770E1EC7C0FFEE5L;
    /** Salt for the per-50-carriage drinkable-potion effect roll (decorrelated from arrows). */
    private static final long SALT_POTION_EPOCH_EFFECT = 0xB0D1ED5EEDC0FFEEL;
    /** Salt for the per-50-carriage potion-form roll (drinkable / splash / lingering). */
    private static final long SALT_POTION_EPOCH_FORM = 0xF0E1F0E1CAFED00DL;

    /**
     * BE NBT key on {@code decorated_pot} that holds the single contained
     * {@link ItemStack}. Matches the vanilla 1.21.1
     * {@code DecoratedPotBlockEntity.TAG_ITEM} schema so the pot's native
     * {@code loadAdditional} populates its {@code item} field and the standard
     * {@code DecoratedPotBlock.onRemove} → {@code Containers.dropContentsOnDestroy}
     * spills it on break alongside the sherd shards.
     */
    private static final String NBT_POT_ITEM = "item";

    // ------------------------------------------------------------------
    // Epoch effects: an otherwise-effectless item found in loot gains a
    // random vanilla potion, rolled once per 50-carriage block (sticky
    // within the block) and escalating in power as the run progresses.
    // Tipped arrows and drinkable/splash/lingering potions share the
    // level/tier/index math below but each carries its own tier table and
    // a decorrelating salt:
    //   • tipped arrows                       → ARROW_EFFECT_TIERS  (offensive)
    //   • drinkable / splash / lingering pots → POTION_EFFECT_TIERS (broad)
    // ------------------------------------------------------------------

    /**
     * Carriages per effect re-roll, shared by every epoch effect. The rolled
     * potion stays constant for this many carriages, so every effectless item
     * found inside one block shares the same effect; it changes at the next
     * block boundary. Doubles as the progression "level" that drives the tier
     * tables.
     */
    private static final int EFFECT_EPOCH_CARRIAGES = 50;

    /** Levels that share one power tier — the pacing dial for the ramp. */
    private static final int EFFECT_LEVELS_PER_TIER = 1;

    /** Potion bottle forms rolled per band: drinkable / splash / lingering. */
    private static final int POTION_FORM_COUNT = 3;

    /**
     * Ordered, escalating power tiers of offensive vanilla potions applied to
     * "Uncraftable" tipped arrows. The level (= carriages travelled /
     * {@link #EFFECT_EPOCH_CARRIAGES}) selects a tier via
     * {@link #arrowEffectTierIndex(int)}; deeper tiers are nastier. Using
     * registered potions keeps vanilla names/tooltips for free ("Arrow of
     * Poison", "Arrow of Harming II", …). This table is the single place to
     * retune which effects appear at which level and how power ramps.
     *
     * <p>Each tier is a <b>single, distinctly-named</b> effect so crossing a
     * 50-carriage boundary always <i>visibly</i> changes the arrow. Two rules
     * keep that promise: (1) no potion repeats across tiers, and (2) no
     * {@code long_*} duration variants — those render with the SAME display
     * name as their base potion ({@code long_slowness} → "Arrow of Slowness"),
     * which would read as "no change" to the player. A tier may still list
     * multiple ids; if so they must all be distinct from every adjacent tier.</p>
     */
    private static final List<List<ResourceLocation>> ARROW_EFFECT_TIERS = List.of(
        tierIds("weakness"),        // T0 — carts 0–49    → Weakness
        tierIds("slowness"),        // T1 — carts 50–99   → Slowness
        tierIds("poison"),          // T2 — carts 100–149 → Poison
        tierIds("harming"),         // T3 — carts 150–199 → Harming
        tierIds("strong_poison"),   // T4 — carts 200–249 → Poison II
        tierIds("strong_harming")   // T5 — carts 250+    → Harming II
    );

    /** Test seam: read-only view of the configured arrow-effect tiers. */
    static List<List<ResourceLocation>> arrowEffectTiersView() {
        return ARROW_EFFECT_TIERS;
    }

    /**
     * Ordered, escalating tiers of vanilla potions applied to otherwise-
     * effectless drinkable / splash / lingering potions found in loot. Unlike
     * the offensive arrow table these span ALL effect kinds (beneficial +
     * offensive + utility), arranged by rough potency: a 50-carriage block
     * sticky-picks one id from its tier, and deeper blocks unlock stronger /
     * level-II effects.
     *
     * <p>Same two invariants as {@link #ARROW_EFFECT_TIERS} (enforced by
     * {@code PotionEpochEffectTest}): no {@code long_*} duration variants (they
     * render with the base potion's display name) and no potion shared across
     * adjacent tiers, so crossing a block boundary always visibly changes the
     * potion.</p>
     */
    private static final List<List<ResourceLocation>> POTION_EFFECT_TIERS = List.of(
        tierIds("weakness", "healing", "night_vision"),                      // T0 — carts 0–49
        tierIds("slowness", "swiftness", "water_breathing"),                 // T1 — carts 50–99
        tierIds("poison", "fire_resistance", "leaping"),                     // T2 — carts 100–149
        tierIds("harming", "strength", "invisibility"),                      // T3 — carts 150–199
        tierIds("strong_poison", "strong_healing", "slow_falling"),          // T4 — carts 200–249
        tierIds("strong_harming", "strong_regeneration", "strong_strength")  // T5 — carts 250+
    );

    /** Test seam: read-only view of the configured potion-effect tiers. */
    static List<List<ResourceLocation>> potionEffectTiersView() {
        return POTION_EFFECT_TIERS;
    }

    private static List<ResourceLocation> tierIds(String... potionPaths) {
        List<ResourceLocation> ids = new ArrayList<>(potionPaths.length);
        for (String path : potionPaths) {
            ids.add(ResourceLocation.withDefaultNamespace(path));
        }
        return List.copyOf(ids);
    }

    private ContainerContentsRoller() {}

    /**
     * Build the BE NBT for a container at {@code localPos} given a pool. If
     * the pool is empty or the block has no Container BE, returns
     * {@code baseNbt} unchanged.
     *
     * <p>Container size is read from the freshly-instantiated BlockEntity
     * (via {@code beType.create}) so dispensers get 9 slots, hoppers 5,
     * chests 27, etc. — without us hardcoding a table.</p>
     */
    @Nullable
    public static CompoundTag roll(ContainerContentsPool pool, BlockState state,
                                   BlockPos localPos, long worldSeed, int carriageIndex,
                                   @Nullable CompoundTag baseNbt,
                                   HolderLookup.Provider registries,
                                   @Nullable Level level) {
        if (pool == null || pool.isEmpty()) return baseNbt;
        if (!state.hasBlockEntity()) return baseNbt;
        int totalWeight = pool.totalWeight();
        if (totalWeight <= 0) return baseNbt;

        // Decorated pots take a dedicated single-item path. The BE is a vanilla
        // ContainerSingleItem in 1.21.1, but it stores its slot under the
        // "item" key (singular), not the generic "Items" ListTag — so the
        // chest-style branch below would silently drop the rolled stack on
        // load. Route it to rollDecoratedPot before the generic path runs.
        if (isDecoratedPot(state)) {
            return rollDecoratedPot(pool, totalWeight, localPos, worldSeed, carriageIndex, baseNbt, registries);
        }
        int slots = nativeContainerSlots(state);
        if (slots <= 0) return baseNbt;

        // Furnace family has semantic slots (input / fuel / output) — route to
        // a slot-aware path so the input slot gets a cookable item, the fuel
        // slot gets a burnable item, and the output slot gets anything.
        if (isFurnaceLike(state)) {
            return rollFurnace(pool, state, localPos, worldSeed, carriageIndex, baseNbt, registries, level);
        }

        // Roll K = uniform random in [fillMin, effectiveMax] (inclusive).
        int effectiveMax = pool.fillMax() == ContainerContentsPool.FILL_ALL
            ? slots : Math.min(pool.fillMax(), slots);
        int effectiveMin = Math.max(0, Math.min(pool.fillMin(), effectiveMax));
        int k = rollKCount(effectiveMin, effectiveMax, localPos, worldSeed, carriageIndex);
        int[] slotsToFill = resolveSlotSubset(slots, k, localPos, worldSeed, carriageIndex);

        ListTag items = new ListTag();
        for (int slot : slotsToFill) {
            ContainerContentsEntry picked = pickEntry(pool, totalWeight, localPos, worldSeed, carriageIndex, slot);
            if (picked == null || picked.isAir()) continue;
            // Per-entry count is treated as a max — roll the actual stack
            // count uniformly in [1, picked.count()] for variety per slot.
            int rolledCount = rollItemCount(picked.count(), localPos, worldSeed, carriageIndex, slot);
            ItemStack stack = rollItemStack(picked, rolledCount, localPos, worldSeed, carriageIndex, slot, registries);
            if (stack.isEmpty()) continue;
            CompoundTag stackTag = (CompoundTag) stack.save(registries, new CompoundTag());
            stackTag.putByte("Slot", (byte) slot);
            items.add(stackTag);
        }

        CompoundTag out = baseNbt == null ? new CompoundTag() : baseNbt.copy();
        // Drop any pre-existing Items list — the rolled pool is authoritative.
        out.put("Items", items);
        return out;
    }

    /**
     * Variant of {@link #roll} that returns a flat list of rolled
     * {@link ItemStack}s instead of an {@code Items} NBT ListTag. Used by the
     * entity-side variant path ({@code EntityVariantApplicator}) where the
     * consumer needs to bucket stacks into entity-specific slot layouts
     * (armor stand {@code ArmorItems}/{@code HandItems}, item frame
     * {@code Item}) rather than container slot indices.
     *
     * <p>Reuses the same deterministic mixers as the chest path —
     * {@code (localPos, worldSeed, carriageIndex)} drive the K-count + slot-
     * subset, and a per-slot salt drives entry + count + stack rolls. The
     * {@code slotCount} parameter sets the synthetic upper bound (e.g. 6 for
     * armor stands = 4 armor + 2 hands, 1 for item frames). The returned list
     * is unordered with respect to entity slots; callers are expected to map
     * each stack to its semantic slot (e.g. via
     * {@code Mob.getEquipmentSlotForItem}).</p>
     */
    public static List<ItemStack> rollStacks(ContainerContentsPool pool, int slotCount,
                                              BlockPos localPos, long worldSeed, int carriageIndex,
                                              HolderLookup.Provider registries) {
        if (pool == null || pool.isEmpty()) return List.of();
        if (slotCount <= 0) return List.of();
        int totalWeight = pool.totalWeight();
        if (totalWeight <= 0) return List.of();

        int effectiveMax = pool.fillMax() == ContainerContentsPool.FILL_ALL
            ? slotCount : Math.min(pool.fillMax(), slotCount);
        int effectiveMin = Math.max(0, Math.min(pool.fillMin(), effectiveMax));
        int k = rollKCount(effectiveMin, effectiveMax, localPos, worldSeed, carriageIndex);
        if (k <= 0) return List.of();
        int[] slotsToFill = resolveSlotSubset(slotCount, k, localPos, worldSeed, carriageIndex);

        List<ItemStack> out = new ArrayList<>();
        for (int slot : slotsToFill) {
            ContainerContentsEntry picked = pickEntry(pool, totalWeight, localPos, worldSeed, carriageIndex, slot);
            if (picked == null || picked.isAir()) continue;
            int rolledCount = rollItemCount(picked.count(), localPos, worldSeed, carriageIndex, slot);
            ItemStack stack = rollItemStack(picked, rolledCount, localPos, worldSeed, carriageIndex, slot, registries);
            if (stack.isEmpty()) continue;
            out.add(stack);
        }
        return out;
    }

    /**
     * Deterministic K = uniform integer in {@code [min, max]}. Same mixer
     * basis as the slot-pick / count rolls but with a distinct salt so all
     * three rolls are independent.
     *
     * <p>Package-private so {@link EntityVariantApplicator} can drive its
     * slot-aware armor stand fill with the same seed math.</p>
     */
    static int rollKCount(int min, int max, BlockPos localPos, long worldSeed, int carriageIndex) {
        if (max <= min) return min;
        long state = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C)
            ^ 0x5BD1E995L; // K-roll salt
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        int range = max - min + 1;
        return min + (int) ((state & 0x7FFFFFFFFFFFFFFFL) % range);
    }

    /**
     * Deterministic stack count for a slot — uniform in {@code [1, max]}.
     * Independent salt so it doesn't correlate with the entry pick.
     *
     * <p>Package-private so {@link EntityVariantApplicator} can roll counts
     * the same way per equipment slot.</p>
     */
    static int rollItemCount(int max, BlockPos localPos, long worldSeed, int carriageIndex, int slot) {
        if (max <= 1) return 1;
        long state = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C)
            ^ ((long) slot * MIX_S)
            ^ 0xA5A5A5A5DEADBEEFL; // count-roll salt
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return 1 + (int) ((state & 0x7FFFFFFFFFFFFFFFL) % max);
    }

    /**
     * Pick {@code count} slot indices from {@code [0..slots)} via seeded
     * Fisher–Yates. Returns the prefix of length {@code count}.
     */
    private static int[] resolveSlotSubset(int slots, int count, BlockPos localPos,
                                           long worldSeed, int carriageIndex) {
        if (count >= slots) {
            int[] all = new int[slots];
            for (int i = 0; i < slots; i++) all[i] = i;
            return all;
        }
        if (count <= 0) return new int[0];
        long state = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C);
        int[] order = new int[slots];
        for (int i = 0; i < slots; i++) order[i] = i;
        for (int i = slots - 1; i > 0; i--) {
            state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
            state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
            state = state ^ (state >>> 31);
            int j = (int) ((state & 0x7FFFFFFFFFFFFFFFL) % (i + 1));
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        int[] picked = new int[count];
        System.arraycopy(order, 0, picked, 0, count);
        return picked;
    }

    /**
     * Slot count for a container BE at {@code state}. Public so the menu
     * controller can show a hint of "max" when the player nudges fill count
     * past the container's capacity.
     */
    public static int slotsForContainer(BlockState state) {
        return containerSlotsFor(state);
    }

    /**
     * Slot count for a container BE. Looks up the BE type's default instance,
     * casts to {@link Container}. Returns 0 for non-container BEs.
     */
    private static int containerSlotsFor(BlockState state) {
        return nativeContainerSlots(state);
    }

    /** Native vanilla {@link Container} slot count, or 0 if the BE doesn't implement it. */
    private static int nativeContainerSlots(BlockState state) {
        BlockEntityType<?> beType = beTypeFor(state);
        if (beType == null) return 0;
        try {
            BlockEntity probe = beType.create(BlockPos.ZERO, state);
            if (probe instanceof Container c) return c.getContainerSize();
        } catch (Throwable ignored) {
            // Some BEs throw on null Level access during construction — bail.
        }
        return 0;
    }

    public static boolean isDecoratedPot(BlockState state) {
        return state.is(Blocks.DECORATED_POT);
    }

    /**
     * Decorated-pot roll path — picks a single weighted entry and writes it as
     * an {@link ItemStack} CompoundTag under vanilla's {@link #NBT_POT_ITEM}
     * key. The {@code fillMin}/{@code fillMax} pool semantics are preserved
     * for vases: with {@code effectiveMax = min(fillMax, 1)} the K-roll either
     * picks "1" (an item drops) or "0" (no item this carriage), giving the
     * same rarity dial the chest path uses. Vanilla's
     * {@code DecoratedPotBlock.onRemove} then handles the world drop on break.
     */
    private static CompoundTag rollDecoratedPot(ContainerContentsPool pool, int totalWeight,
                                                BlockPos localPos, long worldSeed, int carriageIndex,
                                                @Nullable CompoundTag baseNbt,
                                                HolderLookup.Provider registries) {
        int effectiveMax = pool.fillMax() == ContainerContentsPool.FILL_ALL
            ? 1 : Math.min(pool.fillMax(), 1);
        int effectiveMin = Math.max(0, Math.min(pool.fillMin(), effectiveMax));
        int k = rollKCount(effectiveMin, effectiveMax, localPos, worldSeed, carriageIndex);

        CompoundTag out = baseNbt == null ? new CompoundTag() : baseNbt.copy();
        out.remove(NBT_POT_ITEM);
        if (k <= 0) return out;

        ContainerContentsEntry picked = pickEntry(pool, totalWeight, localPos, worldSeed, carriageIndex, /*slot*/ 0);
        if (picked == null || picked.isAir()) return out;

        int rolledCount = rollItemCount(picked.count(), localPos, worldSeed, carriageIndex, /*slot*/ 0);
        ItemStack stack = rollItemStack(picked, rolledCount, localPos, worldSeed, carriageIndex, /*slot*/ 0, registries);
        if (stack.isEmpty()) return out;
        CompoundTag stackTag = (CompoundTag) stack.save(registries, new CompoundTag());
        out.put(NBT_POT_ITEM, stackTag);
        return out;
    }

    /** Furnace family — 3-slot cookers with semantic slots (input / fuel / output). */
    public static boolean isFurnaceLike(BlockState state) {
        return state.is(Blocks.FURNACE)
            || state.is(Blocks.BLAST_FURNACE)
            || state.is(Blocks.SMOKER);
    }

    private static RecipeType<? extends AbstractCookingRecipe> furnaceRecipeType(BlockState state) {
        if (state.is(Blocks.BLAST_FURNACE)) return RecipeType.BLASTING;
        if (state.is(Blocks.SMOKER))         return RecipeType.SMOKING;
        return RecipeType.SMELTING;
    }

    /**
     * Furnace-aware roll. Each pool entry classifies by its function in a
     * priority cascade:
     * <ol>
     *   <li>cookable for this furnace's recipe type → input slot (0)</li>
     *   <li>else fuel (positive burn time) → fuel slot (1)</li>
     *   <li>else (smelt-result or none-of-the-above) → output slot (2)</li>
     * </ol>
     *
     * <p>An item that is both cookable AND fuel (e.g. some wood logs) lands in
     * the input slot — cookable wins by priority. Charcoal (fuel AND a
     * smelt-result) lands in the fuel slot — fuel wins over smelt-result.</p>
     *
     * <p>The pool's fillMin/fillMax still controls how many slots get filled —
     * K = uniform(fillMin, min(fillMax, 3)). Slots are attempted in priority
     * order; a slot whose bucket is empty is skipped without consuming K, so
     * a K=1 pool with only fuel will still put the fuel in slot 1 rather than
     * leaving the furnace empty.</p>
     */
    private static CompoundTag rollFurnace(ContainerContentsPool pool, BlockState state,
                                           BlockPos localPos, long worldSeed, int carriageIndex,
                                           @Nullable CompoundTag baseNbt,
                                           HolderLookup.Provider registries,
                                           @Nullable Level level) {
        RecipeType<? extends AbstractCookingRecipe> rt = furnaceRecipeType(state);
        final int FURNACE_SLOTS = 3;
        int effectiveMax = pool.fillMax() == ContainerContentsPool.FILL_ALL
            ? FURNACE_SLOTS : Math.min(pool.fillMax(), FURNACE_SLOTS);
        int effectiveMin = Math.max(0, Math.min(pool.fillMin(), effectiveMax));
        int k = rollKCount(effectiveMin, effectiveMax, localPos, worldSeed, carriageIndex);

        if (DebugFlags.logLootRolls()) {
            int pinned0 = 0, pinned1 = 0, pinned2 = 0, auto = 0;
            for (ContainerContentsEntry e : pool.entries()) {
                int so = e.slotOverride();
                if (so == 0) pinned0++;
                else if (so == 1) pinned1++;
                else if (so == 2) pinned2++;
                else auto++;
            }
            LOGGER.info("[DT-furnace] rollFurnace block={} K={} fillMin={} fillMax={} entries={} pinned(in/fuel/out/auto)={}/{}/{}/{} carriageIdx={} localPos={}",
                state.getBlock(), k, pool.fillMin(), pool.fillMax(),
                pool.entries().size(), pinned0, pinned1, pinned2, auto,
                carriageIndex, localPos);
        }

        ListTag items = new ListTag();
        int filled = 0;

        // Per slot, prefer entries the author has explicitly pinned to that
        // slot (slotOverride == slot). Pinned items override the cookable /
        // fuel functional checks — the author has decided "this goes here"
        // regardless of recipe data. Only fall through to the auto bucket
        // when no entry was pinned to that slot.
        if (filled < k) {
            ContainerContentsEntry input = pickForSlot(pool, ContainerContentsEntry.SLOT_INPUT,
                e -> isCookable(e, rt, level) && e.slotOverride() == ContainerContentsEntry.SLOT_AUTO,
                localPos, worldSeed, carriageIndex);
            boolean wrote = appendRolled(items, ContainerContentsEntry.SLOT_INPUT, input,
                localPos, worldSeed, carriageIndex, registries);
            if (DebugFlags.logLootRolls()) {
                LOGGER.info("[DT-furnace] slot0 picked={} wrote={}",
                    input == null ? "null" : input.itemId(), wrote);
            }
            if (wrote) filled++;
        }

        if (filled < k) {
            ContainerContentsEntry fuel = pickForSlot(pool, ContainerContentsEntry.SLOT_FUEL,
                e -> isFuel(e, rt) && !isCookable(e, rt, level) && e.slotOverride() == ContainerContentsEntry.SLOT_AUTO,
                localPos, worldSeed, carriageIndex);
            boolean wrote = appendRolled(items, ContainerContentsEntry.SLOT_FUEL, fuel,
                localPos, worldSeed, carriageIndex, registries);
            if (DebugFlags.logLootRolls()) {
                LOGGER.info("[DT-furnace] slot1 picked={} wrote={}",
                    fuel == null ? "null" : fuel.itemId(), wrote);
            }
            if (wrote) filled++;
        }

        if (filled < k) {
            ContainerContentsEntry out2 = pickForSlot(pool, ContainerContentsEntry.SLOT_OUTPUT,
                e -> !isCookable(e, rt, level) && !isFuel(e, rt) && e.slotOverride() == ContainerContentsEntry.SLOT_AUTO,
                localPos, worldSeed, carriageIndex);
            boolean wrote = appendRolled(items, ContainerContentsEntry.SLOT_OUTPUT, out2,
                localPos, worldSeed, carriageIndex, registries);
            if (DebugFlags.logLootRolls()) {
                LOGGER.info("[DT-furnace] slot2 picked={} wrote={}",
                    out2 == null ? "null" : out2.itemId(), wrote);
            }
            if (wrote) filled++;
        }

        CompoundTag out = baseNbt == null ? new CompoundTag() : baseNbt.copy();
        out.put("Items", items);
        return out;
    }

    /**
     * Two-tier pick for a furnace slot: explicit override wins, then auto bucket.
     *
     * <p>Tier 1 — entries with {@code slotOverride == slot}. These are author-pinned
     * and override the functional checks (a wooden sword pinned to the fuel slot
     * lands in the fuel slot even though it's also cookable).</p>
     *
     * <p>Tier 2 — entries matching {@code autoBucket}. The {@code autoBucket} filter
     * should restrict to entries with {@code slotOverride == SLOT_AUTO} so pinned
     * entries don't get double-counted across slots.</p>
     */
    @Nullable
    private static ContainerContentsEntry pickForSlot(ContainerContentsPool pool, int slot,
                                                      Predicate<ContainerContentsEntry> autoBucket,
                                                      BlockPos localPos, long worldSeed, int carriageIndex) {
        ContainerContentsEntry explicit = pickFiltered(pool,
            e -> e.slotOverride() == slot,
            localPos, worldSeed, carriageIndex, slot);
        if (explicit != null) return explicit;
        return pickFiltered(pool, autoBucket, localPos, worldSeed, carriageIndex, slot);
    }

    /**
     * Weighted pick over the subset of pool entries matching {@code filter}.
     * Returns null when the subset is empty or all subset weights are 0.
     * Mixer uses {@code slot} so different slots produce independent picks.
     *
     * <p>Package-private so {@link EntityVariantApplicator}'s slot-aware fill
     * can reuse the same weighted-filter pick for per-equipment-slot rolls.</p>
     */
    @Nullable
    static ContainerContentsEntry pickFiltered(ContainerContentsPool pool,
                                               Predicate<ContainerContentsEntry> filter,
                                               BlockPos localPos, long worldSeed,
                                               int carriageIndex, int slot) {
        List<ContainerContentsEntry> subset = new ArrayList<>();
        int subsetWeight = 0;
        for (ContainerContentsEntry e : pool.entries()) {
            if (!filter.test(e)) continue;
            subset.add(e);
            subsetWeight += e.weight();
        }
        if (subset.isEmpty() || subsetWeight <= 0) return null;

        long mixed = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C)
            ^ ((long) slot * MIX_S);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed = mixed ^ (mixed >>> 31);
        long unsigned = mixed & 0x7FFFFFFFFFFFFFFFL;
        int target = (int) (unsigned % subsetWeight);
        for (ContainerContentsEntry e : subset) {
            target -= e.weight();
            if (target < 0) return e;
        }
        return subset.get(subset.size() - 1);
    }

    /** True if the entry's item has a positive burn time for the given cooking type. */
    private static boolean isFuel(ContainerContentsEntry e, RecipeType<?> recipeType) {
        Item item = resolveItem(e.itemId());
        if (item == null) return false;
        return new ItemStack(item).getBurnTime(recipeType) > 0;
    }

    /** True if the entry's item has a matching cooking recipe for the given type. */
    private static boolean isCookable(ContainerContentsEntry e,
                                      RecipeType<? extends AbstractCookingRecipe> recipeType,
                                      @Nullable Level level) {
        if (level == null) return false;
        Item item = resolveItem(e.itemId());
        if (item == null) return false;
        SingleRecipeInput input = new SingleRecipeInput(new ItemStack(item));
        Optional<? extends RecipeHolder<? extends AbstractCookingRecipe>> recipe =
            level.getRecipeManager().getRecipeFor(recipeType, input, level);
        return recipe.isPresent();
    }

    /**
     * Roll count + stack and write the NBT entry into {@code items} at
     * {@code slot}. Returns true when an entry was actually added — caller uses
     * this to decide whether the slot "consumed" a fill count.
     */
    private static boolean appendRolled(ListTag items, int slot, @Nullable ContainerContentsEntry picked,
                                        BlockPos localPos, long worldSeed, int carriageIndex,
                                        HolderLookup.Provider registries) {
        if (picked == null || picked.isAir()) return false;
        int rolledCount = rollItemCount(picked.count(), localPos, worldSeed, carriageIndex, slot);
        ItemStack stack = rollItemStack(picked, rolledCount, localPos, worldSeed, carriageIndex, slot, registries);
        if (stack.isEmpty()) return false;
        CompoundTag stackTag = (CompoundTag) stack.save(registries, new CompoundTag());
        stackTag.putByte("Slot", (byte) slot);
        items.add(stackTag);
        return true;
    }

    /**
     * Build the rolled {@link ItemStack} for a chosen pool entry. Materializes
     * the registered Item, clamps the stack size to its max, then conditionally
     * applies random durability and a random enchantment.
     *
     * <p>Each gate (durability, enchantment) uses an independent deterministic
     * salt so toggling one effect doesn't shift the other's roll outcome — the
     * same chest at the same world seed always produces the same loot.</p>
     *
     * <p>Package-private so {@link EntityVariantApplicator}'s slot-aware fill
     * can call the same per-stack roll with its slot ordinal as the slot key.</p>
     */
    static ItemStack rollItemStack(ContainerContentsEntry picked, int rolledCount,
                                           BlockPos localPos, long worldSeed,
                                           int carriageIndex, int slot,
                                           HolderLookup.Provider registries) {
        Item item = resolveItem(picked.itemId());
        if (item == null) return ItemStack.EMPTY;

        // Editor-only placeholder dungeontrain:random_book — substitute a
        // stamped vanilla WRITTEN_BOOK rolled from RandomBookRegistry. Pool
        // empty → return EMPTY so the slot stays empty rather than dropping
        // the useless placeholder into the world.
        // Furnace path note: a substituted written book lands in the output
        // slot (fails isCookable + isFuel). Cosmetically odd but harmless.
        if (item == ModItems.RANDOM_BOOK.get()) {
            long bookSeed = mix(localPos, worldSeed, carriageIndex, slot, SALT_RANDOM_BOOK);
            return RandomBookFactory.rollFromPool(bookSeed).orElse(ItemStack.EMPTY);
        }

        int maxStack = new ItemStack(item).getMaxStackSize();
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(maxStack, rolledCount)));

        if (picked.randomDurability() && stack.isDamageableItem()
            && rollChance(picked.durabilityChance(), localPos, worldSeed,
                          carriageIndex, slot, SALT_DUR_CHANCE)) {
            int max = stack.getMaxDamage();
            if (max > 1) {
                int damage = rollUniformInt(max - 1, localPos, worldSeed,
                    carriageIndex, slot, SALT_DUR_VALUE);
                stack.setDamageValue(damage);
            }
        }

        if (picked.randomEnchantment() && stack.isEnchantable()
            && rollChance(picked.enchantmentChance(), localPos, worldSeed,
                          carriageIndex, slot, SALT_ENCH_CHANCE)) {
            long seed = mix(localPos, worldSeed, carriageIndex, slot, SALT_ENCH_VALUE);
            RandomSource rs = RandomSource.create(seed);
            int level = 1 + rs.nextInt(30);
            Optional<HolderSet.Named<Enchantment>> nonTreasure = registries
                .lookup(Registries.ENCHANTMENT)
                .flatMap(reg -> reg.get(EnchantmentTags.IN_ENCHANTING_TABLE));
            if (nonTreasure.isPresent()) {
                EnchantmentHelper.enchantItem(rs, stack, level, nonTreasure.get().stream());
            }
        }

        // Otherwise-effectless ("Uncraftable") tipped arrows get a per-block
        // offensive effect. Keyed on (worldSeed, level) only — independent of
        // localPos/slot — so every arrow in the same 50-carriage block matches.
        applyEpochArrowEffect(stack, item, worldSeed, carriageIndex, registries);

        // Effectless drinkable / splash / lingering potions get the same per-block
        // treatment — a real effect plus a sticky bottle form. May return a NEW
        // stack when the rolled form differs from the found item, so reassign.
        stack = applyEpochPotionEffect(stack, item, worldSeed, carriageIndex, registries);

        long nameSeed = mix(localPos, worldSeed, carriageIndex, slot, SALT_NAME);
        RandomSource nameRng = RandomSource.create(nameSeed);
        NameComposer.applyName(stack, nameRng);

        long statsSeed = mix(localPos, worldSeed, carriageIndex, slot, SALT_STATS);
        RandomSource statsRng = RandomSource.create(statsSeed);
        StatsModifier.applyStats(stack, statsRng);

        RandomSource dyeChanceRng = RandomSource.create(
            mix(localPos, worldSeed, carriageIndex, slot, SALT_DYE_CHANCE));
        RandomSource dyeValueRng = RandomSource.create(
            mix(localPos, worldSeed, carriageIndex, slot, SALT_DYE_VALUE));
        ArmorAppearanceRoller.maybeApplyDye(stack, dyeChanceRng, dyeValueRng);

        RandomSource trimChanceRng = RandomSource.create(
            mix(localPos, worldSeed, carriageIndex, slot, SALT_TRIM_CHANCE));
        RandomSource trimPatternRng = RandomSource.create(
            mix(localPos, worldSeed, carriageIndex, slot, SALT_TRIM_PATTERN));
        RandomSource trimMaterialRng = RandomSource.create(
            mix(localPos, worldSeed, carriageIndex, slot, SALT_TRIM_MATERIAL));
        ArmorAppearanceRoller.maybeApplyTrim(
            stack, trimChanceRng, trimPatternRng, trimMaterialRng, registries);

        return stack;
    }

    /**
     * Apply the per-level offensive effect to an otherwise-effectless
     * ("Uncraftable") tipped arrow. No-op for any other item, and never
     * overwrites an arrow that already carries a potion.
     *
     * <p><b>Determinism exception:</b> unlike every other roll in this class,
     * the potion is keyed on {@code (worldSeed, level)} ONLY — deliberately not
     * {@code localPos}/{@code slot}. That is exactly what makes every
     * uncraftable arrow within the same 50-carriage block resolve to the same
     * effect. The {@code level} also selects an escalating power tier
     * ({@link #ARROW_EFFECT_TIERS}) so arrows grow nastier deeper into the run.</p>
     */
    static void applyEpochArrowEffect(ItemStack stack, Item item, long worldSeed,
                                      int carriageIndex, HolderLookup.Provider registries) {
        if (item != Items.TIPPED_ARROW) return;
        PotionContents existing = stack.get(DataComponents.POTION_CONTENTS);
        if (existing != null && existing.potion().isPresent()) return; // already tipped — leave it

        int level = arrowEffectLevel(carriageIndex);
        int tier = arrowEffectTierIndex(level);
        List<Holder<Potion>> pool = resolvePotions(ARROW_EFFECT_TIERS.get(tier), registries);
        if (pool.isEmpty()) return;

        int idx = arrowPotionIndex(worldSeed, carriageIndex, pool.size());
        Holder<Potion> potion = pool.get(idx);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));

        if (DebugFlags.logLootRolls()) {
            LOGGER.info("[DT-arrow] level={} tier={} potion={} carriageIdx={}",
                level, tier,
                potion.unwrapKey().map(k -> k.location().toString()).orElse("?"),
                carriageIndex);
        }
    }

    /**
     * Turn an otherwise-effectless drinkable / splash / lingering potion found
     * in loot into a real one: a vanilla potion effect from
     * {@link #POTION_EFFECT_TIERS} plus a (possibly different) bottle form, both
     * rolled once per 50-carriage block so every effectless potion in the block
     * matches and the pair escalates with travel.
     *
     * <p>Returns the input stack unchanged for non-potion items and for potions
     * that already carry a real effect. When the rolled form differs from the
     * found item the result is a <b>new</b> stack of that form (a found
     * "Uncraftable Potion" can come back as a "Splash Potion of Poison"), so the
     * caller must use the returned reference.</p>
     *
     * <p><b>Determinism exception</b> (as with the arrow path): the effect + form
     * are keyed on {@code (worldSeed, level)} ONLY — deliberately not
     * {@code localPos}/{@code slot} — which makes every effectless potion within
     * one 50-carriage block resolve identically.</p>
     */
    static ItemStack applyEpochPotionEffect(ItemStack stack, Item item, long worldSeed,
                                            int carriageIndex, HolderLookup.Provider registries) {
        if (!isPotionFormItem(item)) return stack;
        if (!isEffectlessPotion(stack)) return stack; // leave real-effect potions alone

        int level = epochLevel(carriageIndex);
        int tier = potionEffectTierIndex(level);
        List<Holder<Potion>> pool = resolvePotions(POTION_EFFECT_TIERS.get(tier), registries);
        if (pool.isEmpty()) return stack;

        int effIdx = potionEffectIndex(worldSeed, carriageIndex, pool.size());
        Holder<Potion> potion = pool.get(effIdx);

        Item formItem = potionFormItem(potionFormIndex(worldSeed, carriageIndex));
        ItemStack result = item == formItem ? stack : new ItemStack(formItem, stack.getCount());
        result.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));

        if (DebugFlags.logLootRolls()) {
            LOGGER.info("[DT-potion] level={} tier={} potion={} form={} carriageIdx={}",
                level, tier,
                potion.unwrapKey().map(k -> k.location().toString()).orElse("?"),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(formItem),
                carriageIndex);
        }
        return result;
    }

    /** True for the three drinkable / splash / lingering potion item types. */
    private static boolean isPotionFormItem(Item item) {
        return item == Items.POTION
            || item == Items.SPLASH_POTION
            || item == Items.LINGERING_POTION;
    }

    /** Map a {@link #potionFormIndex} result to its potion item (0/1/2 → drink/splash/lingering). */
    private static Item potionFormItem(int formIndex) {
        return switch (formIndex) {
            case 1 -> Items.SPLASH_POTION;
            case 2 -> Items.LINGERING_POTION;
            default -> Items.POTION;
        };
    }

    /**
     * True when {@code stack} carries no potion effect — absent contents, or a
     * no-effect base potion (water / mundane / thick / awkward) with no custom
     * effects. A potion with any real effect returns false so it is never
     * overwritten. {@link PotionContents#hasEffects()} is false for exactly the
     * effectless bases (and the empty "Uncraftable" contents).
     */
    private static boolean isEffectlessPotion(ItemStack stack) {
        PotionContents pc = stack.get(DataComponents.POTION_CONTENTS);
        return pc == null || !pc.hasEffects();
    }

    // ---- Shared epoch math (arrows + potions) -----------------------------

    /**
     * Progression level for an epoch roll = carriages travelled divided by the
     * epoch size. Symmetric for backward generation (negative indices).
     */
    static int epochLevel(int carriageIndex) {
        return Math.floorDiv(Math.abs(carriageIndex), EFFECT_EPOCH_CARRIAGES);
    }

    /** Map a level to a power tier in a {@code tierCount}-tier table, clamped to the top. */
    static int epochTierIndex(int level, int tierCount) {
        int tier = Math.max(0, level) / EFFECT_LEVELS_PER_TIER;
        return Math.min(tier, tierCount - 1);
    }

    /**
     * Deterministic index into a tier's pool, keyed on {@code (worldSeed, level,
     * salt)} only — independent of {@code localPos}/{@code slot} so every
     * effectless item in the block resolves to the same entry. The {@code salt}
     * decorrelates independent rolls (arrow effect vs potion effect vs form).
     */
    static int epochPotionIndex(long worldSeed, int carriageIndex, int poolSize, long salt) {
        if (poolSize <= 1) return 0;
        int level = epochLevel(carriageIndex);
        long state = worldSeed
            ^ ((long) level * MIX_C)
            ^ salt;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return (int) ((state & 0x7FFFFFFFFFFFFFFFL) % poolSize);
    }

    // ---- Arrow delegates (stable names for rollItemStack + ArrowEpochEffectTest)

    static int arrowEffectLevel(int carriageIndex) {
        return epochLevel(carriageIndex);
    }

    static int arrowEffectTierIndex(int level) {
        return epochTierIndex(level, ARROW_EFFECT_TIERS.size());
    }

    static int arrowPotionIndex(long worldSeed, int carriageIndex, int poolSize) {
        return epochPotionIndex(worldSeed, carriageIndex, poolSize, SALT_ARROW_EPOCH_POTION);
    }

    // ---- Potion seams (PotionEpochEffectTest) -----------------------------

    static int potionEffectTierIndex(int level) {
        return epochTierIndex(level, POTION_EFFECT_TIERS.size());
    }

    static int potionEffectIndex(long worldSeed, int carriageIndex, int poolSize) {
        return epochPotionIndex(worldSeed, carriageIndex, poolSize, SALT_POTION_EPOCH_EFFECT);
    }

    /** Sticky per-band form pick: 0 = drinkable, 1 = splash, 2 = lingering. */
    static int potionFormIndex(long worldSeed, int carriageIndex) {
        return epochPotionIndex(worldSeed, carriageIndex, POTION_FORM_COUNT, SALT_POTION_EPOCH_FORM);
    }

    /**
     * Resolve potion ids to holders via the {@code registries} provider
     * (mirroring the enchantment branch), dropping any that don't resolve so a
     * stripped/modded registry degrades to a shorter pool rather than crashing.
     */
    private static List<Holder<Potion>> resolvePotions(List<ResourceLocation> ids,
                                                       HolderLookup.Provider registries) {
        Optional<HolderLookup.RegistryLookup<Potion>> lookup =
            registries.lookup(Registries.POTION);
        if (lookup.isEmpty()) return List.of();
        List<Holder<Potion>> out = new ArrayList<>(ids.size());
        for (ResourceLocation id : ids) {
            lookup.get().get(ResourceKey.create(Registries.POTION, id)).ifPresent(out::add);
        }
        return out;
    }

    /**
     * Roll {@code true} with probability {@code pct} %, deterministically seeded
     * by {@code (localPos, worldSeed, carriageIndex, slot, salt)}. {@code pct=0}
     * never fires; {@code pct>=100} always fires.
     */
    private static boolean rollChance(int pct, BlockPos localPos, long worldSeed,
                                      int carriageIndex, int slot, long salt) {
        if (pct <= 0) return false;
        if (pct >= 100) return true;
        long state = mix(localPos, worldSeed, carriageIndex, slot, salt);
        long unsigned = state & 0x7FFFFFFFFFFFFFFFL;
        return (int) (unsigned % 100L) < pct;
    }

    /** Uniform integer in {@code [0, max]} (inclusive), deterministically seeded. */
    private static int rollUniformInt(int max, BlockPos localPos, long worldSeed,
                                      int carriageIndex, int slot, long salt) {
        if (max <= 0) return 0;
        long state = mix(localPos, worldSeed, carriageIndex, slot, salt);
        long unsigned = state & 0x7FFFFFFFFFFFFFFFL;
        return (int) (unsigned % (long)(max + 1));
    }

    /** Splittable-mix of the deterministic-roll inputs. */
    private static long mix(BlockPos localPos, long worldSeed, int carriageIndex, int slot, long salt) {
        long state = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C)
            ^ ((long) slot * MIX_S)
            ^ salt;
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return state;
    }

    @Nullable
    private static BlockEntityType<?> beTypeFor(BlockState state) {
        try {
            return state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock eb
                ? probeType(eb, state)
                : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BlockEntityType<?> probeType(net.minecraft.world.level.block.EntityBlock eb, BlockState state) {
        BlockEntity be = eb.newBlockEntity(BlockPos.ZERO, state);
        return be == null ? null : be.getType();
    }

    @Nullable
    private static ContainerContentsEntry pickEntry(ContainerContentsPool pool, int totalWeight,
                                                    BlockPos localPos, long worldSeed, int carriageIndex,
                                                    int slot) {
        long mixed = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C)
            ^ ((long) slot * MIX_S);
        // Splittable-mix
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed = mixed ^ (mixed >>> 31);
        long unsigned = mixed & 0x7FFFFFFFFFFFFFFFL;
        int target = (int) (unsigned % totalWeight);
        for (ContainerContentsEntry e : pool.entries()) {
            target -= e.weight();
            if (target < 0) return e;
        }
        return pool.entries().get(pool.entries().size() - 1);
    }

    /**
     * True if a fresh {@link BlockEntity} created from {@code state} is a
     * {@link Container}. Used by the menu controller to reject "look at a
     * non-container" toggle attempts up front.
     */
    public static boolean isContainerState(BlockState state) {
        return containerSlotsFor(state) > 0;
    }

    /** Lookup helper for a placed-world Item id, returns null if not registered. */
    @Nullable
    public static Item resolveItem(net.minecraft.resources.ResourceLocation id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
    }
}
