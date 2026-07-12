package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes water evaporate when poured inside the Nether transition band's core, exactly as it
 * does in the real Nether — even though the band is still the <b>overworld</b> dimension (where
 * {@code dimensionType().ultraWarm()} is {@code false}).
 *
 * <p>Vanilla gates the evaporation purely on the dimension's {@code ultraWarm} flag inside
 * {@link BucketItem#emptyContents}. We add the same branch — fizz sound + smoke, bucket empties,
 * no water placed — when the target column reads as the Nether core
 * ({@link NetherBand#isInNetherBiome}), and only for water (lava buckets stay normal). The inject
 * runs on both sides like vanilla's own branch, so the acting player hears/sees the effect with
 * no placement misprediction; the core check is side-aware (server: authoritative band data;
 * client: the synced {@link ClientNetherBand}). The {@code !ultraWarm} guard means we only ever
 * <i>add</i> behaviour the overworld lacks — the real Nether is left to vanilla.</p>
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {

    @Shadow @Final public Fluid content;

    @Inject(
            method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dungeontrain$evaporateInNetherCore(Player player, Level level, BlockPos pos, BlockHitResult result,
                                                    ItemStack container, CallbackInfoReturnable<Boolean> cir) {
        if (level.dimensionType().ultraWarm()) return;          // real Nether: vanilla already handles it
        if (!this.content.is(FluidTags.WATER)) return;          // lava etc. behave normally in the core
        if (!dungeontrain$inNetherCore(level, pos.getX())) return;

        // Verbatim copy of vanilla's ultraWarm branch — runs on both sides, just like vanilla.
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        level.playSound(player, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F,
                2.6F + (level.random.nextFloat() - level.random.nextFloat()) * 0.8F);
        for (int i = 0; i < 8; i++) {
            level.addParticle(ParticleTypes.LARGE_SMOKE, x + Math.random(), y + Math.random(), z + Math.random(),
                    0.0, 0.0, 0.0);
        }
        cir.setReturnValue(true); // water consumed, nothing placed
    }

    /** Side-aware "is this world-X inside the Nether core?" — server uses band data, client the synced cache. */
    @Unique
    private static boolean dungeontrain$inNetherCore(Level level, int worldX) {
        if (level instanceof ServerLevel serverLevel) {
            return NetherBand.isInNetherBiome(serverLevel, worldX);
        }
        return ClientNetherBand.netherIntensityAt(worldX) >= NetherBand.NETHER_CORE_RAMP;
    }
}
