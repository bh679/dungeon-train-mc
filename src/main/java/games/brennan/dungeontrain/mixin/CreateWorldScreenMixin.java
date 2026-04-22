package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.worldgen.FloorYState;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adds a Floor-Y dropdown to the vanilla CreateWorldScreen, visible only when the
 * Dungeon Train world preset is selected. Switching the dropdown swaps the active
 * preset to the matching {@code dungeontrain:dungeon_train_y{N}} variant so the world
 * that actually gets created uses the chosen floor height.
 *
 * <p>Widget lifecycle:
 * <ul>
 *   <li>{@code init} TAIL — builds the {@link CycleButton}, caches the 7 preset holders
 *       by Y value, wires the onChange handler to {@link FloorYState#set(int)} plus a
 *       call to {@link WorldCreationUiState#setWorldType}.</li>
 *   <li>{@code tick} HEAD — toggles visibility based on the currently selected preset
 *       and, on transitions from a non-DT preset back to DT, re-applies the user's
 *       previously chosen Y.</li>
 * </ul>
 *
 * <p>Extends {@link Screen} so mixin code can call the protected
 * {@code addRenderableWidget} inherited from {@link Screen} — the standard pattern for
 * mixins that add widgets to a Screen subclass.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin extends Screen {

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
            new ResourceLocation(DUNGEONTRAIN$NAMESPACE, DUNGEONTRAIN$DEFAULT_PATH));

    @Shadow
    @Final
    WorldCreationUiState uiState;

    @Unique
    private CycleButton<Integer> dungeontrain$floorYButton;

    @Unique
    private Map<Integer, Holder<WorldPreset>> dungeontrain$yPresets;

    @Unique
    private ResourceKey<WorldPreset> dungeontrain$prevPresetKey;

    protected CreateWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void dungeontrain$addFloorYDropdown(CallbackInfo ci) {
        this.dungeontrain$yPresets = dungeontrain$resolvePresets();
        if (this.dungeontrain$yPresets.isEmpty()) {
            DUNGEONTRAIN$LOGGER.warn("Floor-Y presets could not be resolved; skipping dropdown.");
            return;
        }

        List<Integer> values = Arrays.stream(FloorYState.VALUES).boxed().toList();

        CycleButton<Integer> button = CycleButton.<Integer>builder(
                        (Integer y) -> Component.translatable("dungeontrain.floor_y.value", y))
                .withValues(values)
                .withInitialValue(FloorYState.get())
                .withTooltip(y -> Tooltip.create(Component.translatable("dungeontrain.floor_y.tooltip")))
                .create(
                        this.width - 160,
                        8,
                        150,
                        20,
                        Component.translatable("dungeontrain.floor_y.label"),
                        (btn, newValue) -> dungeontrain$onFloorYChanged(newValue));

        this.dungeontrain$floorYButton = button;
        this.addRenderableWidget(button);
        dungeontrain$syncVisibilityAndPreset();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void dungeontrain$tickSync(CallbackInfo ci) {
        dungeontrain$syncVisibilityAndPreset();
    }

    @Unique
    private void dungeontrain$onFloorYChanged(int y) {
        FloorYState.set(y);
        if (this.uiState == null || this.dungeontrain$yPresets == null) {
            return;
        }
        if (!dungeontrain$currentIsDungeonTrain()) {
            return;
        }
        Holder<WorldPreset> target = this.dungeontrain$yPresets.get(y);
        if (target == null) {
            return;
        }
        this.uiState.setWorldType(new WorldCreationUiState.WorldTypeEntry(target));
    }

    @Unique
    private void dungeontrain$syncVisibilityAndPreset() {
        if (this.dungeontrain$floorYButton == null) {
            return;
        }
        Holder<WorldPreset> presetHolder = this.uiState.getWorldType().preset();
        Optional<ResourceKey<WorldPreset>> currentKey = presetHolder.unwrapKey();
        ResourceKey<WorldPreset> curr = currentKey.orElse(null);

        boolean isDT = curr != null && dungeontrain$isDungeonTrainKey(curr);
        this.dungeontrain$floorYButton.visible = isDT;

        // Only re-apply a non-default Y when the user has just TRANSITIONED onto the
        // plain dungeon_train preset from some other preset. This lets the user cycle
        // forward out of a Y-variant (vanilla falls back to dungeon_train, then the
        // next tick we leave it alone so the next forward-cycle reaches Default).
        if (isDT && DUNGEONTRAIN$DEFAULT_KEY.equals(curr)) {
            boolean cameFromOutside = this.dungeontrain$prevPresetKey == null
                    || !dungeontrain$isDungeonTrainKey(this.dungeontrain$prevPresetKey);
            if (cameFromOutside && FloorYState.get() != FloorYState.DEFAULT) {
                Holder<WorldPreset> target = this.dungeontrain$yPresets.get(FloorYState.get());
                if (target != null) {
                    this.uiState.setWorldType(new WorldCreationUiState.WorldTypeEntry(target));
                    curr = target.unwrapKey().orElse(curr);
                }
            }
        }
        this.dungeontrain$prevPresetKey = curr;
    }

    @Unique
    private boolean dungeontrain$currentIsDungeonTrain() {
        return this.uiState.getWorldType().preset().unwrapKey()
                .map(CreateWorldScreenMixin::dungeontrain$isDungeonTrainKey)
                .orElse(false);
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
                    new ResourceLocation(DUNGEONTRAIN$NAMESPACE, path));
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
