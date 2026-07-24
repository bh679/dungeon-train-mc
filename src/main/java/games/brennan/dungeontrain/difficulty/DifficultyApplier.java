package games.brennan.dungeontrain.difficulty;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.adventureitemstats.api.StatsModifier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Applies a {@link DifficultyTier}'s armor / weapon / effects / enchantments to
 * a freshly-spawned DT carriage mob. Pure logic — no event wiring, no NBT
 * reading; the caller has already fished {@code pIdx} out of the entity's
 * persistent data and decided the mob should be touched.
 *
 * <p>Idempotency is enforced by the caller via a sticky marker tag
 * ({@link #APPLIED_TAG}); this class assumes it's called exactly once per
 * eligible mob.</p>
 *
 * <p>Pre-existing equipment is respected — if a variant's NBT already set, say,
 * a netherite chestplate, we don't overwrite it. Empty slots only.</p>
 */
public final class DifficultyApplier {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Sticky marker tag — present on a mob once we've rolled difficulty for it. */
    public static final String APPLIED_TAG = "dungeontrain_difficulty_applied";

    /** Drop chance for difficulty-applied gear — matches vanilla zombie/skeleton ~0.085. */
    private static final float DIFFICULTY_DROP_CHANCE = 0.05f;

    /**
     * How the per-tier AIS primary-stat bonus ({@link ItemStatLevelScaling}) scales an
     * applied equipment piece — attack damage on weapons, armor on armor.
     */
    public enum StatScaling {
        /** No per-tier stat bonus — plain AIS roll. The baseline for ungeared vanilla parity. */
        NONE,
        /** Scale off the full difficulty tier (strong from tier 1). PlayerMobs use this. */
        FULL,
        /**
         * Scale off the post-material-cap tier
         * ({@code max(0, tier - }{@link ProceduralTiers#MATERIAL_CAP_LEVEL}{@code )}) — the
         * bonus is 0 until gear material ceilings at netherite (level 50), then climbs so
         * hostile gear keeps getting stronger past the plateau. Regular carriage hostiles
         * use this; tiers ≤ 50 stay identical to a plain AIS roll.
         */
        PAST_MATERIAL_CAP
    }

    private DifficultyApplier() {}

    /**
     * Returns true when this mob type renders / interacts with armor slots
     * meaningfully. Bosses and blob-shape mobs are excluded — armor on a slime
     * is invisible and a netherite ghast is just silly. Potion effects still
     * apply to all of them.
     */
    public static boolean supportsArmor(Mob mob) {
        return !(mob instanceof Slime
                || mob instanceof MagmaCube
                || mob instanceof Ghast
                || mob instanceof Phantom
                || mob instanceof EnderDragon
                || mob instanceof WitherBoss);
    }

    /**
     * Roll and apply the difficulty tier for {@code carriageIndex} onto
     * {@code mob}, including potion effects. Tags the mob with
     * {@link #APPLIED_TAG} on completion.
     *
     * @return true if any modification was made, false if no-op (registry empty,
     *         tier returned null, etc.)
     */
    public static boolean apply(Mob mob, int carriageIndex, RandomSource rng) {
        return apply(mob, carriageIndex, rng, true);
    }

    /**
     * As {@link #apply(Mob, int, RandomSource)}, but {@code applyEffects} gates
     * the potion-effect pass — pass {@code false} to roll only equipment
     * (armor + weapon + enchants). Applies no per-tier stat bonus
     * ({@link StatScaling#NONE}); callers that want scaled item stats use the
     * {@link #apply(Mob, int, RandomSource, boolean, StatScaling)} overload.
     *
     * @return true if any modification was made, false if no-op (registry empty,
     *         tier returned null, etc.)
     */
    public static boolean apply(Mob mob, int carriageIndex, RandomSource rng, boolean applyEffects) {
        return apply(mob, carriageIndex, rng, applyEffects, StatScaling.NONE);
    }

    /**
     * As {@link #apply(Mob, int, RandomSource, boolean)}, but {@code statScaling}
     * opts into difficulty-scaled item stats: each rolled equipment piece additionally
     * gets a flat {@link ItemStatLevelScaling#primaryStatBonus primary-stat bonus}
     * sized per {@link StatScaling}. {@link StatScaling#FULL} scales off the current
     * tier (PlayerMobs); {@link StatScaling#PAST_MATERIAL_CAP} scales off the
     * post-netherite-cap tier so regular carriage hostiles keep gaining gear strength
     * past level 50 while tiers ≤ 50 stay a plain AIS roll; {@link StatScaling#NONE}
     * adds no bonus.
     *
     * @return true if any modification was made, false if no-op (registry empty,
     *         tier returned null, etc.)
     */
    public static boolean apply(Mob mob, int carriageIndex, RandomSource rng,
                                boolean applyEffects, StatScaling statScaling) {
        // carriageIndex here is always live player progress (maxTravelledCarriageIndex),
        // which already folds in the difficulty travelled-offset at its source — so a
        // plain tierForTravelled here picks up an admin offset without double-applying.
        int tierIndex = DifficultyProgression.tierForTravelled(carriageIndex);
        // Tier 0 = vanilla baseline; no equipment, effects, or enchantments.
        // Real progression starts at tier 1 once the player has actually
        // travelled `carriagesPerTier` carriages while boarded.
        DifficultyTier tier = ProceduralTiers.tierFor(tierIndex);
        if (tier == null) return false;

        // The tier feeding the per-tier AIS stat bonus: 0 = none (baseline), full tier
        // (PlayerMobs), or the post-material-cap tier (regular hostiles keep scaling
        // past the netherite plateau — 0 at/below level 50, then climbing).
        int statScalingTier = switch (statScaling) {
            case NONE -> 0;
            case FULL -> tierIndex;
            case PAST_MATERIAL_CAP ->
                    ItemStatLevelScaling.pastCapTier(tierIndex, ProceduralTiers.MATERIAL_CAP_LEVEL);
        };

        boolean armorOk = supportsArmor(mob);
        ServerLevel serverLevel = mob.level() instanceof ServerLevel sl ? sl : null;
        HolderLookup.Provider registries = serverLevel != null ? serverLevel.registryAccess() : null;

        if (armorOk) {
            applyArmorSlot(mob, EquipmentSlot.HEAD,  tier.armor().helmet(),     tier.armor().slotChance(), tier.enchant(), registries, rng, statScalingTier, tierIndex);
            applyArmorSlot(mob, EquipmentSlot.CHEST, tier.armor().chestplate(), tier.armor().slotChance(), tier.enchant(), registries, rng, statScalingTier, tierIndex);
            applyArmorSlot(mob, EquipmentSlot.LEGS,  tier.armor().leggings(),   tier.armor().slotChance(), tier.enchant(), registries, rng, statScalingTier, tierIndex);
            applyArmorSlot(mob, EquipmentSlot.FEET,  tier.armor().boots(),      tier.armor().slotChance(), tier.enchant(), registries, rng, statScalingTier, tierIndex);
            applyWeaponSlot(mob, tier.weapon().mainhand(), tier.weapon().chance(), tier.enchant(), registries, rng, statScalingTier, tierIndex);
        }

        if (applyEffects) {
            for (DifficultyTier.EffectSpec spec : tier.effects()) {
                if (rng.nextDouble() >= spec.chance()) continue;
                Holder<MobEffect> holder = lookupEffect(spec.id());
                if (holder == null) {
                    LOGGER.warn("[DungeonTrain] Difficulty: unknown mob effect '{}' in tier '{}' — skipping",
                            spec.id(), tier.name());
                    continue;
                }
                int duration = spec.durationTicks() < 0 ? Integer.MAX_VALUE : spec.durationTicks();
                mob.addEffect(new MobEffectInstance(holder, duration, Math.max(0, spec.amplifier()), false, true));
            }
        }

        mob.addTag(APPLIED_TAG);
        return true;
    }

    private static void applyArmorSlot(Mob mob, EquipmentSlot slot,
                                       List<DifficultyTier.WeightedItem> pool,
                                       double slotChance,
                                       DifficultyTier.EnchantSpec enchant,
                                       HolderLookup.Provider registries,
                                       RandomSource rng,
                                       int statScalingTier,
                                       int tierIndex) {
        if (pool.isEmpty() || slotChance <= 0.0) return;
        if (!mob.getItemBySlot(slot).isEmpty()) return;
        if (rng.nextDouble() >= slotChance) return;

        ItemStack stack = rollEquipment(pool, enchant, registries, rng, statScalingTier, tierIndex);
        if (stack == null) return;
        mob.setItemSlot(slot, stack);
        mob.setDropChance(slot, DIFFICULTY_DROP_CHANCE);
    }

    /**
     * Mainhand weapon roll, ranged-aware. Mirrors {@link #applyArmorSlot} for an
     * <em>empty</em> mainhand (weighted-pick the tier weapon, enchant, name, drop
     * chance). When the mob already holds a <em>ranged</em> weapon — a bow or
     * crossbow from its vanilla default equipment ({@code finalizeSpawn}) — the
     * weapon is kept and merely enchanted with the tier enchant, so higher tiers
     * scale a skeleton's bow / pillager's crossbow instead of swapping it for a
     * melee weapon (which would strip the mob of its ranged attack). Any other
     * pre-existing mainhand (melee default, author-baked) is left untouched — the
     * same "empty slots only" contract the armor slots honour.
     */
    private static void applyWeaponSlot(Mob mob,
                                        List<DifficultyTier.WeightedItem> pool,
                                        double chance,
                                        DifficultyTier.EnchantSpec enchant,
                                        HolderLookup.Provider registries,
                                        RandomSource rng,
                                        int statScalingTier,
                                        int tierIndex) {
        ItemStack current = mob.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!current.isEmpty()) {
            if (isRangedWeapon(current)) {
                maybeEnchant(current, enchant, registries, rng, tierIndex);
                mob.setItemSlot(EquipmentSlot.MAINHAND, current);
            }
            return;
        }
        if (pool.isEmpty() || chance <= 0.0) return;
        if (rng.nextDouble() >= chance) return;

        ItemStack stack = rollEquipment(pool, enchant, registries, rng, statScalingTier, tierIndex);
        if (stack == null) return;
        mob.setItemSlot(EquipmentSlot.MAINHAND, stack);
        mob.setDropChance(EquipmentSlot.MAINHAND, DIFFICULTY_DROP_CHANCE);
    }

    /**
     * Roll one equipment piece from {@code pool}: weighted-pick → tier enchant →
     * AIN name → AIS stats. {@code statScalingTier} (0 = none) adds a flat
     * {@link ItemStatLevelScaling#primaryStatBonus primary-stat bonus} on top of the
     * AIS roll. Returns {@code null} when the pool is empty or the picked item id
     * doesn't resolve. Does not touch the mob — the caller places the stack and sets
     * its drop chance.
     */
    private static ItemStack rollEquipment(List<DifficultyTier.WeightedItem> pool,
                                           DifficultyTier.EnchantSpec enchant,
                                           HolderLookup.Provider registries,
                                           RandomSource rng,
                                           int statScalingTier,
                                           int tierIndex) {
        DifficultyTier.WeightedItem picked = weightedPick(pool, rng);
        if (picked == null) return null;
        Item item = BuiltInRegistries.ITEM.get(picked.item());
        if (item == null) {
            LOGGER.warn("[DungeonTrain] Difficulty: unknown item '{}' in equipment pool — skipping", picked.item());
            return null;
        }
        ItemStack stack = new ItemStack(item);
        maybeEnchant(stack, enchant, registries, rng, tierIndex);
        NameComposer.applyName(stack, rng);
        StatsModifier.applyStats(stack, rng,
            ItemStatLevelScaling.primaryStatBonus(stack, statScalingTier));
        return stack;
    }

    /**
     * Roll the tier enchant onto {@code stack} in place. No-op when the spec is
     * empty / zero-chance, the chance roll fails, the item isn't enchantable, or
     * registries are unavailable. Draws from the same enchanting-table pool vanilla
     * uses, so only item-valid enchants apply (Power on a bow, Quick Charge on a
     * crossbow, etc.).
     */
    private static void maybeEnchant(ItemStack stack,
                                     DifficultyTier.EnchantSpec enchant,
                                     HolderLookup.Provider registries,
                                     RandomSource rng,
                                     int tierIndex) {
        if (enchant == null || enchant.maxLevel() <= 0 || enchant.chance() <= 0.0) return;
        if (rng.nextDouble() >= enchant.chance()) return;
        if (!stack.isEnchantable() || registries == null) return;
        Optional<HolderSet.Named<Enchantment>> tag = registries
                .lookup(Registries.ENCHANTMENT)
                .flatMap(reg -> reg.get(EnchantmentTags.IN_ENCHANTING_TABLE));
        if (tag.isPresent()) {
            EnchantmentHelper.enchantItem(rng, stack, enchant.maxLevel(), tag.get().stream());
            // Clamp to DT's progression cap so a cap-remover mod can't push mob gear past the
            // carriage's difficulty tier (vanillaMax + tier/10); inert without such a mod.
            EnchantLevelCap.clampToProgression(stack, tierIndex);
        }
    }

    /** Bow or crossbow — the ranged weapons a mob's default equipment supplies. */
    private static boolean isRangedWeapon(ItemStack stack) {
        return stack.getItem() instanceof ProjectileWeaponItem;
    }

    private static DifficultyTier.WeightedItem weightedPick(List<DifficultyTier.WeightedItem> pool, RandomSource rng) {
        int total = 0;
        for (DifficultyTier.WeightedItem wi : pool) total += wi.weight();
        if (total <= 0) return null;
        int roll = rng.nextInt(total);
        int acc = 0;
        for (DifficultyTier.WeightedItem wi : pool) {
            acc += wi.weight();
            if (roll < acc) return wi;
        }
        return pool.get(pool.size() - 1);
    }

    private static Holder<MobEffect> lookupEffect(ResourceLocation id) {
        MobEffect raw = BuiltInRegistries.MOB_EFFECT.get(id);
        if (raw == null) return null;
        return BuiltInRegistries.MOB_EFFECT.wrapAsHolder(raw);
    }

    /** Public helper: should this mob be considered for difficulty at all? */
    public static boolean isEligible(Mob mob, boolean affectsBabyMobs) {
        if (mob.getTags().contains(APPLIED_TAG)) return false;
        if (!affectsBabyMobs && mob instanceof AgeableMob a && a.isBaby()) return false;
        return true;
    }
}
