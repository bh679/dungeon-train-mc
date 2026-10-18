package games.brennan.dungeontrain.mixin.vanillabackport;

import com.blackgear.platform.core.util.config.ConfigBuilder;
import com.blackgear.vanillabackport.common.level.blockentities.CreakingHeartBlockEntity;
import com.blackgear.vanillabackport.common.level.entities.creaking.Creaking;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.compat.CreakingHeartExtras;
import games.brennan.dungeontrain.compat.CreakingHeartSettings;
import games.brennan.dungeontrain.compat.CreakingHeartSettingsHolder;
import games.brennan.dungeontrain.compat.CreakingHeartTicking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

/**
 * A creaking heart's DT settings ({@link CreakingHeartSettings}): kept on the block entity under
 * {@link CreakingHeartSettings#TAG}, so they save into a contents template and reach the client
 * for the settings menu through VanillaBackport's own update tag.
 *
 * <ul>
 *   <li><b>Day / Night</b>: VanillaBackport wakes a heart on {@code doCreakingHeartsWorkOnDay ||
 *       isNaturalNight}. The config read is answered {@code false} and the night check becomes
 *       the heart's own "awake now" ({@link CreakingHeartSettings#awake}), which is also false when
 *       the stamp-time Level / Stage / Band gate turned the heart off.</li>
 *   <li><b>Number of Creaking</b>: the extras beyond VanillaBackport's one
 *       ({@link CreakingHeartExtras}), recognised as the heart's own by {@code isProtector}.</li>
 * </ul>
 */
@Mixin(value = CreakingHeartBlockEntity.class, remap = false)
public abstract class CreakingHeartSettingsMixin implements CreakingHeartSettingsHolder, CreakingHeartTicking {

    @Unique
    private CreakingHeartSettings dungeontrain$settings = CreakingHeartSettings.DEFAULT;
    @Unique
    private List<UUID> dungeontrain$extras = List.of();
    @Unique
    private long dungeontrain$age;
    /**
     * The count a heart never stamped onto a carriage (an editor plot, a hand-placed heart) picked
     * the first time it needed one; {@link CreakingHeartSettings#NOT_ROLLED} until then. Kept in
     * memory only, so a template saved from the plot carries the range, not a pick.
     */
    @Unique
    private int dungeontrain$localRoll = CreakingHeartSettings.NOT_ROLLED;

    @Override
    public CreakingHeartSettings dungeontrain$settings() {
        return dungeontrain$settings;
    }

    @Override
    public void dungeontrain$setSettings(CreakingHeartSettings settings) {
        dungeontrain$settings = settings == null ? CreakingHeartSettings.DEFAULT : settings;
        BlockEntity self = (BlockEntity) (Object) this;
        self.setChanged();
        Level level = self.getLevel();
        if (level != null && !level.isClientSide()) {
            BlockState state = self.getBlockState();
            level.sendBlockUpdated(self.getBlockPos(), state, state, Block.UPDATE_CLIENTS);
        }
    }

    // ---- persistence ----

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void dungeontrain$saveSettings(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        // The extra Creaking are not saved: their ids would be baked into every template captured
        // from this heart. After a reload the old ones find no protector and die, and the heart
        // tops back up — the same as VanillaBackport's own Creaking once its id grace runs out.
        tag.put(CreakingHeartSettings.TAG, dungeontrain$settings.save());
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void dungeontrain$loadSettings(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        dungeontrain$settings = CreakingHeartSettings.load(tag.getCompound(CreakingHeartSettings.TAG));
    }

    // ---- Day / Night ----

    @WrapOperation(method = {"serverTick", "updateCreakingState"}, at = @At(value = "INVOKE",
            target = "Lcom/blackgear/vanillabackport/common/level/blocks/CreakingHeartBlock;isNaturalNight(Lnet/minecraft/world/level/Level;)Z"))
    private static boolean dungeontrain$awakeNow(Level level, Operation<Boolean> original,
                                                 @Local(argsOnly = true) CreakingHeartBlockEntity heart) {
        return ((CreakingHeartSettingsHolder) heart).dungeontrain$settings().awake(original.call(level));
    }

    @WrapOperation(method = {"serverTick", "updateCreakingState"}, at = @At(value = "INVOKE",
            target = "Lcom/blackgear/platform/core/util/config/ConfigBuilder$ConfigValue;get()Ljava/lang/Object;"))
    private static Object dungeontrain$noGlobalDayOverride(ConfigBuilder.ConfigValue<?> value, Operation<Object> original) {
        return Boolean.FALSE;
    }

    // ---- Number of Creaking ----

    @Inject(method = "serverTick", at = @At("RETURN"))
    private static void dungeontrain$tickExtrasHook(Level level, BlockPos pos, BlockState state,
                                                    CreakingHeartBlockEntity heart, CallbackInfo ci) {
        if (level instanceof ServerLevel server) ((CreakingHeartTicking) heart).dungeontrain$tickExtras(server, pos);
    }

    @Override
    public void dungeontrain$tickExtras(ServerLevel server, BlockPos pos) {
        dungeontrain$age++;
        if (dungeontrain$extras.isEmpty() && dungeontrain$settings.maxCount() <= 1) return;
        CreakingHeartBlockEntity heart = (CreakingHeartBlockEntity) (Object) this;
        boolean hasMain = ((CreakingHeartBlockEntityInvoker) heart).dungeontrain$creakingProtector().isPresent();
        List<UUID> next = CreakingHeartExtras.tick(server, pos, dungeontrain$target(server), hasMain,
            dungeontrain$age, dungeontrain$extras,
            () -> CreakingHeartBlockEntityInvoker.dungeontrain$spawnProtector(server, heart));
        if (next != dungeontrain$extras) {
            dungeontrain$extras = next;
            heart.setChanged();
        }
    }

    /** The stamp's pick, else this heart's own one-time pick in the range. */
    @Unique
    private int dungeontrain$target(ServerLevel server) {
        CreakingHeartSettings s = dungeontrain$settings;
        if (s.rolledCount() != CreakingHeartSettings.NOT_ROLLED) return s.rolledCount();
        if (dungeontrain$localRoll < s.minCount() || dungeontrain$localRoll > s.maxCount()) {
            dungeontrain$localRoll = s.pickCount(server.getRandom().nextInt(Integer.MAX_VALUE));
        }
        return dungeontrain$localRoll;
    }

    @Inject(method = "removeProtector", at = @At("HEAD"))
    private void dungeontrain$removeExtras(@Nullable DamageSource source, CallbackInfo ci) {
        if (dungeontrain$extras.isEmpty()) return;
        if (((BlockEntity) (Object) this).getLevel() instanceof ServerLevel server) {
            CreakingHeartExtras.removeAll(server, dungeontrain$extras, source);
        }
        dungeontrain$extras = List.of();
        ((BlockEntity) (Object) this).setChanged();
    }

    @Inject(method = "isProtector", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$extrasAreProtectors(Creaking creaking, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && dungeontrain$extras.contains(creaking.getUUID())) cir.setReturnValue(true);
    }
}
