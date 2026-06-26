package games.brennan.dungeontrain.echo;

import games.brennan.playermob.entity.PlayerMobEntity;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Picks the most notable items a remote echo is carrying and renders each as a one-line descriptor
 * — name plus its stat, enchantments, and armor trim — for the encounter story. Captured once at
 * spawn (the only moment the geared {@link PlayerMobEntity} is in hand), so the strings can survive
 * into {@link EchoEncounter} after the entity is gone.
 *
 * <p>"Best" means <em>most notable</em>, not strictly the best combat swap: the score mirrors
 * PlayerMob's {@code EquipmentEvaluator.score()} (base attack-damage/armor + {@code 0.5 ×} enchant
 * levels, with a bow/crossbow fallback) but adds a rarity bonus so valuable non-gear loot (a totem,
 * an enchanted golden apple, an elytra) can surface too. Re-implemented here rather than calling
 * {@code EquipmentEvaluator.score()} — that method is package-private to PlayerMob — keeping this
 * DungeonTrain-only with no sibling-mod bump.</p>
 *
 * <p>Stateless; all methods static. The descriptor strings are resolved up front (no Minecraft types
 * leak into {@link EchoEncounterFormat}, which stays pure-string and unit-testable).</p>
 */
final class EchoItemHighlights {

    /** Each enchantment level adds this much to a stack's score (mirrors PlayerMob's coefficient). */
    private static final double ENCHANT_LEVEL_WEIGHT = 0.5;
    /** Bows/crossbows carry no ATTACK_DAMAGE modifier (damage is on the projectile) — score them here. */
    private static final double BOW_BASE_SCORE = 4.0;
    /** Per rarity tier above COMMON (UNCOMMON→2, RARE→4, EPIC→6) so rare loot floats up. */
    private static final double RARITY_WEIGHT = 2.0;

    private EchoItemHighlights() {}

    /** One ranked item: its display name (the dedup key), full descriptor, and value score. */
    record Scored(String name, String descriptor, double score) {}

    /**
     * The spawn snapshot: the top {@code max} descriptors, the score bar a later pickup must beat to
     * count as an upgrade, and the set of item names already in the story.
     */
    record Highlights(List<String> descriptors, double barScore, Set<String> names) {}

    /**
     * Every non-empty item the echo carries (six equipment slots + PlayerMob backpack), scored and
     * described, deduped by display name, best-first.
     */
    static List<Scored> ranked(PlayerMobEntity mob) {
        List<ItemStack> stacks = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            stacks.add(mob.getItemBySlot(slot));
        }
        SimpleContainer backpack = mob.getInventory();
        for (int i = 0; i < backpack.getContainerSize(); i++) {
            stacks.add(backpack.getItem(i));
        }
        stacks.removeIf(ItemStack::isEmpty);
        stacks.sort(Comparator.comparingDouble(EchoItemHighlights::score).reversed());

