package games.brennan.dungeontrain.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import games.brennan.dungeontrain.client.lod.DtLodRenderable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Carries DT's per-sub-level "render far" flag on {@link ClientSubLevel} via
 * {@link DtLodRenderable}. Client-side mirror of
 * {@link games.brennan.dungeontrain.mixin.ServerSubLevelFreezeMixin}, which does the same for the
 * physics soft-freeze flag — and for the same reason: the flag is read on the hot render path
 * (once per sub-level per RenderType per frame, plus each section-compile pass), so it has to be a
 * plain field read rather than a map lookup, and living on the instance means it dies with the
 * sub-level instead of leaking an entry.
 *
 * <p>Storage only — no behaviour is injected here. The gating lives in
 * {@link SubLevelRenderDispatcherLodMixin} (culling, draws, block entities) and
 * {@link SubLevelRenderDataLodMixin} (section compilation).</p>
 *
 * <p>{@code remap = false}: the target is Sable's own class, not Minecraft mappings.</p>
 */
@Mixin(value = ClientSubLevel.class, remap = false)
public abstract class ClientSubLevelLodMixin implements DtLodRenderable {

    @Unique private boolean dungeonTrain$lodFar;
    @Unique private int dungeonTrain$farTicks;

    @Override
    public boolean dt$isLodFar() {
        return dungeonTrain$lodFar;
    }

    @Override
    public void dt$setLodFar(boolean far) {
        this.dungeonTrain$lodFar = far;
    }

    @Override
    public int dt$farTicks() {
        return dungeonTrain$farTicks;
    }

    @Override
    public void dt$setFarTicks(int ticks) {
        this.dungeonTrain$farTicks = ticks;
    }
}
