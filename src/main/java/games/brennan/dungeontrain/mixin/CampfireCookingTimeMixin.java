package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.CrafterPerks;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterRank;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Cooking Time on campfires: food placed by a perk holder cooks faster. Ownership is per item, the
 * entity that put it on, so two players' steaks on one fire each keep their own timer.
 */
@Mixin(CampfireBlockEntity.class)
public abstract class CampfireCookingTimeMixin {

    @ModifyVariable(method = "placeFood", at = @At("HEAD"), argsOnly = true, index = 3)
    private int dungeontrain$fasterCampfire(int cookTime, LivingEntity entity, ItemStack food, int original) {
        return CrafterPerks.cookTicks(cookTime, CrafterRank.cookingTime(entity));
    }
}
