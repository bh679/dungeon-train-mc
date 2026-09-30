package games.brennan.dungeontrain.mixin.terrablender;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.BackportBiomes;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import terrablender.api.Region;
import terrablender.api.Regions;

/**
 * Keeps VanillaBackport's TerraBlender overworld region out of the region layout.
 *
 * <p>DT picks every overworld biome itself ({@code OverworldStretchBiomes}) and never reads that region —
 * VanillaBackport's biomes reach the vanilla stretch through Platform's additions to vanilla's climate table.
 * But a registered region still takes a share of TerraBlender's region layout, which DT reads to place Biomes
 * O' Plenty's regions, so it would reshuffle the BoP stretch. Dropping it keeps that stretch as it was.
 * See {@link BackportBiomes#isVetoedRegion}.</p>
 */
@Mixin(value = Regions.class, remap = false)
public abstract class RegionsMixin {

    @Unique
    private static final Logger DUNGEONTRAIN$LOGGER = LogUtils.getLogger();

    @Inject(method = "register(Lnet/minecraft/resources/ResourceLocation;Lterrablender/api/Region;)V",
            at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$vetoRegion(ResourceLocation owner, Region region, CallbackInfo ci) {
        dungeontrain$veto(region, ci);
    }

    @Inject(method = "register(Lnet/minecraft/resources/ResourceLocation;ILterrablender/api/Region;)V",
            at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$vetoRegionAt(ResourceLocation owner, int index, Region region, CallbackInfo ci) {
        dungeontrain$veto(region, ci);
    }

    @Unique
    private static void dungeontrain$veto(Region region, CallbackInfo ci) {
        if (region == null || !BackportBiomes.isVetoedRegion(region.getName())) return;
        DUNGEONTRAIN$LOGGER.info("[DungeonTrain] TerraBlender: dropped region {} — its biomes stay in the"
                + " vanilla overworld stretches only", region.getName());
        ci.cancel();
    }
}
