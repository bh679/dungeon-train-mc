package games.brennan.dungeontrain.item;

import games.brennan.dungeontrain.perks.PerkBalances;
import games.brennan.dungeontrain.perks.PerkMenu;
import games.brennan.dungeontrain.perks.PerkPoints;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Edible find in three tiers (Cosmic Apple, Celestial Bite, Violet Core): {@link #points} active perk
 * points for this life, then the perks menu opens to spend them.
 */
public class CosmicAppleItem extends Item {

    public static final FoodProperties FOOD = new FoodProperties.Builder()
        .nutrition(1).saturationModifier(0.1f).alwaysEdible().fast().build();

    static final String KEY_GAINED = "chat.dungeontrain.perks.active_gained";

    private final int points;

    public CosmicAppleItem(Properties properties, int points) {
        super(properties.food(FOOD));
        this.points = points;
    }

    public int points() {
        return points;
    }

    /** Any of the three tiers: a perk point, not a meal, so it never breaks the melon diet. */
    public static boolean isCosmicApple(ItemStack stack) {
        return stack.getItem() instanceof CosmicAppleItem;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof ServerPlayer player) {
            PerkPoints.addActive(player, points);
            PerkBalances.sync(player);
            player.sendSystemMessage(Component.translatable(KEY_GAINED, stack.getHoverName(), points,
                    PerkPoints.active(player))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
            PerkMenu.openPerksMenu(player);
        }
        return super.finishUsingItem(stack, level, entity);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(getDescriptionId() + ".tooltip").withStyle(ChatFormatting.GRAY));
    }
}
