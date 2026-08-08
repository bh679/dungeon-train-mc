package games.brennan.dungeontrain.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import games.brennan.dungeontrain.client.lod.CarriageLod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Skips Sable's per-sub-level chunk rendering for carriages DT has demoted to the baked-mesh LOD
 * tier. This is where the render saving actually lands: Sable runs a cull pass, a
 * {@code renderSectionLayer} draw and a block-entity pass <em>per sub-level per RenderType</em>,
 * each with its own shader-uniform setup, so client cost scales with sub-level count rather than
 * block count. Filtering the far carriages out of those iterations is what makes a long train stop
 * costing a long train.
 *
 * <p>{@link games.brennan.dungeontrain.client.lod.CarriageMeshRenderer} draws the baked mesh in
 * their place, so nothing disappears — the carriage is simply drawn as one buffer instead of a set
 * of chunk sections.</p>
 *
 * <h2>Why one target covers both renderers</h2>
 *
 * <p>Sable selects between two dispatchers ({@code SubLevelRenderer.SelectedRenderer}):
 * {@code VANILLA} and {@code SODIUM_REACHAROUND}. {@code ReachAroundSubLevelRenderDispatcher}
 * <em>extends</em> {@link VanillaSubLevelRenderDispatcher} and overrides only
 * {@code rebuild}/{@code createRenderData}/{@code preRenderChunks}/{@code free} — all four methods
 * gated here are inherited. So mixing into the superclass covers the Sodium path too, which
 * matters because Sodium is the shipped modpack configuration.</p>
 *
 * <p>Bytecode-verified against {@code sable-2.0.2+mc1.21.1}. <b>Re-enumerate this method set on any
 * {@code sable_version} bump</b> — same standing rule as
 * {@link games.brennan.dungeontrain.mixin.ServerSubLevelFreezeMixin}. Because nothing is removed or
 * freed, a gate that silently stops matching is a perf regression, never a crash.</p>
 *
 * <p>{@code remap = false}: Sable's own names.</p>
 */
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class SubLevelRenderDispatcherLodMixin {

    @ModifyVariable(method = "updateCulling", at = @At("HEAD"), argsOnly = true, index = 1)
    private Iterable<ClientSubLevel> dungeonTrain$skipFarForCulling(Iterable<ClientSubLevel> subLevels) {
        return dungeonTrain$excludeFar(subLevels);
    }

    @ModifyVariable(method = "renderSectionLayer", at = @At("HEAD"), argsOnly = true, index = 1)
    private Iterable<ClientSubLevel> dungeonTrain$skipFarForSectionLayer(Iterable<ClientSubLevel> subLevels) {
        return dungeonTrain$excludeFar(subLevels);
    }

    @ModifyVariable(method = "renderAfterSections", at = @At("HEAD"), argsOnly = true, index = 1)
    private Iterable<ClientSubLevel> dungeonTrain$skipFarAfterSections(Iterable<ClientSubLevel> subLevels) {
        return dungeonTrain$excludeFar(subLevels);
    }

    @ModifyVariable(method = "renderBlockEntities", at = @At("HEAD"), argsOnly = true, index = 1)
    private Iterable<ClientSubLevel> dungeonTrain$skipFarBlockEntities(Iterable<ClientSubLevel> subLevels) {
        return dungeonTrain$excludeFar(subLevels);
    }

    /**
     * Copy out the near sub-levels, or hand back the original list untouched when nothing is far.
     *
     * <p>The early-out is the point: with the LOD off, or with a short train entirely inside the
     * threshold, this costs one pass of a boolean field read and allocates nothing — so the mixin
     * is free in the case where it has no work to do, which is most frames for most players.</p>
     */
    @Unique
    private static Iterable<ClientSubLevel> dungeonTrain$excludeFar(Iterable<ClientSubLevel> subLevels) {
        if (subLevels == null) return null;

        boolean anyFar = false;
        for (ClientSubLevel sl : subLevels) {
            if (CarriageLod.isFar(sl)) { anyFar = true; break; }
        }
        if (!anyFar) return subLevels;

        List<ClientSubLevel> near = new ArrayList<>();
        for (ClientSubLevel sl : subLevels) {
            if (!CarriageLod.isFar(sl)) near.add(sl);
        }
        return near;
    }
}
