package games.brennan.dungeontrain.difficulty;

import games.brennan.dungeontrain.block.VaultGreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Vault rewards carry stronger stats than other loot, by vault type: damage and armor take the full
 * boost, attack speed and toughness half of it.
 *
 * <p>Applied to the finished reward as the vault hands it over ({@code VaultServerMixin}), so it covers
 * DT pool rolls and vanilla loot tables alike, and includes AIS's roll and DT's tier bonus. Each stat
 * scales by its <em>effective</em> value, as AIS's own roll does: a sword's attack speed modifier is
 * negative, so multiplying the raw amount would slow it down.</p>
 */
public final class VaultStatBoost {

    /** Attack damage and attack speed a player has with nothing held; private in AIS's {@code StatsModifier}. */
    private static final double PLAYER_BASE_ATTACK_DAMAGE = 1.0;
    private static final double PLAYER_BASE_ATTACK_SPEED = 4.0;

    /** A vault type's multipliers: {@code primary} for damage and armor, {@code secondary} for speed and toughness. */
    public enum Tier {
        REGULAR(1.2, 1.1),
        OMINOUS(1.4, 1.2),
        GREEN(1.5, 1.25);

        final double primary;
        final double secondary;

        Tier(double primary, double secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }
    }

    private VaultStatBoost() {}

    /** The tier a vault in {@code state} rewards at. */
    public static Tier tierOf(BlockState state) {
        if (VaultGreen.isGreen(state)) return Tier.GREEN;
        return state.getValue(VaultBlock.OMINOUS) ? Tier.OMINOUS : Tier.REGULAR;
    }

    /** {@code rewards} at {@code tier}, as a new list. The input stacks are left untouched. */
    public static List<ItemStack> boosted(List<ItemStack> rewards, Tier tier) {
        List<ItemStack> out = new ArrayList<>(rewards.size());
        for (ItemStack stack : rewards) out.add(boosted(stack, tier));
        return out;
    }

    /** A copy of {@code stack} with its combat stats at {@code tier}; the stack itself when it has none. */
    public static ItemStack boosted(ItemStack stack, Tier tier) {
        ItemAttributeModifiers current = stack.getOrDefault(
            DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        // Armor that never went through AIS keeps its stats on the item, not the component.
        if (current.modifiers().isEmpty()) current = stack.getItem().getDefaultAttributeModifiers();

        boolean changed = false;
        List<ItemAttributeModifiers.Entry> rebuilt = new ArrayList<>(current.modifiers().size());
        for (ItemAttributeModifiers.Entry entry : current.modifiers()) {
            ItemAttributeModifiers.Entry next = boosted(entry, tier);
            changed |= next != entry;
            rebuilt.add(next);
        }
        if (!changed) return stack;

        ItemStack out = stack.copy();
        out.set(DataComponents.ATTRIBUTE_MODIFIERS, new ItemAttributeModifiers(rebuilt, current.showInTooltip()));
        return out;
    }

    /** {@code entry} at {@code tier}; the entry itself unless it is an added amount of one of the four stats. */
    private static ItemAttributeModifiers.Entry boosted(ItemAttributeModifiers.Entry entry, Tier tier) {
        AttributeModifier modifier = entry.modifier();
        if (modifier.operation() != AttributeModifier.Operation.ADD_VALUE) return entry;
        Holder<Attribute> attribute = entry.attribute();
        double amount;
        if (attribute.equals(Attributes.ATTACK_DAMAGE)) {
            amount = boostedAmount(PLAYER_BASE_ATTACK_DAMAGE, modifier.amount(), tier.primary);
        } else if (attribute.equals(Attributes.ARMOR)) {
            amount = boostedAmount(0.0, modifier.amount(), tier.primary);
        } else if (attribute.equals(Attributes.ATTACK_SPEED)) {
            amount = boostedAmount(PLAYER_BASE_ATTACK_SPEED, modifier.amount(), tier.secondary);
        } else if (attribute.equals(Attributes.ARMOR_TOUGHNESS)) {
            amount = boostedAmount(0.0, modifier.amount(), tier.secondary);
        } else {
            return entry;
        }
        AttributeModifier scaled = new AttributeModifier(modifier.id(), amount, modifier.operation());
        return new ItemAttributeModifiers.Entry(attribute, scaled, entry.slot());
    }

    /** The modifier amount that makes {@code playerBase + amount} worth {@code multiplier} times as much. Pure. */
    static double boostedAmount(double playerBase, double amount, double multiplier) {
        return (playerBase + amount) * multiplier - playerBase;
    }
}
