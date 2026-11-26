package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.alchemist.Brewmaster;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterCookingOwner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Brewmaster at a placed brewing stand: the stand brews with the rank of the player who last opened it
 * ({@code BaseContainerBlockEntityCookingOwnerMixin} records them through {@link CrafterCookingOwner}; in
 * memory only, as Cooking Time's furnaces). {@code doBrew} is wrapped: its three bottles are copied at
 * the start, and every bottle the brew changed is rolled for extra levels at the end.
 */
@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBrewmasterMixin extends BlockEntity implements CrafterCookingOwner {

    @Unique
    private UUID dungeontrain$brewer;

    @Unique
    private static final ThreadLocal<ItemStack[]> DUNGEONTRAIN$BEFORE = new ThreadLocal<>();

    private BrewingStandBrewmasterMixin() {
        super(null, null, null);
    }

    @Override
    public UUID dungeontrain$cookingOwner() {
        return dungeontrain$brewer;
    }

    @Override
    public void dungeontrain$setCookingOwner(Player player) {
        dungeontrain$brewer = player == null ? null : player.getUUID();
    }

    @Inject(method = "doBrew", at = @At("HEAD"))
    private static void dungeontrain$beforeBrew(Level level, BlockPos pos, NonNullList<ItemStack> items, CallbackInfo ci) {
        ItemStack[] before = new ItemStack[Brewmaster.STAND_BOTTLES];
        for (int i = 0; i < before.length; i++) before[i] = items.get(i).copy();
        DUNGEONTRAIN$BEFORE.set(before);
    }

    @Inject(method = "doBrew", at = @At("TAIL"))
    private static void dungeontrain$afterBrew(Level level, BlockPos pos, NonNullList<ItemStack> items, CallbackInfo ci) {
        ItemStack[] before = DUNGEONTRAIN$BEFORE.get();
        DUNGEONTRAIN$BEFORE.remove();
        if (before == null || level.isClientSide) return;
        if (!(level.getBlockEntity(pos) instanceof CrafterCookingOwner stand) || stand.dungeontrain$cookingOwner() == null) return;
        Player brewer = level.getPlayerByUUID(stand.dungeontrain$cookingOwner());
        for (int i = 0; i < before.length; i++) {
            if (ItemStack.matches(before[i], items.get(i))) continue;
            items.set(i, Brewmaster.strengthen(brewer, items.get(i)));
        }
    }
}
