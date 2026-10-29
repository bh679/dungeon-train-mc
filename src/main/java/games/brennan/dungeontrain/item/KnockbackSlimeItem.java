package games.brennan.dungeontrain.item;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.BandAdvancements;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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

    /** {@code gameplay_action} for {@code secrete_menu/knockback_slime_hit}. */
    public static final String HIT_ADVANCEMENT_ACTION = "knockback_slime_hit";

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
        if (attacker instanceof ServerPlayer player) {
            grantHitAdvancement(player);
        }
        stack.consume(1, attacker);
        return true;
    }

    /**
     * The Secret Menu's "hit something with a Knockback Slime". Only counts once the player has opened
     * that tab: earned before its root, it would have no visible parent and the client would draw it as
     * a stray tab of its own (same gate as {@code ReverseJourneyAdvancements#grantAfterPredecessor}).
     */
    private static void grantHitAdvancement(ServerPlayer player) {
        AdvancementHolder root = player.server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath(
            DungeonTrain.MOD_ID, BandAdvancements.SECRETE_MENU_PREFIX + BandAdvancements.REVERSE_ANCHOR));
        if (root == null || !player.getAdvancements().getOrStartProgress(root).isDone()) {
            return;
        }
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, HIT_ADVANCEMENT_ACTION);
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
