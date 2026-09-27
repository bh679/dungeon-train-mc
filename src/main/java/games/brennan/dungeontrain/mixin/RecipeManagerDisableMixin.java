package games.brennan.dungeontrain.mixin;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.DisabledModContent;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drops every recipe whose result is a {@link DisabledModContent} item (BetterNether / BetterEnd / BoP
 * armour, weapons, tools) once the recipe manager has loaded — crafting, smithing, smelting and the mods'
 * own recipe types alike. Runs on every datapack (re)load; recipe viewers read the synced result.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerDisableMixin {

    @Unique
    private static final Logger dungeontrain$LOGGER = LogUtils.getLogger();

    @Shadow @Final private HolderLookup.Provider registries;

    @Shadow public abstract java.util.Collection<RecipeHolder<?>> getRecipes();

    @Shadow public abstract void replaceRecipes(Iterable<RecipeHolder<?>> recipes);

    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("TAIL"))
    private void dungeontrain$dropDisabledModGear(Map<ResourceLocation, JsonElement> json, ResourceManager resources,
                                                   ProfilerFiller profiler, CallbackInfo ci) {
        List<RecipeHolder<?>> kept = new ArrayList<>();
        int removed = 0;
        for (RecipeHolder<?> holder : getRecipes()) {
            if (dungeontrain$makesDisabledItem(holder)) {
                removed++;
                dungeontrain$LOGGER.debug("[DT-Disable] recipe {} removed", holder.id());
            } else {
                kept.add(holder);
            }
        }
        if (removed > 0) {
            replaceRecipes(kept);
        }
        dungeontrain$LOGGER.info("[DT-Disable] removed {} mod gear/ore recipes", removed);
    }

    /** Unreadable result (a mod recipe type that needs a live level) → kept; never drop blind. */
    @Unique
    private boolean dungeontrain$makesDisabledItem(RecipeHolder<?> holder) {
        try {
            ItemStack result = holder.value().getResultItem(registries);
            return DisabledModContent.isDisabledItem(result);
        } catch (Throwable t) {
            return false;
        }
    }
}
