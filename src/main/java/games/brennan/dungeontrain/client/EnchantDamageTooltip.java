package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentValueEffect;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.AttributeUtil;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Counts damage enchantments in a weapon's hover text. The Attack Damage line only sums attribute
 * modifiers (vanilla's, and the ones AIS rolls), but enchantments add their damage at hit time, so
 * Sharpness never reached the number.
 *
 * <p>An unconditional bonus (Sharpness) goes into the Attack Damage number. A bonus that only applies
 * to some mobs (Smite, Bane of Arthropods, Impaling) gets its own line, so the main number stays true
 * for every target. Amounts come from each enchantment's data, never from a copy of its formula.</p>
 *
 * <p>Display only: this edits the tooltip's lines and leaves the stack's attributes alone. Writing the
 * bonus to an attribute would count it twice in combat.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EnchantDamageTooltip {

    /** The line NeoForge writes for a base modifier: {@code "<value> <attribute name>"}. */
    private static final String BASE_LINE_KEY = "attribute.modifier.equals.0";

    /** Conditional damage enchantments, and the key for the line naming their targets. */
    private static final Map<ResourceKey<Enchantment>, String> CONDITIONAL_KEYS = Map.of(
            Enchantments.SMITE, "tooltip.dungeontrain.enchant_damage.undead",
            Enchantments.BANE_OF_ARTHROPODS, "tooltip.dungeontrain.enchant_damage.arthropods",
            Enchantments.IMPALING, "tooltip.dungeontrain.enchant_damage.aquatic");

    /** Only {@code remove_binomial} rolls, and no vanilla damage enchantment uses it. */
    private static final RandomSource RANDOM = RandomSource.create(0L);

    private EnchantDamageTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!stack.isEnchanted()) return;

        Attribute attackDamage = Attributes.ATTACK_DAMAGE.value();
        List<Component> lines = event.getToolTip();
        int index = baseDamageLine(lines, attackDamage.getDescriptionId());
        if (index < 0) return;

        float flatBonus = 0F;
        List<Component> conditionalLines = new ArrayList<>();
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
            Holder<Enchantment> enchantment = entry.getKey();
            int level = entry.getIntValue();
            flatBonus += damageBonus(enchantment.value(), level, false);
            float conditional = damageBonus(enchantment.value(), level, true);
            Optional<String> key = enchantment.unwrapKey().map(CONDITIONAL_KEYS::get);
            if (conditional > 0F && key.isPresent()) {
                conditionalLines.add(conditionalLine(key.get(), conditional));
            }
        }

        if (flatBonus != 0F) {
            Optional<Component> rebuilt = rebuiltBaseLine(stack, event.getEntity(), event.getFlags(), flatBonus);
            if (rebuilt.isPresent()) lines.set(index, rebuilt.get());
        }
        lines.addAll(index + 1, conditionalLines);
    }

    /**
     * The summed damage {@code enchantment} adds at {@code level}, from only its conditional effects or
     * only its unconditional ones. Package-visible for unit testing.
     */
    static float damageBonus(Enchantment enchantment, int level, boolean conditional) {
        return damageBonus(enchantment.getEffects(EnchantmentEffectComponents.DAMAGE), level, conditional);
    }

    static float damageBonus(List<ConditionalEffect<EnchantmentValueEffect>> effects, int level, boolean conditional) {
        float total = 0F;
        for (ConditionalEffect<EnchantmentValueEffect> effect : effects) {
            if (effect.requirements().isPresent() == conditional) {
                total += effect.effect().process(level, RANDOM, 0F);
            }
        }
        return total;
    }

    /** Index of the base Attack Damage line, or {@code -1} when the tooltip has none. Package-visible for unit testing. */
    static int baseDamageLine(List<Component> lines, String attributeNameKey) {
        for (int i = 0; i < lines.size(); i++) {
            if (isBaseLineOf(lines.get(i), attributeNameKey)) return i;
        }
        return -1;
    }

    private static boolean isBaseLineOf(Component line, String attributeNameKey) {
        if (line.getContents() instanceof TranslatableContents outer && outer.getKey().equals(BASE_LINE_KEY)) {
            for (Object arg : outer.getArgs()) {
                if (arg instanceof Component name
                        && name.getContents() instanceof TranslatableContents inner
                        && inner.getKey().equals(attributeNameKey)) {
                    return true;
                }
            }
        }
        return line.getSiblings().stream().anyMatch(sibling -> isBaseLineOf(sibling, attributeNameKey));
    }

    /** The base line again with {@code bonus} added, built as {@code AttributeUtil.applyTextFor} builds it. */
    private static Optional<Component> rebuiltBaseLine(ItemStack stack, Player player,
                                                       TooltipFlag flag, float bonus) {
        Holder<Attribute> attackDamage = Attributes.ATTACK_DAMAGE;
        ResourceLocation baseId = attackDamage.value().getBaseId();
        for (AttributeModifier modifier : AttributeUtil.getSortedModifiers(stack, EquipmentSlotGroup.MAINHAND).get(attackDamage)) {
            if (!modifier.id().equals(baseId)) continue;
            double entityBase = player == null ? 0 : player.getAttributeBaseValue(attackDamage);
            double value = modifier.amount() + entityBase + bonus;
            return Optional.of(Component.literal(" ")
                    .append(attackDamage.value().toBaseComponent(value, entityBase, false, flag))
                    .withStyle(ChatFormatting.DARK_GREEN));
        }
        return Optional.empty();
    }

    private static Component conditionalLine(String key, float bonus) {
        return Component.literal(" ")
                .append(Component.translatable(key, ItemAttributeModifiers.ATTRIBUTE_MODIFIER_FORMAT.format(bonus)))
                .withStyle(ChatFormatting.DARK_GREEN);
    }
}
