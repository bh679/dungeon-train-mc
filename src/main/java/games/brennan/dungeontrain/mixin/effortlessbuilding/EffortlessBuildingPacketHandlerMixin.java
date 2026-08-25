package games.brennan.dungeontrain.mixin.effortlessbuilding;

import games.brennan.dungeontrain.compat.EffortlessBuildingGate;
import games.brennan.dungeontrain.compat.EffortlessBuildingHistory;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two jobs on one set of seams: gates Effortless Building's creative features behind the Free Play
 * confirmation, and records what they change into the editor's undo history.
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
 * <p><b>The undo half.</b> That same "fires no block events" property is why the editor's own
 * recorder cannot see these builds either, so the four block-changing handlers are also wrapped in
 * an {@code @At("HEAD")} / {@code @At("RETURN")} pair around
 * {@link EffortlessBuildingHistory#begin} / {@link EffortlessBuildingHistory#end} — one Ctrl+Z per
 * Effortless Building action, on the same stack as every hand-placed edit. The begin sits
 * <i>after</i> the gate check so a declined prompt opens no capture, and {@code end} is a no-op
 * when nothing is open, which keeps the pair correct whichever order Mixin applies the two
 * injectors in. {@code handleUpdateModifiers} gets no pair — it writes no blocks.</p>
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
    private static void dungeontrain$beforePlaceBuildMode(
            @Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) {
            ci.cancel();
            return;
        }
        EffortlessBuildingHistory.begin(player, EffortlessBuildingHistory.PLACE);
    }

    @Inject(method = "handlePlaceBuildMode", at = @At("RETURN"), remap = false)
    private static void dungeontrain$afterPlaceBuildMode(
            @Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        EffortlessBuildingHistory.end(player);
    }

    @Inject(method = "handleBreakBuildMode", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$beforeBreakBuildMode(
            @Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) {
            ci.cancel();
            return;
        }
        EffortlessBuildingHistory.begin(player, EffortlessBuildingHistory.BREAK);
    }

    @Inject(method = "handleBreakBuildMode", at = @At("RETURN"), remap = false)
    private static void dungeontrain$afterBreakBuildMode(
            @Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        EffortlessBuildingHistory.end(player);
    }

    @Inject(method = "handleUndo", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$beforeUndo(ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) {
            ci.cancel();
            return;
        }
        EffortlessBuildingHistory.begin(player, EffortlessBuildingHistory.UNDO);
    }

    @Inject(method = "handleUndo", at = @At("RETURN"), remap = false)
    private static void dungeontrain$afterUndo(ServerPlayer player, CallbackInfo ci) {
        EffortlessBuildingHistory.end(player);
    }

    @Inject(method = "handleRedo", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$beforeRedo(ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) {
            ci.cancel();
            return;
        }
        EffortlessBuildingHistory.begin(player, EffortlessBuildingHistory.REDO);
    }

    @Inject(method = "handleRedo", at = @At("RETURN"), remap = false)
    private static void dungeontrain$afterRedo(ServerPlayer player, CallbackInfo ci) {
        EffortlessBuildingHistory.end(player);
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
