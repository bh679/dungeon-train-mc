package games.brennan.dungeontrain.mixin.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes every Sable sub-level's render data when the client level it belongs to is torn down, so the
 * carriages' queued section rebuilds are cancelled along with vanilla's.
 *
 * <p>{@code LevelRenderer.setLevel} cancels the rebuild tasks of the sections it owns
 * ({@code ViewArea.releaseAllBuffers} → {@code RenderSection.reset} → {@code cancelTasks}). Sable's
 * sub-level sections schedule their rebuilds on the same dispatcher but live outside the
 * {@code ViewArea}, and Sable only closes them when a sub-level is removed — never on logout. A task
 * still waiting on a worker therefore ran after the world was gone and crashed the client, first in
 * Sable's shading hook ({@code "container" is null}) and, past that, in vanilla's
 * {@code SectionOcclusionGraph.onSectionCompiled}. With the task cancelled, {@code RebuildTask}'s own
 * {@code isCancelled} checks drop it at whichever stage it has reached.</p>
 *
 * <p>{@link SubLevelRenderData#close()} is what Sable's own {@code ClientSubLevel.onRemove()} calls.
 * Injected at HEAD because {@code this.level} is still the outgoing level there; {@code Minecraft.level}
 * is already null on a disconnect. See {@link SectionCompilerNoLevelGuardMixin} for the backstop that
 * covers the instant between those two. Remove both once Sable tears its render data down upstream.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSubLevelTeardownMixin {

    @Unique
    private static final Logger dungeontrain$LOGGER = LogUtils.getLogger();

    @Shadow
    @Nullable
    private ClientLevel level;

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void dungeontrain$closeSubLevelRenderData(@Nullable ClientLevel newLevel, CallbackInfo ci) {
        ClientLevel outgoing = this.level;
        if (outgoing == null || outgoing == newLevel) return;
        try {
            ClientSubLevelContainer container = SubLevelContainer.getContainer(outgoing);
            if (container == null) return;
            for (ClientSubLevel subLevel : container.getAllSubLevels()) {
                SubLevelRenderData renderData = subLevel.getRenderData();
                if (renderData != null) renderData.close();
            }
        } catch (RuntimeException e) {
            // Leaving a world must never fail on this; the compile guard still covers the crash.
            dungeontrain$LOGGER.warn("[DungeonTrain] Could not close sub-level render data on level teardown", e);
        }
    }
}