        List<Scored> out = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (ItemStack stack : stacks) {
            String name = stack.getHoverName().getString();
            if (!seenNames.add(name)) continue; // collapse a second copy of the same item
            out.add(new Scored(name, describe(stack, name), score(stack)));
        }
        return out;
    }

    /**
     * Snapshot the echo's up-to-{@code max} most notable items at spawn. {@code barScore} is the
     * weakest included item's score (the bar an in-encounter pickup must beat to be "better"), or
     * {@code NEGATIVE_INFINITY} when the echo spawned empty-handed.
     */
    static Highlights snapshot(PlayerMobEntity mob, int max) {
        List<Scored> ranked = ranked(mob);
        List<String> descriptors = new ArrayList<>(max);
        Set<String> names = new HashSet<>();
        double bar = Double.NEGATIVE_INFINITY;
        for (Scored s : ranked) {
            if (descriptors.size() >= max) break;
            descriptors.add(s.descriptor());
            names.add(s.name());
            bar = s.score(); // ranked is descending, so the last taken is the weakest included
        }
        return new Highlights(descriptors, bar, names);
    }

    /**
     * If the echo now holds a not-yet-mentioned item that beats its current best bar, record it as a
     * mid-encounter upgrade on {@code enc} and raise the bar to it. At most one per call, so a steady
     * stream of pickups reads as gradual gains rather than a dump.
     */
    static void collectUpgrades(PlayerMobEntity mob, EchoEncounter enc) {
        for (Scored s : ranked(mob)) {
            if (s.score() <= enc.bestBarScore) break;            // nothing better remains (sorted desc)
            if (enc.mentionedItemNames.contains(s.name())) continue; // already named in the story
            enc.acquiredItems.add(s.descriptor());
            enc.mentionedItemNames.add(s.name());
            enc.bestBarScore = s.score();
            return;
        }
    }

    // ---------------- scoring ----------------

    private static double score(ItemStack stack) {
        return baseStat(stack)
                + totalEnchantmentLevels(stack) * ENCHANT_LEVEL_WEIGHT
                + stack.getRarity().ordinal() * RARITY_WEIGHT;
    }

    @SuppressWarnings("deprecation") // Item.getDefaultAttributeModifiers — armor populates only this.
    private static double baseStat(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(
                DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        if (modifiers.modifiers().isEmpty()) {
            modifiers = stack.getItem().getDefaultAttributeModifiers();
        }
        double attack = sumAddValue(modifiers, Attributes.ATTACK_DAMAGE);
        if (attack > 0) return attack;
        double armor = sumAddValue(modifiers, Attributes.ARMOR);
        if (armor > 0) return armor;
        if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem) return BOW_BASE_SCORE;
        return 0;
    }

    private static double sumAddValue(ItemAttributeModifiers modifiers, Holder<Attribute> attribute) {
        double total = 0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().equals(attribute)
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                total += entry.modifier().amount();
            }
        }
        return total;
    }

    private static int totalEnchantmentLevels(ItemStack stack) {
        int total = 0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
            total += entry.getIntValue();
        }
        return total;
    }

    // ---------------- description ----------------

    /** {@code name (stat · enchants · trim)}, dropping any segment the item lacks. */
    private static String describe(ItemStack stack, String name) {
        List<String> segments = new ArrayList<>(3);
        String stat = statSegment(stack);
        if (stat != null) segments.add(stat);
        String enchants = enchantSegment(stack);
        if (enchants != null) segments.add(enchants);
        String trim = trimSegment(stack);
        if (trim != null) segments.add(trim);

        if (segments.isEmpty()) return name;
        return name + " (" + String.join(" · ", segments) + ")";
    }

    /** "8 attack" / "8 armor", or {@code null} when the item has neither. */
    @SuppressWarnings("deprecation")
    private static String statSegment(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(
                DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        if (modifiers.modifiers().isEmpty()) {
            modifiers = stack.getItem().getDefaultAttributeModifiers();
        }
        double attack = sumAddValue(modifiers, Attributes.ATTACK_DAMAGE);
        if (attack > 0) return number(attack) + " attack";
        double armor = sumAddValue(modifiers, Attributes.ARMOR);
        if (armor > 0) return number(armor) + " armor";
        return null;
    }

    /** "Sharpness V, Unbreaking III", or {@code null} when unenchanted. */
    private static String enchantSegment(ItemStack stack) {
        ItemEnchantments enchantments = stack.getEnchantments();
        if (enchantments.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            names.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
        }
        return String.join(", ", names);
    }

    /** "Silence trim in Netherite", or {@code null} when the item has no armor trim. */
    private static String trimSegment(ItemStack stack) {
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (trim == null) return null;
        String pattern = trim.pattern().value().description().getString();
        String material = trim.material().value().description().getString();
        return pattern + " trim in " + material;
    }

    /** Drop a trailing ".0" so whole stats read "8" not "8.0". */
    private static String number(double value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Double.toString(value);
    }
}
