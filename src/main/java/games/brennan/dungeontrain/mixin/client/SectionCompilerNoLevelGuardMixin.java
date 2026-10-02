package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

/**
 * Skips a section compile that runs after the client level is gone, so Sable's
 * {@code dynamic_directional_shading.SectionCompilerMixin} never sees a null level.
 *
 * <p>Sable's {@code sable$preCompile} (HEAD of this method, on a chunk-render worker) reads the global
 * {@code Minecraft.getInstance().level} and dereferences its sub-level container without a null check.
 * {@code Minecraft.disconnect()} nulls that field, then {@code LevelRenderer.setLevel(null)} cancels
 * vanilla's own section tasks — but nothing cancels the rebuild tasks of Sable's sub-level (carriage)
 * sections, so any still waiting on a worker run afterwards and crash the client with
 * {@code "container" is null}. A train keeps ~20 carriages rebuilding next to the player, which makes a
 * disconnect likely to land on one. Reproduced 2026-10-02 by holding rebuild tasks across a disconnect:
 * every survivor was a carriage section.</p>
 *
 * <p>A compile with no client level belongs to a world that is already torn down, so its output is
 * discarded anyway; an empty {@link SectionCompiler.Results} is what {@code RebuildTask} expects for
 * a section with nothing in it. {@code @WrapMethod} wraps Sable's HEAD/TAIL injectors too, so neither
 * runs for a skipped compile. Remove once Sable null-guards its hook upstream.</p>
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerNoLevelGuardMixin {

    @Unique
    private static final Logger dungeontrain$LOGGER = LogUtils.getLogger();

    @Unique
    private static final long dungeontrain$LOG_INTERVAL_MS = 10_000L;

    @Unique
    private static volatile long dungeontrain$lastLogMs;

    @WrapMethod(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;")
    private SectionCompiler.Results dungeontrain$skipWithoutLevel(SectionPos sectionPos, RenderChunkRegion region,
                                                                  VertexSorting sorting, SectionBufferBuilderPack pack,
                                                                  List<AddSectionGeometryEvent.AdditionalSectionRenderer> renderers,
                                                                  Operation<SectionCompiler.Results> original) {
        if (Minecraft.getInstance().level != null) {
            return original.call(sectionPos, region, sorting, pack, renderers);
        }
        long now = System.currentTimeMillis();
        if (now - dungeontrain$lastLogMs >= dungeontrain$LOG_INTERVAL_MS) {
            dungeontrain$lastLogMs = now;
            dungeontrain$LOGGER.info("[DungeonTrain] Skipped section compile at {} after the client level was cleared", sectionPos);
        }
        return new SectionCompiler.Results();
    }
}
