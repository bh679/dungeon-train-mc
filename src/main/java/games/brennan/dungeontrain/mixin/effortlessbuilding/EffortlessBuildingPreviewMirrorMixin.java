package games.brennan.dungeontrain.mixin.effortlessbuilding;

import games.brennan.dungeontrain.compat.EffortlessBuildingPreviewMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;

/**
 * Appends DT editor mirror images to Effortless Building's client preview — see
 * {@link EffortlessBuildingPreviewMirror}. Client-side only by the {@code isClientSide} guard; the
 * server's pipeline never needs it, since {@link games.brennan.dungeontrain.compat.EffortlessBuildingMirror}
 * mirrors the real writes.
 *
 * <p>Same fragility contract as {@link EffortlessBuildingPacketHandlerMixin}: read out of the pinned
 * {@code effortlessbuilding-4.2+1.21.1}, {@code required: false}, {@code remap = false}.</p>
 */
@Mixin(targets = "neoforge.nl.requios.effortlessbuilding.modifier.ModifierSystem", remap = false)
public abstract class EffortlessBuildingPreviewMirrorMixin {

    @Inject(method = "processBlocks", at = @At("RETURN"), remap = false)
    private void dungeontrain$mirrorPreview(@Coerce LinkedHashMap<BlockPos, Object> set, Player player,
                                            @Coerce Object buildState, CallbackInfo ci) {
        if (player == null || !player.level().isClientSide()) return;
        EffortlessBuildingPreviewMirror.addImages(set);
    }
}
