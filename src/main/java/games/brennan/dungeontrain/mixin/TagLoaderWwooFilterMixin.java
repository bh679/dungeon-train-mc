package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.WwooTagFilter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagLoader;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Map;

/**
 * Drops WWOO's world-wide {@code minecraft:} tag edits that none of its features need — see
 * {@link WwooTagFilter}. Filters the per-file resource stacks {@code TagLoader.load} iterates, so a
 * dropped WWOO file never reaches the {@code replace}/merge logic; every other pack's copy loads as
 * usual. Any error falls back to the unfiltered stacks.
 */
@Mixin(TagLoader.class)
public abstract class TagLoaderWwooFilterMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @ModifyExpressionValue(
        method = "load",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/resources/FileToIdConverter;listMatchingResourceStacks(Lnet/minecraft/server/packs/resources/ResourceManager;)Ljava/util/Map;"))
    private Map<ResourceLocation, List<Resource>> dungeontrain$dropWwooTagEdits(
            Map<ResourceLocation, List<Resource>> stacks) {
        try {
            return WwooTagFilter.filter(stacks, Resource::sourcePackId);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] WWOO tag filter failed; loading tags unfiltered", t);
            return stacks;
        }
    }
}
