package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a DT carriage group welded together when a player disconnects part of it.
 *
 * <p>A carriage group is assembled as a single Sable {@link ServerSubLevel}
 * ({@code TrainAssembler.spawnGroup} → one {@code Shipyard.assemble} call). Sable runs a
 * per-sub-level connectivity splitter: {@link ServerSubLevel#tick()} calls
 * {@link SubLevelHeatMapManager#tick()} (gated on {@code SableConfig.SUB_LEVEL_SPLITTING}),
 * which flood-fills the sub-level's solid blocks and, when an island becomes disconnected
 * and free-floating, carves it into a brand-new sub-level via {@code split()} — i.e. the
 * loose blocks "become their own ship".</p>
 *
 * <p>DT trains are <b>kinematic</b> — {@link SableManagedShip#applyTickOutput} teleports the
 * whole body every tick — so internal block connectivity is irrelevant to how a train moves.
 * We therefore cancel the heat-map tick (and hence the split) for DT-managed sub-levels, so a
 * disconnected block stays in the group's block grid and keeps travelling with the train. The
 * gate is per-sub-level (via {@link SableManagedShip#isDungeonTrainManaged}), so any non-DT
 * Sable ship still splits normally. Cancelling {@code tick()} is safe: {@code onSolidAdded}/
 * {@code onSolidRemoved} still run to keep the heat-map's internal maps current (they're just
 * never consumed by the split state machine); mass and collision are handled separately by
 * {@code MassTracker} / {@code SubLevelPhysicsSystem} and are untouched.</p>
 *
 * <p>{@code remap = false}: the target class, its {@code tick} method, and the {@code subLevel}
 * field are Sable's own names, not Minecraft mappings. Bytecode-verified against
 * {@code sable-2.0.2+mc1.21.1}. <b>Re-verify method/field names on any {@code sable_version}
 * bump.</b> Mirrors the gating pattern in {@link ServerSubLevelFreezeMixin} /
 * {@link SubLevelPhysicsSystemFreezeMixin}.</p>
 */
@Mixin(value = SubLevelHeatMapManager.class, remap = false)
public abstract class SubLevelHeatMapSplitMixin {

    @Shadow @Final private ServerSubLevel subLevel;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$suppressSplitForManagedShip(CallbackInfo ci) {
        if (SableManagedShip.isDungeonTrainManaged(subLevel)) {
            ci.cancel();
        }
    }
}
