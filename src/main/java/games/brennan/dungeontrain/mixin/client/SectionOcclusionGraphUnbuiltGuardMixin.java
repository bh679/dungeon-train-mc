package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Ignores a compiled section handed to an occlusion graph that has not been built yet, instead of
 * crashing the client with "Batching sections".
 *
 * <h2>What goes wrong without this</h2>
 * <p>Vista (the TV mod) gives every TV feed its own {@code SectionOcclusionGraph} and forwards each
 * freshly compiled section to all of them from {@code LevelRenderer.addRecentlyCompiledSection}, which
 * runs on a section-compile worker thread. On the render thread, a feed's frustum state creates its
 * graph and only afterwards calls {@code waitAndReset}, the one method that gives a graph its state —
 * a new graph's {@code currentGraph} is empty until then. A section that finishes compiling in that
 * gap reaches vanilla's {@code onSectionCompiled}, which reads {@code currentGraph.get().events} and
 * throws a {@code NullPointerException}; vanilla hands it to {@code delayCrash} and the client goes
 * down. Seen 2026-11-13 after ~15 minutes on a train, where carriage TVs make new feeds often.</p>
 *
 * <h2>Why skipping is lossless</h2>
 * <p>A graph without state has no view area to propagate through. {@code waitAndReset} ends by
 * calling {@code invalidate()}, so the graph's first update is a full rebuild that walks every
 * compiled section, this one included.</p>
 *
 * <p>Ungated: vanilla only calls this on its own graph, which is reset before any section compiles,
 * so the guard never fires without Vista. Under Sodium this class is unused.</p>
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphUnbuiltGuardMixin {

    @Shadow
    @Final
    private AtomicReference<?> currentGraph;

    @Inject(method = "onSectionCompiled", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipUnbuiltGraph(SectionRenderDispatcher.RenderSection section, CallbackInfo ci) {
        if (this.currentGraph.get() == null) ci.cancel();
    }
}
