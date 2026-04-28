package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.worldgen.FloorYState;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Screen-level companion mixin to the World-tab mixin. Handles two things:
 * <ul>
 *   <li>{@code init} TAIL — resolves the seven {@code dungeontrain:dungeon_train_y{N}}
 *       world-preset holders from the registry and publishes them to
 *       {@link FloorYState#presets} for the World-tab click handler to consume.</li>
 *   <li>{@code tick} HEAD — toggles the visibility of the World-tab's Floor-Y button
 *       based on whether the currently selected world preset is a Dungeon Train variant,
 *       and re-applies the chosen Y when the user cycles back onto the plain DT preset
 *       from a non-DT preset.</li>
 * </ul>
 *
 * <p>No cleanup on screen close is needed — {@link FloorYState#button} and
 * {@link FloorYState#presets} are overwritten the next time a CreateWorldScreen + WorldTab
 * pair is constructed.</p>
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Unique
    private static final Logger DUNGEONTRAIN$LOGGER = LogUtils.getLogger();

    @Unique
    private static final String DUNGEONTRAIN$NAMESPACE = DungeonTrain.MOD_ID;

    @Unique
    private static final String DUNGEONTRAIN$DEFAULT_PATH = "dungeon_train";

    @Unique
    private static final String DUNGEONTRAIN$Y_PATH_PREFIX = "dungeon_train_y";

    @Unique
    @SuppressWarnings("removal")
    private static final ResourceKey<WorldPreset> DUNGEONTRAIN$DEFAULT_KEY = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(DUNGEONTRAIN$NAMESPACE, DUNGEONTRAIN$DEFAULT_PATH));

    @Shadow
    @Final
    WorldCreationUiState uiState;

    @Unique
    private ResourceKey<WorldPreset> dungeontrain$prevPresetKey;

    @Inject(method = "init", at = @At("TAIL"))
    private void dungeontrain$publishPresets(CallbackInfo ci) {
        FloorYState.presets = dungeontrain$resolvePresets();
        if (FloorYState.presets.isEmpty()) {
            DUNGEONTRAIN$LOGGER.warn("Floor-Y presets could not be resolved; dropdown will be a no-op.");
        }
        dungeontrain$syncVisibilityAndPreset();
    }

    // NeoForge 1.21.1 migration: the per-tick visibility sync that previously
    // hooked CreateWorldScreen.tick() is dropped — CreateWorldScreen inherits
    // tick() from Screen and the mixin processor refuses to inject into a
    // non-overridden inherited method. The Floor-Y button visibility now
    // settles once at init-TAIL; cycling presets without leaving and re-opening
    // the screen won't refresh button visibility live until this is wired up
    // to ScreenEvent.Render.Pre or a similar NeoForge event in a follow-up.

    @Unique
    private void dungeontrain$syncVisibilityAndPreset() {
        CycleButton<Integer> button = FloorYState.button;
        if (button == null) {
            return;
        }
        Holder<WorldPreset> presetHolder = this.uiState.getWorldType().preset();
        Optional<ResourceKey<WorldPreset>> currentKey = presetHolder.unwrapKey();
        ResourceKey<WorldPreset> curr = currentKey.orElse(null);

        boolean isDT = curr != null && dungeontrain$isDungeonTrainKey(curr);
        button.visible = isDT;

        // Only re-apply a non-default Y when the user has just TRANSITIONED onto the
        // plain dungeon_train preset from some other preset. This keeps the preset cycle
        // button usable: cycling forward out of a Y-variant falls back to dungeon_train,
        // and the next tick leaves it alone so the next forward-cycle reaches Default.
        if (isDT && DUNGEONTRAIN$DEFAULT_KEY.equals(curr)
                && FloorYState.presets != null
                && FloorYState.get() != FloorYState.DEFAULT) {
            boolean cameFromOutside = this.dungeontrain$prevPresetKey == null
                    || !dungeontrain$isDungeonTrainKey(this.dungeontrain$prevPresetKey);
            if (cameFromOutside) {
                Holder<WorldPreset> target = FloorYState.presets.get(FloorYState.get());
                if (target != null) {
                    this.uiState.setWorldType(new WorldCreationUiState.WorldTypeEntry(target));
                    curr = target.unwrapKey().orElse(curr);
                }
            }
        }
        this.dungeontrain$prevPresetKey = curr;
    }

    @Unique
    private static boolean dungeontrain$isDungeonTrainKey(ResourceKey<WorldPreset> key) {
        if (!DUNGEONTRAIN$NAMESPACE.equals(key.location().getNamespace())) {
            return false;
        }
        String path = key.location().getPath();
        return DUNGEONTRAIN$DEFAULT_PATH.equals(path) || path.startsWith(DUNGEONTRAIN$Y_PATH_PREFIX);
    }

    @Unique
    @SuppressWarnings("removal")
    private Map<Integer, Holder<WorldPreset>> dungeontrain$resolvePresets() {
        Map<Integer, Holder<WorldPreset>> map = new HashMap<>();
        if (this.uiState == null || this.uiState.getSettings() == null) {
            return map;
        }
        RegistryAccess.Frozen registryAccess = this.uiState.getSettings().worldgenLoadContext();
        Registry<WorldPreset> presetRegistry = registryAccess.registryOrThrow(Registries.WORLD_PRESET);
        for (int y : FloorYState.VALUES) {
            String path = (y == FloorYState.DEFAULT)
                    ? DUNGEONTRAIN$DEFAULT_PATH
                    : DUNGEONTRAIN$Y_PATH_PREFIX + y;
            ResourceKey<WorldPreset> key = ResourceKey.create(
                    Registries.WORLD_PRESET,
                    ResourceLocation.fromNamespaceAndPath(DUNGEONTRAIN$NAMESPACE, path));
            Optional<Holder.Reference<WorldPreset>> holder = presetRegistry.getHolder(key);
            if (holder.isPresent()) {
                map.put(y, holder.get());
            } else {
                DUNGEONTRAIN$LOGGER.warn("Floor-Y preset not found in registry: {}", key.location());
            }
        }
        return map;
    }
}
