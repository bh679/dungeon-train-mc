package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.VanillaBiomeTwins;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.AmbientAdditionsSettings;
import net.minecraft.world.level.biome.AmbientMoodSettings;
import net.minecraft.world.level.biome.AmbientParticleSettings;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Outside the WWOO stretch a WWOO-changed biome answers as its vanilla twin
 * ({@link VanillaBiomeTwins}): grass and foliage colour, water, sky and fog colour, rain/snow/ice
 * climate, ambient sounds, music and particles. Questions carrying a position use it; the rest use
 * the client camera. Foliage and water tint are the exception: their getters take no position, but
 * the tint resolvers do, so {@code client.BiomeColorsTwinMixin} swaps the twin in per block. A pure
 * pass-through for biomes WWOO left alone, and inside the stretch.
 */
@Mixin(Biome.class)
public abstract class BiomeMixin {

    private Biome dungeontrain$self() {
        return (Biome) (Object) this;
    }

    private Biome dungeontrain$twinAt(double x) {
        return VanillaBiomeTwins.twinFor(dungeontrain$self(), x);
    }

    private Biome dungeontrain$twinAtCamera() {
        return VanillaBiomeTwins.twinForCamera(dungeontrain$self());
    }

    // ---- with a position -------------------------------------------------------------------

    @Inject(method = "getGrassColor", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$grassColor(double posX, double posZ, CallbackInfoReturnable<Integer> cir) {
        Biome twin = dungeontrain$twinAt(posX);
        if (twin != null) cir.setReturnValue(twin.getGrassColor(posX, posZ));
    }

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$precipitation(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        Biome twin = dungeontrain$twinAt(pos.getX());
        if (twin != null) cir.setReturnValue(twin.getPrecipitationAt(pos));
    }

    @Inject(method = "coldEnoughToSnow", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$coldEnoughToSnow(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Biome twin = dungeontrain$twinAt(pos.getX());
        if (twin != null) cir.setReturnValue(twin.coldEnoughToSnow(pos));
    }

    @Inject(method = "warmEnoughToRain", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$warmEnoughToRain(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Biome twin = dungeontrain$twinAt(pos.getX());
        if (twin != null) cir.setReturnValue(twin.warmEnoughToRain(pos));
    }

    @Inject(method = "shouldSnow", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$shouldSnow(LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Biome twin = dungeontrain$twinAt(pos.getX());
        if (twin != null) cir.setReturnValue(twin.shouldSnow(level, pos));
    }

    @Inject(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z",
            at = @At("HEAD"), cancellable = true)
    private void dungeontrain$shouldFreeze(LevelReader level, BlockPos water, boolean mustBeAtEdge,
                                           CallbackInfoReturnable<Boolean> cir) {
        Biome twin = dungeontrain$twinAt(water.getX());
        if (twin != null) cir.setReturnValue(twin.shouldFreeze(level, water, mustBeAtEdge));
    }

    // ---- without a position: the client camera decides ------------------------------------------

    // Foliage and water colour are NOT gated here: they are tints, resolved per block by
    // client.BiomeColorsTwinMixin. A camera gate here would re-tint that block by where the player stands.

    @Inject(method = "getWaterFogColor", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$waterFogColor(CallbackInfoReturnable<Integer> cir) {
        Biome twin = dungeontrain$twinAtCamera();
        if (twin != null) cir.setReturnValue(twin.getWaterFogColor());
    }

    @Inject(method = "getSkyColor", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skyColor(CallbackInfoReturnable<Integer> cir) {
        Biome twin = dungeontrain$twinAtCamera();
        if (twin != null) cir.setReturnValue(twin.getSkyColor());
    }

    @Inject(method = "getFogColor", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$fogColor(CallbackInfoReturnable<Integer> cir) {
        Biome twin = dungeontrain$twinAtCamera();
        if (twin != null) cir.setReturnValue(twin.getFogColor());
    }

    @Inject(method = "getAmbientParticle", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$ambientParticle(CallbackInfoReturnable<Optional<AmbientParticleSettings>> cir) {
        Biome twin = dungeontrain$twinAtCamera();
        if (twin != null) cir.setReturnValue(twin.getAmbientParticle());
    }

    @Inject(method = "getAmbientLoop", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$ambientLoop(CallbackInfoReturnable<Optional<Holder<SoundEvent>>> cir) {
        Biome twin = dungeontrain$twinAtCamera();
        if (twin != null) cir.setReturnValue(twin.getAmbientLoop());
    }

    @Inject(method = "getAmbientMood", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$ambientMood(CallbackInfoReturnable<Optional<AmbientMoodSettings>> cir) {
        Biome twin = dungeontrain$twinAtCamera();
        if (twin != null) cir.setReturnValue(twin.getAmbientMood());
    }

    @Inject(method = "getAmbientAdditions", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$ambientAdditions(CallbackInfoReturnable<Optional<AmbientAdditionsSettings>> cir) {
        Biome twin = dungeontrain$twinAtCamera();
        if (twin != null) cir.setReturnValue(twin.getAmbientAdditions());
    }

    @Inject(method = "getBackgroundMusic", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$backgroundMusic(CallbackInfoReturnable<Optional<Music>> cir) {
        Biome twin = dungeontrain$twinAtCamera();
        if (twin != null) cir.setReturnValue(twin.getBackgroundMusic());
    }
}
