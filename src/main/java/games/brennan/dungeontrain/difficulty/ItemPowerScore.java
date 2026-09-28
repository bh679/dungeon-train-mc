package games.brennan.dungeontrain.difficulty;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * How powerful one {@link ItemStack} is, as a pair of raw numbers: its headline combat stat and its
 * total enchantment weight. The single definition used wherever the mod ranks gear — the echo
 * encounter story ({@code EchoItemHighlights}) and mob experience drops
 * ({@link MobExperienceScaling}) — so "best item" means the same thing in both.
 *
 * <p>{@link #baseStat} deliberately reports <em>one</em> stat per item (attack damage if the item
 * has any, else armor) rather than a sum: a sword and a chestplate are then directly comparable on
 * a single scale. It reads the {@link DataComponents#ATTRIBUTE_MODIFIERS} component first, which is
 * where AIS writes DT's past-netherite stat scaling, so difficulty-scaled gear scores above its
 * vanilla equivalent for free.</p>
 *
 * <p>Stateless and side-effect-free; all methods static.</p>
 */
public final class ItemPowerScore {

    /** Bows/crossbows carry no ATTACK_DAMAGE modifier (damage is on the projectile) — score them here. */
    public static final double BOW_BASE_SCORE = 4.0;

    private ItemPowerScore() {}

    /**
     * The item's headline combat stat: attack damage if it has any, else armor, else a flat score for
     * bows and crossbows, else 0.
     *
     * <p>Clamped to {@link BakedItemStats#MAX_SANE_MODIFIER}. Items baked into saved structure
     * templates can carry multi-million attribute amounts (the corruption {@code BakedItemStats}
     * exists to repair); without the clamp one of those would dominate every score it entered.</p>
     */
    @SuppressWarnings("deprecation") // Item.getDefaultAttributeModifiers — armor populates only this.
    public static double baseStat(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        ItemAttributeModifiers modifiers = stack.getOrDefault(
                DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        if (modifiers.modifiers().isEmpty()) {
            modifiers = stack.getItem().getDefaultAttributeModifiers();
        }
        double attack = sumAddValue(modifiers, Attributes.ATTACK_DAMAGE);
        if (attack > 0) return sane(attack);
        double armor = sumAddValue(modifiers, Attributes.ARMOR);
        if (armor > 0) return sane(armor);
        if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem) return BOW_BASE_SCORE;
        return 0;
    }

    /** Sum of every enchantment level on {@code stack} (Sharpness V + Unbreaking III = 8). */
    public static int totalEnchantmentLevels(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        int total = 0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
            total += entry.getIntValue();
        }
        return total;
    }

    /** Total of every {@link AttributeModifier.Operation#ADD_VALUE} modifier for one attribute. */
    public static double sumAddValue(ItemAttributeModifiers modifiers, Holder<Attribute> attribute) {
        double total = 0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().equals(attribute)
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                total += entry.modifier().amount();
            }
        }
        return total;
    }

    /** Discards a corrupt amount rather than clamping it — an absurd stat is noise, not a big number. */
    private static double sane(double amount) {
        return BakedItemStats.isAbsurd(amount) ? 0 : amount;
    }
}
