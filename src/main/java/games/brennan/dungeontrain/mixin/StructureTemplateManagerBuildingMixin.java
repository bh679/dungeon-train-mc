package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.building.BuildingStore;
import games.brennan.dungeontrain.building.Buildings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Loads a player's copy of a building ahead of the jar's — how an edit in the Buildings editor reaches the
 * Lost City and WWOO stretch.
 *
 * <p>Only DT's building templates are touched: {@code dungeontrain:lost_city/<name>} (the shipped buildings)
 * and {@code dungeontrain:buildings/<name>} (new ones). With no player copy — none saved, or the world has
 * custom content turned off, which empties every package search — vanilla loads the jar's copy as before.
 * Runs once per id: the manager caches what this returns until {@code BuildingWorldgen.evict} drops it.</p>
 */
@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerBuildingMixin {

    @Unique
    private static final Logger dungeontrain$LOGGER = LogUtils.getLogger();

    @Inject(method = "tryLoad", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$loadPlayerBuilding(ResourceLocation id,
                                                 CallbackInfoReturnable<Optional<StructureTemplate>> cir) {
        Optional<String> name = Buildings.nameOf(id);
        if (name.isEmpty()) return;
        Optional<CompoundTag> tag = BuildingStore.readPlayerTag(name.get());
        if (tag.isEmpty()) return;
        try {
            StructureTemplate template = ((StructureTemplateManager) (Object) this).readStructure(tag.get());
            dungeontrain$LOGGER.info("[DungeonTrain] Placing the player's copy of building {}", name.get());
            cir.setReturnValue(Optional.of(template));
        } catch (RuntimeException e) {
            // A broken player file falls back to the jar's building, never to a hole in the city.
            dungeontrain$LOGGER.error("[DungeonTrain] Player copy of building {} is unreadable — using the shipped one",
                name.get(), e);
        }
    }
}
