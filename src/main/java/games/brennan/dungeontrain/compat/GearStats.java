package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.compat.GearValueFormula.Slot;
import games.brennan.dungeontrain.compat.GearValueFormula.Stats;
import games.brennan.dungeontrain.difficulty.BakedItemStats;
import games.brennan.dungeontrain.difficulty.ItemPowerScore;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads an {@link ItemStack} into the plain {@link Stats} {@link GearValueFormula} prices. Stats are
 * the stack's own: its {@code ATTRIBUTE_MODIFIERS} (where Adventure Item Stats writes rolls and DT's
 * level bonus), its {@code TOOL} component and its durability — so a rolled sword reads as rolled.
 */
public final class GearStats {

    /** A player's attack damage and attack speed with nothing held; item modifiers add to these. */
    private static final double PLAYER_BASE_ATTACK_DAMAGE = 1.0;
    private static final double PLAYER_BASE_ATTACK_SPEED = 4.0;

    /** Mining tier by the tag of blocks a tool cannot harvest (wood/gold 0 … diamond/netherite 3). */
    private static final List<Map.Entry<TagKey<Block>, Integer>> TIER_BY_INCORRECT_TAG = List.of(
        Map.entry(BlockTags.INCORRECT_FOR_WOODEN_TOOL, 0),
        Map.entry(BlockTags.INCORRECT_FOR_GOLD_TOOL, 0),
        Map.entry(BlockTags.INCORRECT_FOR_STONE_TOOL, 1),
        Map.entry(BlockTags.INCORRECT_FOR_IRON_TOOL, 2),
        Map.entry(BlockTags.INCORRECT_FOR_DIAMOND_TOOL, 3),
        Map.entry(BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 3));

    /** Vanilla material prefixes of gear ids; anything else is an unknown material. */
    private static final List<String> MATERIALS = List.of(
        "wooden", "stone", "iron", "golden", "diamond", "netherite", "leather", "chainmail");

    private GearStats() {}

    /** Which priced slot the stack is, or empty for anything the formula doesn't cover. */
    public static Optional<Slot> slotOf(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return Optional.empty();
        if (stack.is(ItemTags.SWORDS)) return Optional.of(Slot.SWORD);
        if (stack.is(ItemTags.AXES)) return Optional.of(Slot.AXE);
        if (stack.is(ItemTags.PICKAXES)) return Optional.of(Slot.PICKAXE);
        if (stack.is(ItemTags.SHOVELS)) return Optional.of(Slot.SHOVEL);
        if (stack.is(ItemTags.HOES)) return Optional.of(Slot.HOE);
        if (stack.getItem() instanceof ArmorItem armor) {
            return switch (armor.getType()) {
                case HELMET -> Optional.of(Slot.HELMET);
                case CHESTPLATE -> Optional.of(Slot.CHESTPLATE);
                case LEGGINGS -> Optional.of(Slot.LEGGINGS);
                case BOOTS -> Optional.of(Slot.BOOTS);
                default -> Optional.empty(); // horse and wolf armour
            };
        }
        return Optional.empty();
    }

    /** The stack's stats in {@code slot}. */
    @SuppressWarnings("deprecation") // Item.getDefaultAttributeModifiers — stacks without the component.
    public static Stats read(ItemStack stack, Slot slot) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        if (modifiers.modifiers().isEmpty()) modifiers = stack.getItem().getDefaultAttributeModifiers();
        int max = stack.getMaxDamage();
        return new Stats(slot, materialOf(stack),
            PLAYER_BASE_ATTACK_DAMAGE + sane(modifiers, Attributes.ATTACK_DAMAGE),
            PLAYER_BASE_ATTACK_SPEED + sane(modifiers, Attributes.ATTACK_SPEED),
            miningSpeed(stack.get(DataComponents.TOOL)),
            miningTier(stack.get(DataComponents.TOOL)),
            sane(modifiers, Attributes.ARMOR),
            sane(modifiers, Attributes.ARMOR_TOUGHNESS),
            sane(modifiers, Attributes.KNOCKBACK_RESISTANCE),
            Math.max(1, max - stack.getDamageValue()), Math.max(1, max),
            stack.has(DataComponents.FIRE_RESISTANT));
    }

    /** Enchantments on the item and stored on a book, by vanilla id path; other namespaces are dropped. */
    public static Map<String, Integer> enchantments(ItemStack stack) {
        Map<String, Integer> out = new HashMap<>();
        addAll(out, stack.get(DataComponents.ENCHANTMENTS));
        addAll(out, stack.get(DataComponents.STORED_ENCHANTMENTS));
        return Map.copyOf(out);
    }

    private static void addAll(Map<String, Integer> out, ItemEnchantments enchantments) {
        if (enchantments == null) return;
        for (var entry : enchantments.entrySet()) {
            Holder<Enchantment> holder = entry.getKey();
            holder.unwrapKey()
                .map(key -> key.location())
                .filter(id -> ResourceLocation.DEFAULT_NAMESPACE.equals(id.getNamespace()))
                .ifPresent(id -> out.merge(id.getPath(), entry.getIntValue(), Math::max));
        }
    }

    private static double sane(ItemAttributeModifiers modifiers, Holder<Attribute> attribute) {
        double amount = ItemPowerScore.sumAddValue(modifiers, attribute);
        return BakedItemStats.isAbsurd(amount) ? 0 : amount;
    }

    private static double miningSpeed(Tool tool) {
        if (tool == null) return 0;
        double best = 0;
        for (Tool.Rule rule : tool.rules()) {
            if (rule.speed().isPresent()) best = Math.max(best, rule.speed().get());
        }
        return best;
    }

    private static int miningTier(Tool tool) {
        if (tool == null) return 0;
        for (Tool.Rule rule : tool.rules()) {
            if (rule.correctForDrops().orElse(true)) continue;
            Optional<TagKey<Block>> tag = rule.blocks().unwrapKey();
            if (tag.isEmpty()) continue;
            for (Map.Entry<TagKey<Block>, Integer> e : TIER_BY_INCORRECT_TAG) {
                if (e.getKey().equals(tag.get())) return e.getValue();
            }
        }
        return 0;
    }

    private static String materialOf(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        for (String m : MATERIALS) {
            if (path.startsWith(m + "_")) return m;
        }
        return "";
    }
}
