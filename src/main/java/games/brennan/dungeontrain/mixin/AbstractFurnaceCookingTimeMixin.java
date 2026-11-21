package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.CrafterCookingOwner;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterPerks;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterRank;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Cooking Time on furnace, smoker and blast furnace. The block cooks with the rank of the player who
 * last opened its menu ({@code BaseContainerBlockEntityCookingOwnerMixin} records them): every recipe
 * time the block looks up is scaled by {@link CrafterPerks#cookTicks}, and opening the menu
 * re-times the item already cooking so the perk applies at once. The owner lives in memory only.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceCookingTimeMixin extends BlockEntity implements CrafterCookingOwner {

    @Shadow int cookingProgress;
    @Shadow int cookingTotalTime;

    @Shadow
    private static int getTotalCookTime(Level level, AbstractFurnaceBlockEntity blockEntity) {
        throw new AssertionError("shadow");
    }

    @Unique
    private UUID dungeontrain$cookingOwner;

    private AbstractFurnaceCookingTimeMixin() {
        super(null, null, null);
    }

    @Override
    public UUID dungeontrain$cookingOwner() {
        return dungeontrain$cookingOwner;
    }

    @Override
    public void dungeontrain$setCookingOwner(Player player) {
        dungeontrain$cookingOwner = player == null ? null : player.getUUID();
        if (this.level == null || this.level.isClientSide) return;
        int total = getTotalCookTime(this.level, (AbstractFurnaceBlockEntity) (Object) this);
        if (total == this.cookingTotalTime) return;
        this.cookingTotalTime = total;
        if (this.cookingProgress >= total) this.cookingProgress = Math.max(0, total - 1);
        this.setChanged();
    }

    @Inject(method = "getTotalCookTime", at = @At("RETURN"), cancellable = true)
    private static void dungeontrain$scaleCookTime(Level level, AbstractFurnaceBlockEntity blockEntity,
                                                   CallbackInfoReturnable<Integer> cir) {
        if (!((Object) blockEntity instanceof CrafterCookingOwner cook) || cook.dungeontrain$cookingOwner() == null) return;
        Player owner = level.getPlayerByUUID(cook.dungeontrain$cookingOwner());
        int rank = CrafterRank.cookingTime(owner);
        if (rank <= 0) return;
        cir.setReturnValue(CrafterPerks.cookTicks(cir.getReturnValue(), rank));
    }
}
