package games.brennan.dungeontrain.item;

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

/** Edible find: one active perk point for this life, then the perks menu opens to spend it. */
public class CosmicDebrisItem extends Item {

    public static final FoodProperties FOOD = new FoodProperties.Builder()
        .nutrition(1).saturationModifier(0.1f).alwaysEdible().fast().build();

    static final String KEY_GAINED = "chat.dungeontrain.perks.active_gained";

    public CosmicDebrisItem(Properties properties) {
        super(properties.food(FOOD));
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof ServerPlayer player) {
            PerkPoints.addActive(player, 1);
            player.sendSystemMessage(Component.translatable(KEY_GAINED, PerkPoints.active(player))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
            PerkMenu.openPerksMenu(player);
        }
        return super.finishUsingItem(stack, level, entity);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.dungeontrain.cosmic_debris.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
