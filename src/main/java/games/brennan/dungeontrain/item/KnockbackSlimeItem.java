package games.brennan.dungeontrain.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Knockback Slime: a single-use slimeball that hits like Knockback X.
 *
 * <p>Land a melee hit while holding it and the target is thrown back with the force of a Knockback
 * {@value #KNOCKBACK_LEVEL} weapon, then one slimeball is spent (not in creative). Swinging at air or
 * at something that can't be hurt doesn't fire {@link #hurtEnemy}, so it is only used up on a real hit.</p>
 *
 * <p>The knockback is the item's own, not a stored {@code minecraft:knockback} enchantment: enchantments
 * are data-driven in 1.21.1 and can't sit in an item's default components, and a stored one could be
 * ground off. The glint and the "Knockback X" tooltip line give it the enchanted look instead.</p>
 */
public class KnockbackSlimeItem extends Item {

    /** The Knockback level this slimeball hits like. */
    public static final int KNOCKBACK_LEVEL = 10;

    /** Vanilla turns each knockback level into half a unit of push ({@code Player#attack}). */
    private static final float STRENGTH_PER_LEVEL = 0.5F;

    public KnockbackSlimeItem(Properties properties) {
        super(properties);
    }

    /** Push strength handed to {@link LivingEntity#knockback} for a level-{@value #KNOCKBACK_LEVEL} hit. */
    public static double knockbackStrength() {
        return KNOCKBACK_LEVEL * STRENGTH_PER_LEVEL;
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker.level().isClientSide()) {
            return true;
        }
        float yawRadians = attacker.getYRot() * Mth.DEG_TO_RAD;
        target.knockback(knockbackStrength(), Mth.sin(yawRadians), -Mth.cos(yawRadians));
        // Players own their motion client-side; hurtMarked resyncs the push to them.
        target.hurtMarked = true;
        stack.consume(1, attacker);
        return true;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("enchantment.minecraft.knockback")
            .append(" ")
            .append(Component.translatable("enchantment.level." + KNOCKBACK_LEVEL))
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.dungeontrain.knockback_slime.tooltip").withStyle(ChatFormatting.DARK_GRAY));
    }
}
