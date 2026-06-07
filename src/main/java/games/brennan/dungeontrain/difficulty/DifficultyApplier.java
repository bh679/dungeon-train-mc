package games.brennan.dungeontrain.difficulty;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.adventureitemstats.api.StatsModifier;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
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
     * (armor + weapon + enchants). PlayerMobs use this so they get
     * difficulty-scaled gear without buffs (see {@code PlayerMobGroupSpawner}).
     *
     * @return true if any modification was made, false if no-op (registry empty,
     *         tier returned null, etc.)
     */
    public static boolean apply(Mob mob, int carriageIndex, RandomSource rng, boolean applyEffects) {
        int carriagesPerTier = Math.max(1, DungeonTrainConfig.getCarriagesPerTier());
        int tierIndex = Math.abs(carriageIndex) / carriagesPerTier;
        // Tier 0 = vanilla baseline; no equipment, effects, or enchantments.
        // Real progression starts at tier 1 once the player has actually
        // travelled `carriagesPerTier` carriages while boarded.
        DifficultyTier tier = ProceduralTiers.tierFor(tierIndex);
        if (tier == null) return false;

        boolean armorOk = supportsArmor(mob);
        ServerLevel serverLevel = mob.level() instanceof ServerLevel sl ? sl : null;
        HolderLookup.Provider registries = serverLevel != null ? serverLevel.registryAccess() : null;

        if (armorOk) {
            applyArmorSlot(mob, EquipmentSlot.HEAD,  tier.armor().helmet(),     tier.armor().slotChance(), tier.enchant(), registries, rng);
            applyArmorSlot(mob, EquipmentSlot.CHEST, tier.armor().chestplate(), tier.armor().slotChance(), tier.enchant(), registries, rng);
            applyArmorSlot(mob, EquipmentSlot.LEGS,  tier.armor().leggings(),   tier.armor().slotChance(), tier.enchant(), registries, rng);
            applyArmorSlot(mob, EquipmentSlot.FEET,  tier.armor().boots(),      tier.armor().slotChance(), tier.enchant(), registries, rng);
            applyArmorSlot(mob, EquipmentSlot.MAINHAND, tier.weapon().mainhand(), tier.weapon().chance(), tier.enchant(), registries, rng);
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
                                       RandomSource rng) {
        if (pool.isEmpty() || slotChance <= 0.0) return;
        if (!mob.getItemBySlot(slot).isEmpty()) return;
        if (rng.nextDouble() >= slotChance) return;

        DifficultyTier.WeightedItem picked = weightedPick(pool, rng);
        if (picked == null) return;
        Item item = BuiltInRegistries.ITEM.get(picked.item());
        if (item == null) {
            LOGGER.warn("[DungeonTrain] Difficulty: unknown item '{}' in equipment pool — skipping slot {}", picked.item(), slot);
            return;
        }
        ItemStack stack = new ItemStack(item);
        if (enchant != null && enchant.maxLevel() > 0
                && enchant.chance() > 0.0 && rng.nextDouble() < enchant.chance()
                && stack.isEnchantable() && registries != null) {
            Optional<HolderSet.Named<Enchantment>> tag = registries
                    .lookup(Registries.ENCHANTMENT)
                    .flatMap(reg -> reg.get(EnchantmentTags.IN_ENCHANTING_TABLE));
            if (tag.isPresent()) {
                EnchantmentHelper.enchantItem(rng, stack, enchant.maxLevel(), tag.get().stream());
            }
        }
        NameComposer.applyName(stack, rng);
        StatsModifier.applyStats(stack, rng);
        mob.setItemSlot(slot, stack);
        mob.setDropChance(slot, DIFFICULTY_DROP_CHANCE);
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
