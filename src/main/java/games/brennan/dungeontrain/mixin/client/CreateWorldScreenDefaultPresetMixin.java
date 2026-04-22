package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes {@code dungeontrain:dungeon_train} the default-selected option in the
 * World Type dropdown on {@link CreateWorldScreen}. Since v0.16.0 the mod ships
 * this preset, but vanilla would otherwise default to "Default" (the vanilla
 * overworld), forcing players to manually pick it every new world.
 *
 * Targets the outer {@link CreateWorldScreen} class at {@code <init>} TAIL so
 * {@code uiState} is already constructed. Switching the preset here fires the
 * uiState listeners, but the screen hasn't been displayed yet, so the
 * subsequent {@code init()} builds the dropdown widget with our preset
 * pre-selected. The constructor runs once per screen instance — {@code init()}
 * re-runs on resize/tab-switch, but doesn't re-invoke us — so a manual user
 * selection persists for the screen's lifetime.
 *
 * If the preset isn't registered (e.g. missing data pack at screen-open time),
 * falls back silently to the vanilla default.
 *
 * Complementary to {@link CreateWorldScreenMixin}, which targets the inner
 * {@code $WorldTab} to add the "Dungeon Train Options…" button row; both
 * coexist without overlap.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenDefaultPresetMixin {

    @Shadow
    public abstract WorldCreationUiState getUiState();

    @Inject(method = "<init>", at = @At("TAIL"))
    @SuppressWarnings("removal") // ResourceLocation ctors deprecated in Forge 47.4 but still idiomatic for 1.20.1
    private void dungeontrain$setDefaultPreset(CallbackInfo ci) {
        ResourceKey<WorldPreset> presetKey = ResourceKey.create(
            Registries.WORLD_PRESET,
            new ResourceLocation(DungeonTrain.MOD_ID, "dungeon_train"));

        WorldCreationUiState state = this.getUiState();
        state.getSettings().worldgenLoadContext()
            .lookup(Registries.WORLD_PRESET)
            .flatMap(lookup -> lookup.get(presetKey))
            .ifPresent(holder -> state.setWorldType(
                new WorldCreationUiState.WorldTypeEntry(holder)));
    }
}
