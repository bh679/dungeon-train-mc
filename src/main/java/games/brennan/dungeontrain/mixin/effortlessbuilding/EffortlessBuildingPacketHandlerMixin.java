package games.brennan.dungeontrain.mixin.effortlessbuilding;

import games.brennan.dungeontrain.compat.EffortlessBuildingGate;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gates Effortless Building's creative features behind the Free Play confirmation.
 *
 * <p>Effortless Building drives every build through its own server-bound packets — it fires no
 * NeoForge block events and registers no commands, so neither {@code CheatDetectionEvents.onCommand}
 * nor a block-place listener can see it. These four handlers are the whole server-side surface of the
 * features that matter:</p>
 *
 * <ul>
 *   <li>{@code handlePlaceBuildMode} / {@code handleBreakBuildMode} — a multi-block build ran;</li>
 *   <li>{@code handleUndo} / {@code handleRedo} — a build was rewound;</li>
 *   <li>{@code handleUpdateModifiers} — a mirror / array / radial modifier changed. Only gated when
 *       the packet switches one <b>on</b> ({@link EffortlessBuildingGate#enablesModifier}), so
 *       turning a mirror off never trips Free Play.</li>
 * </ul>
 *
 * <p>Ordinary single-block placement is deliberately <b>not</b> hooked: that path goes through the
 * mod's own {@code MixinBlockItem} and is honest play.</p>
 *
 * <p><b>Version-fragile by nature.</b> The seams were read out of the modpack-pinned build
 * {@code effortlessbuilding-4.2+1.21.1}; the classes sit under a relocated {@code neoforge.} package
 * prefix in that multi-loader jar. If a future build renames a handler the injector simply won't
 * apply — {@code required: false} in the config plus {@link games.brennan.dungeontrain.mixin.EffortlessBuildingMixinPlugin}
 * mean that degrades to "no gating", never a crash. {@code remap = false} throughout: these are
 * another mod's classes, not Minecraft's.</p>
 */
@Mixin(targets = "neoforge.nl.requios.effortlessbuilding.network.PacketHandler", remap = false)
public abstract class EffortlessBuildingPacketHandlerMixin {

    @Inject(method = "handlePlaceBuildMode", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$gatePlaceBuildMode(
            @Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) ci.cancel();
    }

    @Inject(method = "handleBreakBuildMode", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$gateBreakBuildMode(
            @Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) ci.cancel();
    }

    @Inject(method = "handleUndo", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$gateUndo(ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) ci.cancel();
    }

    @Inject(method = "handleRedo", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$gateRedo(ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) ci.cancel();
    }

    /**
     * Cancelling here is what makes a declined prompt honest: the server never stores the modifier,
     * so the player's next placement isn't multiplied.
     */
    @Inject(method = "handleUpdateModifiers", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$gateUpdateModifiers(
            @Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        if (!EffortlessBuildingGate.enablesModifier(packet)) return; // turning one off is free
        if (EffortlessBuildingGate.gate(player)) ci.cancel();
    }
}
