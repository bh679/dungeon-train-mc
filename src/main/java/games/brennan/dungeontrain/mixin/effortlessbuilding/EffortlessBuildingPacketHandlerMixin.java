package games.brennan.dungeontrain.mixin.effortlessbuilding;

import games.brennan.dungeontrain.compat.EffortlessBuildingGate;
import games.brennan.dungeontrain.compat.EffortlessBuildingHistory;
import games.brennan.dungeontrain.compat.EffortlessBuildingMirror;
import games.brennan.dungeontrain.compat.EffortlessBuildingVariantBreaks;
import games.brennan.dungeontrain.compat.EffortlessBuildingVariants;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Five jobs on one set of seams: gates Effortless Building's creative features behind the Free
 * Play confirmation, records what they change into the editor's undo history, with a
 * variant clipboard in hand turns a shape placement into a bulk clipboard paste
 * ({@link EffortlessBuildingVariants}), and clears the variant pools of the cells a shape break
 * empties ({@link EffortlessBuildingVariantBreaks}), as a hand break does, and carries the
 * editor's live mirroring over to every cell a build writes ({@link EffortlessBuildingMirror}).
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
        // A variant clipboard in hand: the shape is a bulk paste, handled by DT. Cancelled before
        // begin() — the paste records its own undo step (block + sidecar) through the editor's
        // pending-sidecar snapshot, so no block-diff capture is needed here.
        if (EffortlessBuildingVariants.tryClipboardBuild(packet, player)) {
            ci.cancel();
            return;
        }
        EffortlessBuildingHistory.begin(player, EffortlessBuildingHistory.PLACE);
        EffortlessBuildingMirror.begin(player);
    }

    @Inject(method = "handlePlaceBuildMode", at = @At("RETURN"), remap = false)
    private static void dungeontrain$afterPlaceBuildMode(
            @Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        EffortlessBuildingMirror.flush(player);
        EffortlessBuildingHistory.end(player);
    }

    @Inject(method = "handleBreakBuildMode", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$beforeBreakBuildMode(
            @Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) {
            ci.cancel();
            return;
        }
        // Noted before the capture opens so the capture can include this plot's sidecar: the
        // pools the break clears then come back with its blocks on one Ctrl+Z.
        String variantPlotKey = EffortlessBuildingVariantBreaks.begin(player);
        EffortlessBuildingHistory.begin(player, EffortlessBuildingHistory.BREAK, variantPlotKey);
        EffortlessBuildingMirror.begin(player);
    }

    @Inject(method = "handleBreakBuildMode", at = @At("RETURN"), remap = false)
    private static void dungeontrain$afterBreakBuildMode(
            @Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        // Pools cleared before the capture closes, so the removal lands in the same undo step.
        EffortlessBuildingVariantBreaks.end(player);
        EffortlessBuildingMirror.flush(player);
        EffortlessBuildingHistory.end(player);
    }

    @Inject(method = "handleUndo", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$beforeUndo(ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) {
            ci.cancel();
            return;
        }
        EffortlessBuildingHistory.begin(player, EffortlessBuildingHistory.UNDO);
        EffortlessBuildingMirror.begin(player);
    }

    @Inject(method = "handleUndo", at = @At("RETURN"), remap = false)
    private static void dungeontrain$afterUndo(ServerPlayer player, CallbackInfo ci) {
        EffortlessBuildingMirror.flush(player);
        EffortlessBuildingHistory.end(player);
    }

    @Inject(method = "handleRedo", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$beforeRedo(ServerPlayer player, CallbackInfo ci) {
        if (EffortlessBuildingGate.gate(player)) {
            ci.cancel();
            return;
        }
        EffortlessBuildingHistory.begin(player, EffortlessBuildingHistory.REDO);
        EffortlessBuildingMirror.begin(player);
    }

    @Inject(method = "handleRedo", at = @At("RETURN"), remap = false)
    private static void dungeontrain$afterRedo(ServerPlayer player, CallbackInfo ci) {
        EffortlessBuildingMirror.flush(player);
        EffortlessBuildingHistory.end(player);
    }

    /** Every block a shape place or break writes, noted for {@link EffortlessBuildingMirror#flush}. */
    @WrapOperation(
        method = {"handlePlaceBuildMode", "handleBreakBuildMode"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
        remap = false)
    private static boolean dungeontrain$recordSetBlock(
            ServerLevel level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        boolean changed = original.call(level, pos, state, flags);
        if (changed) EffortlessBuildingMirror.record(pos);
        return changed;
    }

    @WrapOperation(
        method = "handleBreakBuildMode",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;)Z"),
        remap = false)
    private static boolean dungeontrain$recordDestroyBlock(
            ServerLevel level, BlockPos pos, boolean drop, Entity breaker, Operation<Boolean> original) {
        boolean changed = original.call(level, pos, drop, breaker);
        if (changed) EffortlessBuildingMirror.record(pos);
        return changed;
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
