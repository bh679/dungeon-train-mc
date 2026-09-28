package games.brennan.dungeontrain.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.FancySubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import games.brennan.dungeontrain.client.portal.SubLevelSealFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps the train out of a portal twin — the Sable half of the seal cut
 * {@link FrustumPortalSealMixin} makes for the world.
 *
 * <p>Sub-levels are drawn in Sable's own pass, so vanilla's frustum never gets asked about them.
 * Each of the dispatcher's three draw calls takes the sub-levels to draw as its first argument, and
 * this filters that argument — see {@link SubLevelSealFilter}, which is where the decision and the
 * "free unless sealed" short-circuit live.</p>
 *
 * <p><b>Filtered at the draw, not at the cull.</b> {@code updateCulling} is the tempting hook and the
 * wrong one: skipping a sub-level there leaves its per-section visibility at whatever the last frame
 * decided, which is "visible" for the train the player just walked off. Dropping it from the draw
 * itself cannot go stale.</p>
 *
 * <p>{@code ReachAroundSubLevelRenderDispatcher} extends the vanilla one and inherits all three, so
 * the two targets here cover every renderer Sable ships. {@code require = 0} on each: a Sable
 * refactor of these signatures should cost the train's half of the cut, not the client's launch.</p>
 */
@Mixin(value = {VanillaSubLevelRenderDispatcher.class, FancySubLevelRenderDispatcher.class},
       remap = false)
public abstract class SubLevelSealCullMixin {

    @ModifyVariable(method = "renderSectionLayer", at = @At("HEAD"), argsOnly = true, index = 1,
                    remap = false, require = 0)
    private Iterable<ClientSubLevel> dungeontrain$cullSections(Iterable<ClientSubLevel> subLevels) {
        return SubLevelSealFilter.beyondSeal(subLevels);
    }

    @ModifyVariable(method = "renderAfterSections", at = @At("HEAD"), argsOnly = true, index = 1,
                    remap = false, require = 0)
    private Iterable<ClientSubLevel> dungeontrain$cullAfterSections(Iterable<ClientSubLevel> subLevels) {
        return SubLevelSealFilter.beyondSeal(subLevels);
    }

    @ModifyVariable(method = "renderBlockEntities", at = @At("HEAD"), argsOnly = true, index = 1,
                    remap = false, require = 0)
    private Iterable<ClientSubLevel> dungeontrain$cullBlockEntities(Iterable<ClientSubLevel> subLevels) {
        return SubLevelSealFilter.beyondSeal(subLevels);
    }
}
